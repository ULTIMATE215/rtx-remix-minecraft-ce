/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/** Opaque external allocations may only be shared by matching devices and drivers. */
final class VulkanDeviceIdentity {
    private VulkanDeviceIdentity() {}

    static void requireMatch(VkDevice device, long deviceLow, long deviceHigh, long driverLow, long driverHigh) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var ids = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(ids);
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), properties);
            var deviceId = ids.deviceUUID().order(ByteOrder.nativeOrder());
            var driverId = ids.driverUUID().order(ByteOrder.nativeOrder());
            if (deviceId.getLong(0) != deviceLow || deviceId.getLong(8) != deviceHigh
                    || driverId.getLong(0) != driverLow || driverId.getLong(8) != driverHigh) {
                throw new IllegalStateException("Minecraft and RTX Remix selected different Vulkan devices or drivers; "
                        + "select the same NVIDIA GPU for both renderers");
            }
        }
    }
}
