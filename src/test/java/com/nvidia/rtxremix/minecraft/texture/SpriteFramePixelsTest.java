/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpriteFramePixelsTest {
    @Test
    void interpolationIncludesAlphaLikeTheVanillaShader() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 64, (byte) 128, (byte) 255,
                (byte) 255, (byte) 192, (byte) 128, 0});
        ByteBuffer output = ByteBuffer.allocate(4);
        SpriteFramePixels.blend(source, 2, 1, 1, 0, 0, 1, 0, 0.25f, output);
        assertArrayEquals(new byte[]{64, 96, (byte) 128, (byte) 191}, output.array());
        assertEquals(0, source.position());
        assertEquals(0, output.position());
    }

    @Test
    void frameRowsRespectSheetStrideAndBufferPositions() {
        ByteBuffer source = ByteBuffer.allocate(4 + 3 * 2 * 4);
        source.position(4);
        for (int row = 0; row < 2; row++) {
            for (int channel = 0; channel < 4; channel++) {
                source.put(4 + row * 12 + channel, (byte) (20 + row * 40));
                source.put(4 + row * 12 + 8 + channel, (byte) (100 + row * 40));
            }
        }
        ByteBuffer output = ByteBuffer.allocate(12);
        output.position(4);
        SpriteFramePixels.blend(source, 3, 1, 2, 2, 0, 0, 0, 0.25f, output);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 80, 80, 80, 80, 120, 120, 120, 120}, output.array());
        assertEquals(4, source.position());
        assertEquals(4, output.position());
    }

    @Test
    void progressMatchesTheIntegerCarriedByTheVertexId() {
        assertEquals(0f, SpriteFramePixels.progress(0, 7));
        assertEquals(0.428f, SpriteFramePixels.progress(3, 7), 0.000001f);
        assertEquals(0.857f, SpriteFramePixels.progress(6, 7), 0.000001f);
    }
}
