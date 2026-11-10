/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes the final texture copy to Remix while retaining vanilla's presentation state setup. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
    @Inject(method = "writeToTexture", at = @At("RETURN"))
    private void rtxremix$mirrorGlyphPixels(
            GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer,
            int x, int y, int width, int height, CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page != null && mipLevel == 0 && depthOrLayer == 0) {
            int channels = destination.getFormat().componentCount();
            page.blit(x, y, width, height, channels, source, width * channels);
        }
    }

    @Redirect(method = "presentTexture", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
    private void rtxremix$copyFinalTexture(
            DirectStateAccess dsa, int sourceFbo, int destinationFbo,
            int srcX0, int srcY0, int srcX1, int srcY1,
            int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter,
            GpuTextureView source, int surfaceWidth, int surfaceHeight) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.copyGlFrameToRemix(sourceFbo, source.getWidth(0), source.getHeight(0));
        } else {
            dsa.blitFrameBuffers(sourceFbo, destinationFbo,
                    srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        }
    }
}
