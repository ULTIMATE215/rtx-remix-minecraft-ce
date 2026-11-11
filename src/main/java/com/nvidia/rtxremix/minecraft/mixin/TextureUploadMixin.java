/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/** Captures font uploads before their CPU storage is released, for either glyph source type. */
@Mixin(CommandEncoder.class)
public abstract class TextureUploadMixin {
    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("RETURN"))
    private void rtxremix$mirrorGlyphUpload(GpuTexture texture, ByteBuffer pixels, int mipLevel,
                                          int layer, int x, int y, int width, int height, CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page != null && mipLevel == 0 && layer == 0) {
            int channels = texture.getFormat().componentCount();
            page.blit(x, y, width, height, channels, pixels, width * channels);
        }
    }
}
