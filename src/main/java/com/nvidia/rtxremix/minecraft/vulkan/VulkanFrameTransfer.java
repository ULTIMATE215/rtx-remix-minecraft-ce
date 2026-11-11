/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

/** Converts framebuffer row order while preserving the color encoding of Minecraft's UNORM target. */
final class VulkanFrameTransfer implements Destroyable {
    private final int width;
    private final int height;
    private final VulkanImage encodedScratch;

    VulkanFrameTransfer(VulkanWork work, int width, int height, int sharedFormat) {
        this.width = width;
        this.height = height;
        int encodedFormat = switch (sharedFormat) {
            case VK_FORMAT_R8G8B8A8_SRGB -> VK_FORMAT_R8G8B8A8_UNORM;
            case VK_FORMAT_B8G8R8A8_SRGB -> VK_FORMAT_B8G8R8A8_UNORM;
            default -> VK_FORMAT_UNDEFINED;
        };
        // Blitting an sRGB image decodes/encodes its pixels. Minecraft composites
        // the HUD into an UNORM target containing display-encoded color, so copy
        // the sRGB bits to a compatible UNORM image before flipping/swizzling.
        encodedScratch = encodedFormat == VK_FORMAT_UNDEFINED ? null : new VulkanImage(work,
                width, height, 1, encodedFormat,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
    }

    /** Caller owns external-image acquisition and barriers around the transfer. */
    void fromShared(VkCommandBuffer commands, long shared, long destination, int destinationMip,
                    MemoryStack stack) {
        long source = shared;
        if (encodedScratch != null) {
            encodedScratch.initialize(commands);
            copyEncoded(commands, shared, encodedScratch.image(), stack);
            VulkanSync.barrier(commands);
            source = encodedScratch.image();
        }
        VulkanTextureBlit.record(commands, source, 0, width, height,
                destination, destinationMip, width, height, true, VK_FILTER_NEAREST, stack);
    }

    /** Caller releases the external image only after the transfer completes. */
    void toShared(VkCommandBuffer commands, long source, int sourceMip, int sourceWidth, int sourceHeight,
                  long shared, MemoryStack stack) {
        if (encodedScratch != null) encodedScratch.initialize(commands);
        long destination = encodedScratch == null ? shared : encodedScratch.image();
        VulkanTextureBlit.record(commands, source, sourceMip, sourceWidth, sourceHeight,
                destination, 0, width, height, true, VK_FILTER_NEAREST, stack);
        if (encodedScratch != null) {
            VulkanSync.barrier(commands);
            copyEncoded(commands, encodedScratch.image(), shared, stack);
        }
    }

    private void copyEncoded(VkCommandBuffer commands, long source, long destination, MemoryStack stack) {
        var copy = VkImageCopy.calloc(1, stack);
        copy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.extent().set(width, height, 1);
        vkCmdCopyImage(commands, source, VK_IMAGE_LAYOUT_GENERAL,
                destination, VK_IMAGE_LAYOUT_GENERAL, copy);
    }

    @Override
    public void destroy() {
        if (encodedScratch != null) encodedScratch.destroy();
    }
}
