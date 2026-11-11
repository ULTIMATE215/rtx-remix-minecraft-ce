/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.nvidia.rtxremix.minecraft.vulkan.RemixVulkanSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
    @Inject(method = "createSurface", at = @At("RETURN"), cancellable = true)
    private void rtxremix$wrapSurface(long window, CallbackInfoReturnable<GpuSurfaceBackend> callback) {
        callback.setReturnValue(new RemixVulkanSurface((VulkanDevice) (Object) this, callback.getReturnValue()));
    }
}
