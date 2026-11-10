/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.RemixFeatureCapture;
import com.nvidia.rtxremix.minecraft.entity.RemixFeatureRenderer;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Retains vanilla's feature tessellation while supplying Remix's vertex consumers. */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin implements RemixFeatureRenderer {
    @Override
    @Invoker("buildGroup")
    public abstract void rtxremix$buildGroup(FeatureFrameContext context, List<SubmitNode> submits);

    @Inject(method = "getVertexBuilder", at = @At("HEAD"), cancellable = true)
    private void rtxremix$captureVertices(RenderType layer, CallbackInfoReturnable<VertexConsumer> cir) {
        VertexConsumer buffer = RemixFeatureCapture.buffer(layer);
        if (buffer != null) {
            cir.setReturnValue(buffer);
        }
    }
}
