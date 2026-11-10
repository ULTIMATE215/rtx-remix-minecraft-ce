/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Copies CPU geometry before its mesh closes and defers only the weather draws. */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;uploadVertexBuffer(Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;"))
    private GpuBuffer rtxremix$captureWeather(WeatherEffectRenderer renderer, ByteBuffer vertices,
                                            Operation<GpuBuffer> original,
                                            @Local(argsOnly = true) WeatherRenderState state) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            RtxRemixMod.getInstance().getRenderer().getWeatherRenderer().capture(vertices,
                    state.rainColumns.size(), state.snowColumns.size(), Minecraft.getInstance().gameRenderer.lightmap());
        }
        return original.call(renderer, vertices);
    }

    @WrapOperation(method = "renderWeather", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V"))
    private void rtxremix$deferDraw(RenderPass pass, int indices, int instances, int firstIndex,
                                  int baseVertex, int firstInstance, Operation<Void> original) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            original.call(pass, indices, instances, firstIndex, baseVertex, firstInstance);
        }
    }
}
