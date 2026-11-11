/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.ARGB;

/**
 * Reads a {@link NativeImage} pixel in its native little-endian RGBA (big-endian ABGR) order.
 * Minecraft 1.21.11 made {@code NativeImage.getColor} private and only exposes the ARGB view.
 */
public final class NativeImagePixels {
    private NativeImagePixels() {}

    /** Returns the pixel as ABGR (red in the lowest byte), matching the 1.21.1 {@code getColor}. */
    public static int abgr(NativeImage image, int x, int y) {
        return ARGB.toABGR(image.getPixel(x, y));
    }
}
