/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.ItemCaptureState;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeStorage;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState")
public abstract class ItemLayerRenderStateMixin {
    @Shadow private ItemTransform itemTransform;

    @Inject(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"))
    private void rtxremix$emitHeldEffects(PoseStack matrices, SubmitNodeCollector queue,
                                        int light, int overlay, int outline, CallbackInfo ci) {
        ItemCaptureState item = ItemCaptureState.current();
        if (queue instanceof RemixSubmitNodeStorage capture && item != null && item.claimEffects()) {
            capture.emitHeldEffects(item, matrices, itemTransform.scale().x());
        }
    }
}
