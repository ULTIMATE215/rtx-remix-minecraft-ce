/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.nvidia.rtxremix.minecraft.profiling.PendingUploadsAccess;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(UberGpuBuffer.class)
public abstract class UberGpuBufferMixin implements PendingUploadsAccess {
    @Shadow @Final private Object2ObjectOpenHashMap<?, ?> stagedAllocations;

    @Override
    public int rtxremix$pendingUploads() {
        return stagedAllocations.size();
    }
}
