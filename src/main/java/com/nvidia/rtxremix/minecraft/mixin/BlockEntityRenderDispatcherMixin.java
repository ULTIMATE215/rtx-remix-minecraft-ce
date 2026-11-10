/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockDamagePass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Captures block entities where vanilla renders them, instead of rendering them twice.
 *
 * <p>Vanilla evaluates the block-entity renderer here and submits its commands to the
 * world's queue. Each dispatch is offered to {@code RemixRenderer.renderBlockEntityInPlace},
 * which runs the same renderer against the Remix capture stack and a private queue and
 * submits the result; the world-queue submit never happens. Vanilla's own matrix stack and
 * queue are deliberately not forwarded: the stack is camera-relative, and the capture
 * supplies an identity one so the geometry stays block-local. When Remix declines,
 * vanilla renders as usual.
 *
 * <p>Since 1.21.9 the manager renders a {@link BlockEntityRenderState}; the block entity
 * itself is looked up from the client world at the state's position.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @WrapMethod(
        method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
    )
    private <S extends BlockEntityRenderState> void rtxremix$captureBlockEntityInPlace(
            S state, PoseStack matrices, SubmitNodeCollector queue,
            CameraRenderState cameraState, Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        Minecraft client = Minecraft.getInstance();
        BlockEntity be = renderer == null || client.level == null
                ? null
                : client.level.getBlockEntity(state.blockPos);
        boolean captured = be != null
                && be.hasLevel()
                && renderer.renderBlockEntityInPlace(be,
                        (captureMatrices, captureQueue) -> {
                            var previous = state.breakProgress;
                            if (previous != null) state.breakProgress = RemixBlockDamagePass.localOverlay(previous.progress());
                            try {
                                original.call(state, captureMatrices, captureQueue, cameraState);
                            } finally {
                                state.breakProgress = previous;
                            }
                        });
        if (!captured) {
            original.call(state, matrices, queue, cameraState);
        }
    }
}
