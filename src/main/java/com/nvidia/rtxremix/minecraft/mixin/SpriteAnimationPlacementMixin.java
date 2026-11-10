/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.nvidia.rtxremix.minecraft.texture.SpriteAnimationAccess;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureAtlasSprite.class)
public abstract class SpriteAnimationPlacementMixin {
    @Shadow @Final private int x;
    @Shadow @Final private int y;
    @Shadow @Final private int padding;

    @Inject(method = "createAnimationState", at = @At("RETURN"))
    private void rtxremix$bindAnimation(GpuBufferSlice ubo, int uboSize,
                                      CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
        TextureAtlasSprite sprite = (TextureAtlasSprite) (Object) this;
        if (cir.getReturnValue() != null) {
            ((SpriteAnimationAccess) cir.getReturnValue()).rtxremix$bindSprite(sprite, x + padding, y + padding);
        }
    }
}
