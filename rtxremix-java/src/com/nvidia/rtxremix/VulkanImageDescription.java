/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.util.Arrays;

/**
 * Original Vulkan creation and allocation parameters observed by the bridge's
 * export layer. Handles and allocations remain owned by the exporting runtime.
 * Vulkan enum values are preserved without introducing a graphics dependency
 * into the JNI facade.
 */
public final class VulkanImageDescription {
    private static final int PREFIX_WORDS = 33;

    public final long generation;
    public final long allocationSize;
    public final int memoryTypeIndex;
    public final int allocationFlags;
    public final int deviceMask;
    public final boolean dedicated;
    public final long memoryOffset;
    public final int imageFlags;
    public final int imageType;
    public final int format;
    public final int width;
    public final int height;
    public final int depth;
    public final int mipLevels;
    public final int arrayLayers;
    public final int samples;
    public final int tiling;
    public final int usage;
    public final int sharingMode;
    public final int initialLayout;
    public final int externalHandleTypes;
    public final boolean hasFormatList;
    public final boolean hasStencilUsage;
    public final int stencilUsage;
    public final int externalQueueFamily;
    public final int externalLayout;
    public final long deviceUuidLow;
    public final long deviceUuidHigh;
    public final long driverUuidLow;
    public final long driverUuidHigh;
    public final int[] queueFamilies;
    public final int[] viewFormats;

    VulkanImageDescription(long[] words) {
        if (words == null || words.length < PREFIX_WORDS || words[0] != 1) {
            throw new IllegalArgumentException("Invalid Vulkan export description version or length");
        }
        long familyCount = words[31];
        long formatCount = words[32];
        if (familyCount < 0 || formatCount < 0 || familyCount > words.length || formatCount > words.length
                || PREFIX_WORDS + familyCount + formatCount != words.length) {
            throw new IllegalArgumentException("Invalid Vulkan export description arrays");
        }
        generation = words[1];
        allocationSize = words[2];
        memoryTypeIndex = (int) words[3];
        allocationFlags = (int) words[4];
        deviceMask = (int) words[5];
        dedicated = words[6] != 0;
        memoryOffset = words[7];
        imageFlags = (int) words[8];
        imageType = (int) words[9];
        format = (int) words[10];
        width = (int) words[11];
        height = (int) words[12];
        depth = (int) words[13];
        mipLevels = (int) words[14];
        arrayLayers = (int) words[15];
        samples = (int) words[16];
        tiling = (int) words[17];
        usage = (int) words[18];
        sharingMode = (int) words[19];
        initialLayout = (int) words[20];
        externalHandleTypes = (int) words[21];
        hasFormatList = words[22] != 0;
        hasStencilUsage = words[23] != 0;
        stencilUsage = (int) words[24];
        externalQueueFamily = (int) words[25];
        externalLayout = (int) words[26];
        deviceUuidLow = words[27];
        deviceUuidHigh = words[28];
        driverUuidLow = words[29];
        driverUuidHigh = words[30];
        queueFamilies = Arrays.stream(words, PREFIX_WORDS, PREFIX_WORDS + (int) familyCount)
                .mapToInt(value -> (int) value).toArray();
        viewFormats = Arrays.stream(words, PREFIX_WORDS + (int) familyCount, words.length)
                .mapToInt(value -> (int) value).toArray();
    }
}
