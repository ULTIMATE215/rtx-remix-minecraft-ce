/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.PendingUploadsAccess;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin implements PendingUploadsAccess {
    @Shadow @Final private Map<ChunkSectionLayer, SectionRenderDispatcher.SectionUberBuffers> chunkUberBuffers;
    @Shadow public abstract void lock();
    @Shadow public abstract void unlock();

    @Override
    public int rtxremix$pendingUploads() {
        lock();
        try {
            int count = 0;
            for (var buffers : chunkUberBuffers.values()) {
                count += ((PendingUploadsAccess) buffers.vertexBuffer()).rtxremix$pendingUploads();
                count += ((PendingUploadsAccess) buffers.indexBuffer()).rtxremix$pendingUploads();
            }
            return count;
        } finally {
            unlock();
        }
    }
}
