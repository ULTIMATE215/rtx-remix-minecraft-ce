/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Redirects vanilla's CPU feature geometry into a capture provider on the render thread. */
public final class RemixFeatureCapture {
    private static RemixBufferSource current;

    private RemixFeatureCapture() {}

    public static boolean isCapturing() { return current != null; }

    static void replay(RemixFeatureRenderer renderer, FeatureFrameContext context,
                       List<SubmitNode> submits, RemixBufferSource provider) {
        RenderSystem.assertOnRenderThread();
        RemixBufferSource previous = current;
        current = provider;
        try {
            renderer.rtxremix$buildGroup(context, submits);
        } finally {
            current = previous;
        }
    }

    /** Null outside capture, so vanilla keeps its normal staged vertex buffer. */
    public static VertexConsumer buffer(RenderType layer) {
        return current == null ? null : current.getBuffer(layer);
    }
}
