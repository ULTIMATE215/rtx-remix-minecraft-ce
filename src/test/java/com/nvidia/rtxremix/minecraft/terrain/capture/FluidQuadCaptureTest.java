/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FluidQuadCaptureTest {
    private record Quad(ChunkSectionLayer layer, float[] positions, float[] uvs, int color) {}

    @Test
    void acceptsAttributesAfterTheNormalAndFlushesTheFinalQuad() {
        List<Quad> quads = new ArrayList<>();
        FluidQuadCapture capture = new FluidQuadCapture((layer, positions, uvs, color) ->
                quads.add(new Quad(layer, positions.clone(), uvs.clone(), color)));
        VertexConsumer out = capture.getBuilder(ChunkSectionLayer.TRANSLUCENT);
        for (int q = 0; q < 2; q++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                out.addVertex(q, vertex, 3)
                        .setNormal(0, 1, 0)
                        .setColor(0x80ABCDEF)
                        .setUv(vertex / 4f, q / 2f)
                        .setLight(0xF000F0);
            }
        }
        assertEquals(1, quads.size());
        capture.flush();
        assertEquals(2, quads.size());
        assertEquals(ChunkSectionLayer.TRANSLUCENT, quads.get(1).layer());
        assertEquals(0x80ABCDEF, quads.get(1).color());
        assertArrayEquals(new float[] {1, 0, 3, 1, 1, 3, 1, 2, 3, 1, 3, 3}, quads.get(1).positions());
        assertArrayEquals(new float[] {0, .5f, .25f, .5f, .5f, .5f, .75f, .5f}, quads.get(1).uvs());
        capture.flush();
        assertEquals(2, quads.size());
    }

    @Test
    void incompleteOutputIsRejectedAndResetDropsTheAbortedBlock() {
        List<Quad> quads = new ArrayList<>();
        FluidQuadCapture capture = new FluidQuadCapture((layer, positions, uvs, color) ->
                quads.add(new Quad(layer, positions.clone(), uvs.clone(), color)));
        capture.getBuilder(ChunkSectionLayer.CUTOUT).addVertex(0, 0, 0).setUv(0, 0);
        assertThrows(IllegalStateException.class, capture::flush);
        capture.reset();
        capture.flush();
        assertEquals(0, quads.size());
    }
}
