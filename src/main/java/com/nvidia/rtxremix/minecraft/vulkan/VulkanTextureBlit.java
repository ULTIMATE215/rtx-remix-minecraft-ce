/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import static org.lwjgl.vulkan.VK10.*;

/** Copies between GENERAL-layout color images on Minecraft's command encoder. */
public final class VulkanTextureBlit {
    private VulkanTextureBlit() {}

    public static void copy(VulkanCommandEncoder encoder, GpuTextureView source, GpuTextureView destination) {
        VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commands, stack);
            record(commands, image(source), source.baseMipLevel(), source.getWidth(0), source.getHeight(0),
                    image(destination), destination.baseMipLevel(), destination.getWidth(0), destination.getHeight(0),
                    false, VK_FILTER_NEAREST, stack);
            VulkanCommandEncoder.memoryBarrier(commands, stack);
        }
        VulkanInterop.check(vkEndCommandBuffer(commands), "finish image copy");
        encoder.execute(commands);
    }

    public static long image(GpuTextureView view) {
        if (!(view.texture() instanceof VulkanGpuTexture texture) || texture.isClosed() || view.isClosed()) {
            throw new IllegalArgumentException("A live Minecraft Vulkan texture is required");
        }
        return texture.vkImage();
    }

    /** Records a color transfer; callers own layout transitions and memory dependencies. */
    public static void record(VkCommandBuffer commands, long source, int sourceMip, int sourceWidth, int sourceHeight,
                       long destination, int destinationMip, int destinationWidth, int destinationHeight,
                       boolean flipY, int filter, MemoryStack stack) {
        var blit = VkImageBlit.calloc(1, stack);
        blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(sourceMip).layerCount(1);
        blit.srcOffsets(0).set(0, 0, 0);
        blit.srcOffsets(1).set(sourceWidth, sourceHeight, 1);
        blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(destinationMip).layerCount(1);
        blit.dstOffsets(0).set(0, flipY ? destinationHeight : 0, 0);
        blit.dstOffsets(1).set(destinationWidth, flipY ? 0 : destinationHeight, 1);
        vkCmdBlitImage(commands, source, VK_IMAGE_LAYOUT_GENERAL, destination, VK_IMAGE_LAYOUT_GENERAL, blit, filter);
    }
}
