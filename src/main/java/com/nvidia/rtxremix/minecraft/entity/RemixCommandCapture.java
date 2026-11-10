/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.mixin.FeatureRenderDispatcherAccessor;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Bridges the submit model to the capture providers.
 *
 * <p>Vanilla renderers no longer write vertices while they run: they submit model,
 * part, item, and text commands to a {@link SubmitNodeStorage}, and in 26.x the feature
 * renderers replay those into a staged GPU buffer rather than a buffer source. A capture
 * therefore renders into this private queue and then {@link #flush() flushes} it by
 * replaying the vertex-producing submits itself, into the capture provider, inside the
 * capture's {@code EntityRenderContext} bracket, so cuboids and quads still reach
 * {@code RemixCapturingConsumer} on the render thread.
 */
final class RemixCommandCapture implements AutoCloseable {

    private static final FeatureRenderPhase.Output DISCARD = (submit, strictlyOrdered) -> { };

    private final RemixSubmitNodeStorage queue = new RemixSubmitNodeStorage(this);
    private final RemixBufferSource provider;
    private final CameraRenderState cameraState = new CameraRenderState();
    private final List<SubmitNode> singleSubmit = Arrays.asList(new SubmitNode[1]);
    private final Map<SingleQuadParticle.Layer, RenderType> particleLayers = new IdentityHashMap<>();
    private FeatureFrameContext frameContext;
    private FeatureRendererMap featureRenderers;
    private final FeatureRenderPhase.Output replay = (submit, strictlyOrdered) -> replay(submit);

    RemixCommandCapture(RemixBufferSource provider) {
        this.provider = provider;
    }

    SubmitNodeStorage queue() {
        return queue;
    }

    void emitHeldEffects(ItemCaptureState item, PoseStack matrices, float displayScale) {
        HeldItemEffectsRenderer.render(item.stack(), item.mode(), matrices, provider, displayScale);
    }

    /** Camera state for renderers that orient labels or billboards toward the viewer. */
    CameraRenderState cameraState() {
        Minecraft client = Minecraft.getInstance();
        Camera camera = client == null || client.gameRenderer == null ? null : client.gameRenderer.mainCamera();
        if (camera != null) {
            cameraState.pos = camera.position();
            cameraState.blockPos = camera.blockPosition();
            cameraState.orientation.set(camera.rotation());
            cameraState.xRot = camera.xRot();
            cameraState.yRot = camera.yRot();
            cameraState.initialized = true;
        }
        return cameraState;
    }

    /** Replays every queued command into the capture provider and clears the queue. */
    void flush() {
        prepareContext();
        for (SubmitNodeCollection collection : queue.getSubmitsPerOrder().values()) {
            for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                if (phase.isEmpty()) {
                    continue;
                }
                boolean skip = phase == collection.outline;
                phase.sortInto(skip ? DISCARD : replay);
            }
        }
    }

    /** Drops queued commands without replaying them, after a renderer threw. */
    void discard() {
        for (SubmitNodeCollection collection : queue.getSubmitsPerOrder().values()) {
            for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                if (!phase.isEmpty()) {
                    phase.sortInto(DISCARD);
                }
            }
        }
    }

    private void prepareContext() {
        Minecraft client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;
        var options = gameRenderer.gameRenderState().optionsRenderState;
        var models = client.getModelManager().getBlockStateModelSet();
        var lightmap = gameRenderer.lightmap();
        if (frameContext == null || frameContext.options() != options
                || frameContext.blockStateModelSet() != models || frameContext.lightmap() != lightmap) {
            frameContext = new FeatureFrameContext(options, client.font, models,
                    client.getBlockColors(), client.getTextureManager(), client.getAtlasManager(),
                    lightmap, gameRenderer.renderBuffers().stagedVertexBuffer());
        }
        featureRenderers = ((FeatureRenderDispatcherAccessor) gameRenderer.featureRenderDispatcher())
                .rtxremix$getFeatureRenderers();
    }

    private void replay(SubmitNode submit) {
        // Remix traces the entity's geometry; vanilla's blob-shadow decals would light it twice.
        if (submit instanceof ShadowFeatureRenderer.Submit) {
            return;
        }
        if (submit instanceof QuadParticleFeatureRenderer.Submit particles) {
            replayParticles(particles);
            return;
        }
        FeatureRenderer<?> renderer = featureRenderers.getOrThrow(submit.featureType());
        if (!(renderer instanceof RemixFeatureRenderer capture)) {
            throw new IllegalStateException("Feature has no CPU vertex output: " + submit.featureType());
        }
        singleSubmit.set(0, submit);
        try {
            RemixFeatureCapture.replay(capture, frameContext, singleSubmit, provider);
        } finally {
            singleSubmit.set(0, null);
        }
    }

    private void replayParticles(QuadParticleFeatureRenderer.Submit submit) {
        var particles = submit.particles();
        for (SingleQuadParticle.Layer layer : particles.layers()) {
            if (layer.translucent() != submit.translucent()) continue;
            RenderType renderType = particleLayers.computeIfAbsent(layer, key -> RenderType.create(
                    "rtxremix_particle_capture", RenderSetup.builder(key.pipeline())
                            .withTexture("Sampler0", key.textureAtlasLocation()).createRenderSetup()));
            // Particle vertices omit normals. The capture consumer finishes them at the
            // next position (or end of capture), deriving each quad's geometric normal.
            particles.buildLayer(layer, provider.getBuffer(renderType));
        }
    }

    @Override
    public void close() {
        discard();
        queue.getSubmitsPerOrder().clear();
        frameContext = null;
        featureRenderers = null;
        particleLayers.clear();
    }
}
