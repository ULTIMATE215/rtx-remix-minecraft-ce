/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;

/** Retains the framegraph pass and its state transitions while Remix supplies terrain. */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @WrapOperation(method = "renderGroup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;drawMultipleIndexed(Ljava/util/Collection;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V"))
    private <T> void rtxremix$skipTerrainDraw(RenderPass pass, Collection<RenderPass.Draw<T>> draws,
                                           GpuBuffer indices, IndexType type, Collection<String> uniforms,
                                           T data, Operation<Void> original) {
        boolean skipped = RtxRemixMod.isRemixWorldRendering();
        ((WorldRendererProfilingAccess) Minecraft.getInstance().levelRenderer).rtxremix$recordTerrainDraw(skipped);
        if (!skipped) original.call(pass, draws, indices, type, uniforms, data);
    }
}
