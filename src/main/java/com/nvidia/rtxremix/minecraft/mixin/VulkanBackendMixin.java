/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @Shadow @Final @Mutable public static Set<String> REQUIRED_DEVICE_EXTENSIONS;
    @Shadow @Final @Mutable public static Set<VulkanFeature> REQUIRED_DEVICE_FEATURES;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void rtxremix$requireInteropAndCompute(CallbackInfo callback) {
        Set<String> extensions = new HashSet<>(REQUIRED_DEVICE_EXTENSIONS);
        extensions.add(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
        extensions.add(VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
        REQUIRED_DEVICE_EXTENSIONS = Set.copyOf(extensions);
        Set<VulkanFeature> features = new HashSet<>(REQUIRED_DEVICE_FEATURES);
        features.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageExtendedFormats",
                VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));
        REQUIRED_DEVICE_FEATURES = Set.copyOf(features);
    }
}
