/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VulkanExportDescriptionTest {
    @Test
    void imageDecoderRejectsTruncatedAndUnknownRecords() {
        assertThrows(IllegalArgumentException.class, () -> new VulkanImageDescription(null));
        assertThrows(IllegalArgumentException.class, () -> new VulkanImageDescription(new long[32]));
        long[] words = new long[33];
        words[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> new VulkanImageDescription(words));
        words[0] = 1;
        words[31] = -1;
        assertThrows(IllegalArgumentException.class, () -> new VulkanImageDescription(words));
        words[31] = Long.MAX_VALUE;
        assertThrows(IllegalArgumentException.class, () -> new VulkanImageDescription(words));
        words[31] = 0;
        words[32] = 1;
        assertThrows(IllegalArgumentException.class, () -> new VulkanImageDescription(words));
    }

    @Test
    void copiesVariableLengthMetadataAndPreservesUnsignedFamilyAndUuidBits() {
        long[] words = new long[36];
        words[0] = 1;
        words[25] = 0xffff_fffeL;
        words[27] = Long.MIN_VALUE;
        words[31] = 1;
        words[32] = 2;
        words[33] = 3;
        words[34] = 37;
        words[35] = 43;
        VulkanImageDescription image = new VulkanImageDescription(words);
        assertEquals(-2, image.externalQueueFamily);
        assertEquals(Long.MIN_VALUE, image.deviceUuidLow);
        words[33] = 99;
        assertArrayEquals(new int[]{3}, image.queueFamilies);
        assertArrayEquals(new int[]{37, 43}, image.viewFormats);
    }

    @Test
    void semaphoreDecoderRequiresTheCompleteVersionedIdentity() {
        assertThrows(IllegalArgumentException.class, () -> VulkanSemaphoreDescription.decode(new long[]{1, 1}));
        assertThrows(IllegalArgumentException.class, () -> VulkanSemaphoreDescription.decode(new long[]{2, 1, 2, 3, 4, 5}));
        VulkanSemaphoreDescription semaphore = VulkanSemaphoreDescription.decode(new long[]{1, 1, 2, 3, 4, 5});
        assertEquals(1, semaphore.type);
        assertEquals(2, semaphore.deviceUuidLow);
        assertEquals(5, semaphore.driverUuidHigh);
    }
}
