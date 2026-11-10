/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.SpriteAnimationAccess;
import com.nvidia.rtxremix.minecraft.texture.SpriteAnimationCapture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.AnimationState.class)
public abstract class SpriteAnimationStateMixin implements SpriteAnimationAccess {
    @Shadow private int frame;
    @Shadow private int subFrame;
    @Shadow public abstract boolean needsToDraw();
    @Unique private SpriteAnimationCapture rtxremix$capture;

    @Override
    public void rtxremix$bindSprite(TextureAtlasSprite sprite, int x, int y) {
        if (rtxremix$capture != null) rtxremix$capture.close();
        rtxremix$capture = new SpriteAnimationCapture(sprite, x, y);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$queueFrame(CallbackInfo ci) {
        if (rtxremix$capture != null) rtxremix$capture.queue(frame, subFrame, needsToDraw());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rtxremix$releasePixels(CallbackInfo ci) {
        if (rtxremix$capture != null) {
            rtxremix$capture.close();
            rtxremix$capture = null;
        }
    }
}
