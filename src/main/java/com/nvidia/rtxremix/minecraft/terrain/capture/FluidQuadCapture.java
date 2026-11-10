/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.ARGB;

/** Reusable CPU output for custom fluid handlers, independent of their attribute write order. */
final class FluidQuadCapture implements FluidRenderer.Output {
    @FunctionalInterface
    interface Output {
        void quad(ChunkSectionLayer layer, float[] positions, float[] uvs, int color);
    }

    private final Consumer[] consumers;

    FluidQuadCapture(Output output) {
        ChunkSectionLayer[] layers = ChunkSectionLayer.values();
        consumers = new Consumer[layers.length];
        for (int i = 0; i < layers.length; i++) consumers[i] = new Consumer(layers[i], output);
    }

    @Override
    public VertexConsumer getBuilder(ChunkSectionLayer layer) { return consumers[layer.ordinal()]; }

    void flush() {
        for (Consumer consumer : consumers) consumer.flush();
    }

    void reset() {
        for (Consumer consumer : consumers) consumer.count = 0;
    }

    private static final class Consumer implements VertexConsumer {
        private final ChunkSectionLayer layer;
        private final Output output;
        private final float[] positions = new float[12], uvs = new float[8];
        private int count, color;

        Consumer(ChunkSectionLayer layer, Output output) {
            this.layer = layer;
            this.output = output;
        }

        void flush() {
            if (count == 0) return;
            if (count != 4) throw new IllegalStateException("Fluid handler emitted an incomplete quad");
            output.quad(layer, positions, uvs, color);
            count = 0;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count == 4) flush();
            positions[count * 3] = x;
            positions[count * 3 + 1] = y;
            positions[count * 3 + 2] = z;
            if (count == 0) color = 0xFFFFFFFF;
            count++;
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) {
            uvs[(count - 1) * 2] = u;
            uvs[(count - 1) * 2 + 1] = v;
            return this;
        }
        @Override public VertexConsumer setColor(int argb) { if (count == 1) color = argb; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(ARGB.color(a, r, g, b)); }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
