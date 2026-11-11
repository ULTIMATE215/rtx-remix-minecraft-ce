/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/** Vulkan semaphore type and exporting device identity, observed at creation/export. */
public final class VulkanSemaphoreDescription {
    public final int type;
    public final long deviceUuidLow;
    public final long deviceUuidHigh;
    public final long driverUuidLow;
    public final long driverUuidHigh;

    private VulkanSemaphoreDescription(int type, long deviceUuidLow, long deviceUuidHigh,
                                       long driverUuidLow, long driverUuidHigh) {
        this.type = type;
        this.deviceUuidLow = deviceUuidLow;
        this.deviceUuidHigh = deviceUuidHigh;
        this.driverUuidLow = driverUuidLow;
        this.driverUuidHigh = driverUuidHigh;
    }

    static VulkanSemaphoreDescription decode(long[] words) {
        if (words == null || words.length != 6 || words[0] != 1) {
            throw new IllegalArgumentException("Invalid Vulkan semaphore description");
        }
        return new VulkanSemaphoreDescription((int) words[1], words[2], words[3], words[4], words[5]);
    }
}
