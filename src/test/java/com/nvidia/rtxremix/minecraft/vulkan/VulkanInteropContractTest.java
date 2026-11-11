/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.VulkanImageDescription;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK12.*;

class VulkanInteropContractTest {
    @Test
    void importsOnlyTheObservedAllocationTypeAndSize() throws Exception {
        VulkanImageDescription image = decode(exportedImage());
        assertDoesNotThrow(() -> VulkanInteropImage.validate(image, 4096, 16, 16, VK_FORMAT_R8G8B8A8_UNORM, true));
        assertDoesNotThrow(() -> VulkanInteropImage.validateMemory(image, 1024, 1 << 4, true));
        assertThrows(IllegalStateException.class, () -> VulkanInteropImage.validateMemory(image, 8192, 1 << 4, false));
        assertThrows(IllegalStateException.class, () -> VulkanInteropImage.validateMemory(image, 1024, 1 << 3, false));
        assertThrows(IllegalStateException.class, () -> VulkanInteropImage.validate(image, 2048, 16, 16,
                VK_FORMAT_R8G8B8A8_UNORM, true));
        long[] nondedicated = exportedImage();
        nondedicated[6] = 0;
        VulkanImageDescription other = decode(nondedicated);
        assertThrows(IllegalStateException.class, () -> VulkanInteropImage.validateMemory(other, 1024, 1 << 4, true));
    }

    @Test
    void refusesAnUnreleasedOrUnsupportedImageInsteadOfGuessingItsContract() throws Exception {
        // Field/value pairs for a different size, offset, shape, sharing mode,
        // external handle type, ownership family, layout, or allocation feature.
        long[][] invalid = {{11, 32}, {7, 256}, {13, 2}, {14, 2}, {15, 2}, {16, 4},
                {19, VK_SHARING_MODE_CONCURRENT}, {21, 0}, {25, VK_QUEUE_FAMILY_IGNORED},
                {25, org.lwjgl.vulkan.EXTQueueFamilyForeign.VK_QUEUE_FAMILY_FOREIGN_EXT},
                {26, VK_IMAGE_LAYOUT_UNDEFINED}, {4, VK_MEMORY_ALLOCATE_DEVICE_MASK_BIT}};
        for (long[] change : invalid) {
            long[] words = exportedImage();
            words[(int) change[0]] = change[1];
            VulkanImageDescription image = decode(words);
            assertThrows(IllegalStateException.class, () -> VulkanInteropImage.validate(image, 4096,
                    16, 16, VK_FORMAT_R8G8B8A8_UNORM, true), "invalid export field " + change[0]);
        }
    }

    @Test
    void depthNeedsReadAccessAndColorAlsoNeedsWriteAccess() throws Exception {
        long[] words = exportedImage();
        words[18] = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        VulkanImageDescription image = decode(words);
        assertDoesNotThrow(() -> VulkanInteropImage.validate(image, 4096, 16, 16, VK_FORMAT_R8G8B8A8_UNORM, false));
        assertThrows(IllegalStateException.class, () -> VulkanInteropImage.validate(image, 4096, 16, 16,
                VK_FORMAT_R8G8B8A8_UNORM, true));
    }

    @Test
    void remixFormatCodesAreTranslatedToVulkanFormats() {
        assertEquals(VK_FORMAT_R8G8B8A8_UNORM, VulkanInteropImage.vkFormat(TextureFormat.RGBA8_UNORM));
        assertEquals(VK_FORMAT_B8G8R8A8_UNORM, VulkanInteropImage.vkFormat(TextureFormat.BGRA8_UNORM));
        assertEquals(VK_FORMAT_R32_SFLOAT, VulkanInteropImage.vkFormat(TextureFormat.R32_SFLOAT));
        assertThrows(IllegalArgumentException.class, () -> VulkanInteropImage.vkFormat(-1));
    }

    private static long[] exportedImage() {
        return new long[]{1, 7, 4096, 4, 0, 0, 1, 0, 0, VK_IMAGE_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM,
                16, 16, 1, 1, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_SHARING_MODE_EXCLUSIVE, VK_IMAGE_LAYOUT_UNDEFINED, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT,
                0, 0, 0, VK_QUEUE_FAMILY_EXTERNAL, VK_IMAGE_LAYOUT_GENERAL, 10, 11, 12, 13, 0, 0};
    }

    private static VulkanImageDescription decode(long[] words) throws Exception {
        // The facade normally obtains this wire record exclusively from JNI.
        Constructor<VulkanImageDescription> constructor = VulkanImageDescription.class.getDeclaredConstructor(long[].class);
        constructor.setAccessible(true);
        return constructor.newInstance((Object) words);
    }
}
