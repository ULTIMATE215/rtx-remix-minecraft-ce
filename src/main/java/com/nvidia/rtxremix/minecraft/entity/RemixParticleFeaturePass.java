/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.List;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;

/** Captures pickup items and model particles on the render thread, where models may be mutated. */
public final class RemixParticleFeaturePass {
    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final RemixCommandCapture commands = new RemixCommandCapture(provider);
    private float[] bones = new float[0];

    public void capture(ParticleGroupRenderState group, CameraRenderState camera, RemixEntitySubmitter submitter) {
        provider.beginEntity();
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);
        List<RemixVertexConsumerProvider.CapturedLayer> layers;
        try {
            group.submit(commands.queue(), camera);
            commands.flush();
            layers = provider.endEntity();
        } catch (RuntimeException | Error e) {
            commands.discard();
            throw e;
        } finally {
            context.reset();
        }
        if (layers.isEmpty()) return;
        bones = context.snapshotBoneTransforms(bones);
        // Vanilla supplies one state per particle group. The class token keeps that
        // group's part ordinals stable even though its state is rebuilt each frame.
        submitter.submitEntity(new RemixEntityPass.EntityCaptureResult(group.getClass(), camera.pos,
                0, layers, bones, context.getBoneCount(), "minecraft:particle_feature", false));
    }

    public void clear() {
        commands.close();
        provider.clear();
    }
}
