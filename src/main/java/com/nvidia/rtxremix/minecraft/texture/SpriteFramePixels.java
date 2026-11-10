/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.nio.ByteBuffer;

/** CPU sampling of the mip-zero RGBA frames used by vanilla's animation shader. */
final class SpriteFramePixels {
    private SpriteFramePixels() {}

    static float progress(int subFrame, int duration) {
        // animate_sprite.vsh receives an integer thousandth through gl_VertexID.
        return (int) ((float) subFrame / duration * 1000.0f) / 1000.0f;
    }

    static void blend(ByteBuffer source, int imageWidth, int width, int height,
                      int x, int y, int nextX, int nextY, float amount, ByteBuffer destination) {
        int base = source.position();
        int output = destination.position();
        for (int row = 0; row < height; row++) {
            int first = base + ((y + row) * imageWidth + x) * 4;
            int second = base + ((nextY + row) * imageWidth + nextX) * 4;
            for (int byteIndex = 0; byteIndex < width * 4; byteIndex++) {
                int a = source.get(first + byteIndex) & 255;
                int b = source.get(second + byteIndex) & 255;
                destination.put(output++, (byte) Math.round(a + (b - a) * amount));
            }
        }
    }
}
