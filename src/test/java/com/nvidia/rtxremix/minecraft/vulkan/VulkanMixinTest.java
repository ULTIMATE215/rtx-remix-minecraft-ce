/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.nvidia.rtxremix.minecraft.mixin.GpuSurfaceAccessor;
import com.nvidia.rtxremix.minecraft.mixin.VulkanCommandEncoderAccessor;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;

/** Applies the actual mixins to the pinned client classes without creating a window or GPU device. */
@Tag("fabric")
class VulkanMixinTest {
    @Test
    void appliesRequiredDeviceFeaturesAndTheFrameAndUploadHooks() {
        assertTrue(VulkanBackend.REQUIRED_DEVICE_EXTENSIONS.contains(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME));
        assertTrue(VulkanBackend.REQUIRED_DEVICE_EXTENSIONS.contains(VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME));
        assertTrue(GpuSurfaceAccessor.class.isAssignableFrom(GpuSurface.class));
        assertTrue(VulkanCommandEncoderAccessor.class.isAssignableFrom(VulkanCommandEncoder.class));
        assertHasHandler(Minecraft.class, "rtxremix$useVulkan");
        assertHasHandler(VulkanDevice.class, "rtxremix$wrapSurface");
        assertHasHandler(CommandEncoder.class, "rtxremix$mirrorGlyphUpload");
        assertDoesNotThrow(RemixWeatherRenderer::registerShaders);
    }

    private static void assertHasHandler(Class<?> target, String name) {
        assertTrue(Arrays.stream(target.getDeclaredMethods()).anyMatch(method -> method.getName().contains(name)),
                target.getName() + " did not receive " + name);
    }
}
