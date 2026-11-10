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
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeStorage;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Replays first-person commands while their hand is known, including bare arms and maps. */
@Mixin(ItemInHandRenderer.class)
public class HeldItemRendererMixin {
    @WrapMethod(method = "submitArmWithItem")
    private void rtxremix$captureHand(AbstractClientPlayer player, float tickDelta, float pitch,
                                     InteractionHand hand, float swingProgress, ItemStack item,
                                     float equipProgress, PoseStack matrices, SubmitNodeCollector queue,
                                     int light, Operation<Void> original) {
        if (!(queue instanceof RemixSubmitNodeStorage capture)) {
            original.call(player, tickDelta, pitch, hand, swingProgress, item,
                    equipProgress, matrices, queue, light);
            return;
        }
        capture.flush();
        EntityRenderContext context = EntityRenderContext.get();
        InteractionHand previousHand = context.getActiveHand();
        context.setActiveHand(hand);
        try {
            original.call(player, tickDelta, pitch, hand, swingProgress, item,
                    equipProgress, matrices, queue, light);
            capture.flush();
        } finally {
            context.setActiveHand(previousHand);
        }
    }
}
