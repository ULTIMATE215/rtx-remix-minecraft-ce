/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static com.nvidia.rtxremix.minecraft.vulkan.VulkanSync.check;

/** Owned 2D/3D compute image, kept in GENERAL layout between passes. */
public final class VulkanImage implements Destroyable {
    private final VulkanWork work;
    private final int width, height, depth;
    private long image, allocation, view, sampler;
    private boolean initialized;

    public VulkanImage(VulkanWork work, int width, int height, int depth, int format, int usage, int filter, int addressMode) {
        this.work = work;
        this.width = width;
        this.height = height;
        this.depth = depth;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var create = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(depth == 1 ? VK_IMAGE_TYPE_2D : VK_IMAGE_TYPE_3D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).usage(usage);
            create.extent().set(width, height, depth);
            var handle = stack.mallocLong(1);
            var memory = stack.mallocPointer(1);
            check(vmaCreateImage(work.allocator(), create, VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE), handle, memory, null), "allocate compute image");
            image = handle.get(0);
            allocation = memory.get(0);
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(depth == 1 ? VK_IMAGE_VIEW_TYPE_2D : VK_IMAGE_VIEW_TYPE_3D).format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(work.device(), viewInfo, null, handle), "create compute image view");
            view = handle.get(0);
            if ((usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0) {
                var samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter).minFilter(filter)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(addressMode)
                        .addressModeV(addressMode).addressModeW(addressMode);
                check(vkCreateSampler(work.device(), samplerInfo, null, handle), "create compute sampler");
                sampler = handle.get(0);
            }
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    public long image() { return image; }
    public long view() { return view; }
    public long sampler() { return sampler; }

    public void initialize(VkCommandBuffer commands) {
        if (initialized) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, barrier);
        }
        initialized = true;
    }

    public void copyTo(VkCommandBuffer commands, VulkanBuffer destination) {
        VulkanSync.barrier(commands);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdCopyImageToBuffer(commands, image, VK_IMAGE_LAYOUT_GENERAL, destination.buffer(), copyRegion(stack));
        }
        VulkanSync.barrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
    }

    public void upload(VkCommandBuffer commands, VulkanBuffer source) {
        initialize(commands);
        VulkanSync.barrier(commands);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdCopyBufferToImage(commands, source.buffer(), image, VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack));
        }
        VulkanSync.barrier(commands);
    }

    private VkBufferImageCopy.Buffer copyRegion(MemoryStack stack) {
        var copy = VkBufferImageCopy.calloc(1, stack);
        copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.imageExtent().set(width, height, depth);
        return copy;
    }

    @Override
    public void destroy() {
        if (sampler != 0) vkDestroySampler(work.device(), sampler, null);
        if (view != 0) vkDestroyImageView(work.device(), view, null);
        if (image != 0) vmaDestroyImage(work.allocator(), image, allocation);
        image = allocation = view = sampler = 0;
    }
}
