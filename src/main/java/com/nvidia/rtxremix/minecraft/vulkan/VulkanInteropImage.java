/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.VulkanImageDescription;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK12.*;

/** An import of the actual exported image and allocation; Remix retains the Win32 HANDLE. */
final class VulkanInteropImage implements Destroyable {
    private final VkDevice device;
    private final long sharedHandle;
    private final long allocationSize;
    private final long generation;
    private final int remixFormat;
    final int width;
    final int height;
    final int externalFamily;
    private long image;
    private long memory;

    VulkanInteropImage(VkDevice device, long sharedHandle, long allocationSize,
                       int width, int height, int remixFormat, boolean writable) {
        if (sharedHandle == 0 || sharedHandle == -1L || allocationSize <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid Remix shared image");
        }
        this.device = device;
        this.sharedHandle = sharedHandle;
        this.allocationSize = allocationSize;
        this.width = width;
        this.height = height;
        this.remixFormat = remixFormat;
        VulkanImageDescription description = RemixAPI.describeVulkanImage(sharedHandle, width, height, vkFormat(remixFormat));
        validate(description, allocationSize, width, height, vkFormat(remixFormat), writable);
        VulkanDeviceIdentity.requireMatch(device, description.deviceUuidLow, description.deviceUuidHigh,
                description.driverUuidLow, description.driverUuidHigh);
        generation = description.generation;
        externalFamily = description.externalQueueFamily;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var create = VkImageCreateInfo.calloc(stack).sType$Default()
                    .flags(description.imageFlags).imageType(description.imageType).format(description.format)
                    .mipLevels(description.mipLevels).arrayLayers(description.arrayLayers).samples(description.samples)
                    .tiling(description.tiling).usage(description.usage).sharingMode(description.sharingMode)
                    .initialLayout(description.initialLayout);
            create.extent().set(description.width, description.height, description.depth);
            create.pNext(VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(description.externalHandleTypes));
            if (description.hasFormatList) {
                var formats = VkImageFormatListCreateInfo.calloc(stack).sType$Default();
                if (description.viewFormats.length != 0) formats.pViewFormats(stack.ints(description.viewFormats));
                create.pNext(formats);
            }
            if (description.hasStencilUsage) {
                create.pNext(VkImageStencilUsageCreateInfo.calloc(stack).sType$Default()
                        .stencilUsage(description.stencilUsage));
            }
            var handle = stack.mallocLong(1);
            VulkanInterop.check(vkCreateImage(device, create, null, handle), "create shared image");
            image = handle.get(0);

            var dedicated = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
            var requirements = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicated);
            vkGetImageMemoryRequirements2(device,
                    VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(image), requirements);
            validateMemory(description, requirements.memoryRequirements().size(),
                    requirements.memoryRequirements().memoryTypeBits(), dedicated.requiresDedicatedAllocation());
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    // OPAQUE_WIN32 requires the exporter's exact size AND memory type (01743).
                    .allocationSize(description.allocationSize).memoryTypeIndex(description.memoryTypeIndex)
                    .pNext(VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default()
                            .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT).handle(sharedHandle));
            if (description.dedicated) {
                allocation.pNext(VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image));
            }
            if (description.allocationFlags != 0) {
                allocation.pNext(VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default()
                        .flags(description.allocationFlags).deviceMask(description.deviceMask));
            }
            VulkanInterop.check(vkAllocateMemory(device, allocation, null, handle), "import Remix image memory");
            memory = handle.get(0);
            VulkanInterop.check(vkBindImageMemory(device, image, memory, description.memoryOffset), "bind shared image");
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    static void validate(VulkanImageDescription description, long bytes, int width, int height,
                         int format, boolean writable) {
        if (description.allocationSize != bytes || description.width != width || description.height != height
                || description.format != format || description.generation <= 0) {
            throw new IllegalStateException("Remix's image description differs from its Vulkan export");
        }
        if (description.imageType != VK_IMAGE_TYPE_2D || description.depth != 1 || description.mipLevels != 1
                || description.arrayLayers != 1 || description.samples != VK_SAMPLE_COUNT_1_BIT
                || description.sharingMode != VK_SHARING_MODE_EXCLUSIVE || description.memoryOffset != 0) {
            throw new IllegalStateException("Unsupported Remix shared image shape, sharing mode or memory offset");
        }
        if ((description.externalHandleTypes & VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT) == 0
                || description.externalLayout != VK_IMAGE_LAYOUT_GENERAL
                || description.externalQueueFamily != VK_QUEUE_FAMILY_EXTERNAL) {
            throw new IllegalStateException("Remix did not export and release the Vulkan image for external access");
        }
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | (writable ? VK_IMAGE_USAGE_TRANSFER_DST_BIT : 0);
        if ((description.usage & usage) != usage) {
            throw new IllegalStateException("Remix's exported image does not support the required transfers");
        }
        if ((description.allocationFlags & ~VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT) != 0) {
            throw new IllegalStateException("Unsupported Remix device-group or capture-replay allocation flags");
        }
    }

    static void validateMemory(VulkanImageDescription description, long requiredBytes,
                               int allowedTypes, boolean requiresDedicated) {
        if (requiredBytes > description.allocationSize || description.memoryTypeIndex < 0
                || description.memoryTypeIndex >= 32 || (allowedTypes & (1 << description.memoryTypeIndex)) == 0
                || (requiresDedicated && !description.dedicated)) {
            throw new IllegalStateException("The exported Vulkan allocation is incompatible with the imported image");
        }
    }

    boolean matches(long handle, long bytes, int width, int height, int format) {
        return sharedHandle == handle && allocationSize == bytes && this.width == width && this.height == height
                && remixFormat == format
                && generation == RemixAPI.vulkanImageGeneration(handle, width, height, vkFormat(format));
    }

    long image() { return image; }

    static int vkFormat(int format) {
        return switch (format) {
            case TextureFormat.RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case TextureFormat.RGBA8_SRGB -> VK_FORMAT_R8G8B8A8_SRGB;
            case TextureFormat.BGRA8_UNORM -> VK_FORMAT_B8G8R8A8_UNORM;
            case TextureFormat.BGRA8_SRGB -> VK_FORMAT_B8G8R8A8_SRGB;
            case TextureFormat.RGBA16_SFLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case TextureFormat.RGBA32_SFLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case TextureFormat.R32_SFLOAT -> VK_FORMAT_R32_SFLOAT;
            default -> throw new IllegalArgumentException("Unsupported Remix shared image format: " + format);
        };
    }

    @Override
    public void destroy() {
        if (image != 0) vkDestroyImage(device, image, null);
        if (memory != 0) vkFreeMemory(device, memory, null);
        image = memory = 0;
    }
}
