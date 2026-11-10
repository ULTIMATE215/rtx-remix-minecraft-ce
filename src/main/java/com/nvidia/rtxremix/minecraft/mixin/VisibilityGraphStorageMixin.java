/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionOcclusionGraph.GraphStorage.class)
public abstract class VisibilityGraphStorageMixin implements VisibilityGraphAccess {
    @Unique private VisibilityGraphBuffers.Buffers rtxremix$buffers;

    @Redirect(method = "<init>", at = @At(value = "NEW",
            target = "(I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$SectionToNodeMap;"))
    private SectionOcclusionGraph.SectionToNodeMap rtxremix$nodeMap(int size, ViewArea viewArea) {
        rtxremix$buffers = VisibilityGraphBuffers.acquire(viewArea);
        return rtxremix$buffers.infos;
    }

    @Redirect(method = "<init>", at = @At(value = "NEW",
            target = "()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
    private Long2ObjectOpenHashMap<LongList> rtxremix$waitingSections() {
        return rtxremix$buffers.waitingSections;
    }

    @Override
    public VisibilityGraphBuffers.Buffers rtxremix$buffers() {
        return rtxremix$buffers;
    }
}
