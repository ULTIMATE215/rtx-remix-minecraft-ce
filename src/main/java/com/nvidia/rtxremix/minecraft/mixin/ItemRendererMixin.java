/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.ItemCaptureStateAccess;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries item identity and holder from extraction to command submission. The
 * item's render type alone cannot distinguish opaque items from glass blocks.
 */
@Mixin(ItemModelResolver.class)
public class ItemRendererMixin {
    @Inject(method = "updateForTopItem", at = @At("RETURN"))
    private void rtxremix$rememberItem(ItemStackRenderState state, ItemStack stack,
                                     ItemDisplayContext mode, Level level,
                                     ItemOwner owner, int seed, CallbackInfo ci) {
        ((ItemCaptureStateAccess) state).rtxremix$itemCaptureState().set(stack, mode, owner);
    }
}
