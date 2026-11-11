/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/** Dependencies between our compute/transfer commands and Minecraft's earlier or later work. */
public final class VulkanSync {
    private VulkanSync() {}

    public static void barrier(VkCommandBuffer commands) {
        barrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    }

    public static void barrier(VkCommandBuffer commands, int fromStage, int fromAccess, int toStage, int toAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(fromAccess).dstAccessMask(toAccess);
            vkCmdPipelineBarrier(commands, fromStage, toStage, 0, barrier, null, null);
        }
    }

    public static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan failed to " + operation + " (" + result + ")");
    }
}
