/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import com.nvidia.rtxremix.InstanceCategoryBit;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Captures the block-breaking crack where vanilla draws it and submits it through the
 * entity submitter as a decal over the terrain.
 *
 * <p>{@code WorldRendererMixin} intercepts {@code LevelRenderer.submitBlockDestroyAnimation}
 * and routes its extracted state to {@code RemixRenderer.renderBlockDamageInPlace}, which
 * calls {@link #captureModel}. Fabric's model emitter renders onto this pass's identity
 * {@link RecyclingMatrixStack} rather than vanilla's camera-relative one, so the captured
 * quads are block-local and the crack projection runs in block space: one pooled mesh per
 * block shape and stage, and an instance root that is a translation by the block position.
 *
 * <p>Vanilla extracts a block position each frame, and the submitter's retained registry
 * keys parts by owner identity, so each broken position is
 * lent one owner token for as long as it keeps being rendered. A position vanilla stops
 * rendering, because the block broke, the player let go, or it left the 32-block range,
 * loses its token at {@link #finishFrame}, and the submitter's {@code endFrame} retires
 * its parts the same frame. The pass holds no native state of its own.
 */
public final class RemixBlockDamagePass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixBlockDamagePass.class);

    private static final String TYPE_KEY = "minecraft:block_breaking";
    private static final CrumblingOverlay[] LOCAL_OVERLAYS = new CrumblingOverlay[10];
    static {
        PoseStack.Pose identity = new PoseStack().last();
        for (int i = 0; i < LOCAL_OVERLAYS.length; i++) LOCAL_OVERLAYS[i] = new CrumblingOverlay(i, identity);
    }

    /** Block-entity capture has a block-local root, so its decal projection has the same root. */
    public static CrumblingOverlay localOverlay(int stage) { return LOCAL_OVERLAYS[stage]; }

    /**
     * The crack is coplanar with the terrain face it is drawn on, which is the case
     * terrain's own overlay category already solves with {@code DECAL_STATIC}: the
     * runtime offsets decal quads off the surface beneath and blends them over it. The
     * runtime handles {@code DECAL_DYNAMIC} identically and has deprecated its option,
     * so the static bit is used for both.
     */
    static final RemixEntitySubmitter.PartCategoryPolicy TERRAIN_DECAL =
            role -> InstanceCategoryBit.DECAL_STATIC;

    /**
     * Vanilla's damage render with the matrix stack and consumer left open, so the pass
     * can supply its own. The mixin's {@code Operation} handle stays in the mixin.
     */
    @FunctionalInterface
    public interface BlockDamageRenderCall {
        void render(PoseStack matrices, VertexConsumer consumer);
    }

    private final RecyclingMatrixStack matrices = new RecyclingMatrixStack();
    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final CrackProjector projector = new CrackProjector();
    private final PositionOwners owners = new PositionOwners();
    private final FailureLogThrottle failures = new FailureLogThrottle(LOGGER, "Block damage");
    private float[] reusableBoneData = new float[0];
    private QuadEmitter emitter;
    private VertexConsumer modelConsumer;
    private PoseStack modelMatrices;
    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(0);

    /** Uses Fabric's model output for vanilla models and resource-pack/custom geometry alike. */
    public void captureModel(BlockBreakingRenderState state, BlockStateModelSet models, RemixEntitySubmitter submitter) {
        BlockStateModel model = models.get(state.blockState());
        capture(state.blockPos(), state.progress(), (matrices, consumer) -> {
            matrices.translate(state.blockState().getOffset(state.blockPos()));
            modelMatrices = matrices;
            modelConsumer = consumer;
            random.setSeed(state.blockState().getSeed(state.blockPos()));
            if (emitter == null) emitter = Renderer.get().quadEmitter(this::emitQuad);
            emitter.clear();
            try {
                ((FabricBlockStateModel) model).emitQuads(emitter, BlockAndTintGetter.EMPTY,
                        state.blockPos(), state.blockState(), random, face -> false);
            } catch (RuntimeException | Error e) {
                emitter = null;
                throw e;
            } finally {
                modelConsumer = null;
                modelMatrices = null;
            }
        }, submitter);
    }

    private void emitQuad(QuadView quad) {
        PoseStack.Pose pose = modelMatrices.last();
        var normal = quad.faceNormal();
        for (int v = 0; v < 4; v++) {
            modelConsumer.addVertex(pose, quad.x(v), quad.y(v), quad.z(v))
                    .setColor(-1).setUv(quad.u(v), quad.v(v))
                    .setNormal(pose, normal.x(), normal.y(), normal.z());
        }
    }

    /**
     * Renders one broken block's damage model through vanilla's renderer on the identity
     * stack and submits the capture inside the submitter's open frame.
     *
     * @param stage the destroy stage vanilla selected for the block, 0 to 9
     */
    public void capture(BlockPos pos, int stage, BlockDamageRenderCall call,
                        RemixEntitySubmitter submitter) {
        EntityRenderContext ctx = EntityRenderContext.get();
        provider.beginEntity();
        matrices.pushPose();
        ctx.begin(true);
        List<RemixVertexConsumerProvider.CapturedLayer> layers;
        try {
            VertexConsumer crack = provider.getBuffer(
                    ModelBakery.DESTROY_TYPES.get(stage));
            call.render(matrices, projector.target(crack));
            layers = provider.endEntity();
        } catch (Exception e) {
            failures.noteFailure("capturing", "block damage", pos, e);
            return;
        } finally {
            // Both restore even if the renderer threw: the model renderer translates the
            // root in place, and a capture left open would make the next one look nested.
            matrices.reset();
            ctx.reset();
        }
        if (layers.isEmpty()) {
            return;
        }

        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        RemixEntityPass.EntityCaptureResult result = new RemixEntityPass.EntityCaptureResult(
                owners.ownerFor(pos.asLong()),
                Vec3.atLowerCornerOf(pos),
                0.0f,
                layers,
                reusableBoneData,
                ctx.getBoneCount(),
                TYPE_KEY,
                false);
        try {
            submitter.submitEntity(result, TERRAIN_DECAL);
        } catch (Exception e) {
            failures.noteFailure("submitting", "block damage", pos, e);
        }
    }

    /**
     * Drops the owner tokens of positions not captured this frame. Called once per frame
     * from {@code renderAndSubmitFrame}; retiring their scene instances belongs to the
     * submitter's {@code endFrame}.
     */
    public void finishFrame() {
        owners.endFrame();
        failures.endFrame();
    }

    /**
     * One identity token per broken block position, kept while the position is rendered.
     * Tokens are handed out during a frame and the ones not handed out are dropped at
     * {@link #endFrame}, so a position rendered every frame keeps one token and a
     * position that stops being rendered starts over with a new one if it returns.
     */
    static final class PositionOwners {
        private static final class Owner {
            long lastSeenFrame;
        }

        private final Long2ObjectOpenHashMap<Owner> owners = new Long2ObjectOpenHashMap<>();
        private long frame;

        Object ownerFor(long packedPos) {
            Owner owner = owners.get(packedPos);
            if (owner == null) {
                owner = new Owner();
                owners.put(packedPos, owner);
            }
            owner.lastSeenFrame = frame;
            return owner;
        }

        void endFrame() {
            if (!owners.isEmpty()) {
                owners.values().removeIf(owner -> owner.lastSeenFrame != frame);
            }
            frame++;
        }

        int size() {
            return owners.size();
        }
    }

    /**
     * Projects the crack texture onto a block's faces the way vanilla's
     * {@code OverlayVertexConsumer} does for an identity pose: the block-local position,
     * rotated into the frame of the face its normal selects, is the texture coordinate,
     * and the model's own atlas coordinates and shaded colour are discarded.
     *
     * <p>Vanilla's consumer is not reused because it hands its delegate the normal before
     * the texture coordinate, and {@link RemixCapturingConsumer} closes a vertex at its
     * normal. This one delivers the coordinate first.
     */
    static final class CrackProjector implements VertexConsumer {
        private final Quaternionf[] faceRotations = new Quaternionf[Direction.values().length];
        private final Vector3f projected = new Vector3f();
        private VertexConsumer target;
        private float x, y, z;

        CrackProjector() {
            for (Direction face : Direction.values()) {
                faceRotations[face.ordinal()] = face.getRotation();
            }
        }

        /** Directs the next render's vertices at {@code target}. */
        CrackProjector target(VertexConsumer target) {
            this.target = target;
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            target.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            target.setColor(0xFFFFFFFF);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            target.setColor(0xFFFFFFFF);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            target.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            target.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            target.setLineWidth(width);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            Direction face = Direction.getApproximateNearest(x, y, z);
            projected.set(this.x, this.y, this.z)
                    .rotateY(Mth.PI)
                    .rotateX(-Mth.HALF_PI)
                    .rotate(faceRotations[face.ordinal()]);
            target.setUv(-projected.x, -projected.y);
            target.setNormal(x, y, z);
            return this;
        }
    }
}
