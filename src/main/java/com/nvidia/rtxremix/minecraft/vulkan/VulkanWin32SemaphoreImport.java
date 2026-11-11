/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImportSemaphoreWin32HandleInfoKHR;

import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

/** Imports a borrowed Remix handle permanently into a newly created timeline semaphore. */
final class VulkanWin32SemaphoreImport {
    private VulkanWin32SemaphoreImport() {}

    static int importTimeline(long device, long semaphore, long sharedHandle, long importFunction) {
        if (device == 0 || semaphore == 0) {
            throw new IllegalArgumentException("Vulkan device and semaphore handles must be valid");
        }
        if (sharedHandle == 0 || sharedHandle == -1L) {
            throw new IllegalArgumentException("Remix shared semaphore handles must be valid");
        }
        if (importFunction == 0) {
            throw new IllegalStateException("Vulkan Win32 semaphore import function is unavailable");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkImportSemaphoreWin32HandleInfoKHR.calloc(stack).sType$Default()
                    .semaphore(semaphore).handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT)
                    .handle(sharedHandle);
            // calloc leaves flags zero (permanent import) and name null, as required
            // by VUID-VkImportSemaphoreWin32HandleInfoKHR-handle-01469. LWJGL 3.4.1's
            // generated validator incorrectly requires both handle and name. Use its
            // native dispatch directly for this locally validated, handle-only import.
            return JNI.callPPI(device, info.address(), importFunction);
        }
    }
}
