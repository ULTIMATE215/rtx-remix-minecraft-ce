/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.client.Minecraft;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;

/** Queue and allocation ownership for compute work, including its completion and retirement. */
public interface VulkanWork {
    VkDevice device();
    long allocator();
    VkCommandBuffer begin();
    void execute(VkCommandBuffer commands);
    GpuFence fence();
    void retire(Destroyable resource);
    void submit();

    static VulkanWork minecraft() {
        RenderSystem.assertOnRenderThread();
        return new MinecraftWork(RemixVulkanSurface.from(Minecraft.getInstance().windowSurface()).device());
    }

    final class MinecraftWork implements VulkanWork {
        private final VulkanDevice device;
        private final VulkanCommandEncoder encoder;

        private MinecraftWork(VulkanDevice device) {
            this.device = device;
            encoder = device.createCommandEncoder();
        }

        @Override public VkDevice device() { return device.vkDevice(); }
        @Override public long allocator() { return device.vma(); }
        @Override public VkCommandBuffer begin() {
            RenderSystem.assertOnRenderThread();
            return encoder.allocateAndBeginTransientCommandBuffer();
        }
        @Override public void execute(VkCommandBuffer commands) {
            VulkanInterop.check(vkEndCommandBuffer(commands), "finish compute command buffer");
            encoder.execute(commands);
        }
        @Override public GpuFence fence() { return encoder.createFence(); }
        @Override public void retire(Destroyable resource) { encoder.queueForDestroy(resource); }
        @Override public void submit() { encoder.submit(); }
    }
}
