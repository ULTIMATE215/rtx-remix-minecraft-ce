/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.nio.ByteBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

/** Defers vanilla's weather vertices until the same frame's Remix colour and depth arrive. */
public final class RemixWeatherRenderer {
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private final Quaternionf rotation = new Quaternionf();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final FogData fog = new FogData();
    private ByteBuffer vertices;
    private int rainQuads;
    private int snowQuads;
    private GpuTextureView lightmap;
    private boolean cameraReady;
    private int lastDrawCount;
    private int lastQuadCount;

    public static void registerShaders() { WeatherGpu.registerShaders(); }

    public void beginCaptureFrame() {
        rainQuads = snowQuads = 0;
        lightmap = null;
        lastDrawCount = lastQuadCount = 0;
    }

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        modelView.rotation(rotation.set(camera.rotation()).conjugate());
        projection.identity().perspective((float) Math.toRadians(fovYDegrees), aspect, NEAR_PLANE, FAR_PLANE, true);
        cameraReady = true;
    }

    /** Copies before vanilla closes its mesh; the retained direct buffer grows only at its high water mark. */
    public void capture(ByteBuffer source, int rainQuads, int snowQuads, GpuTextureView lightmap) {
        int bytes = Math.multiplyExact(rainQuads + snowQuads, 4 * DefaultVertexFormat.PARTICLE.getVertexSize());
        if (!source.isDirect() || source.remaining() != bytes) {
            throw new IllegalArgumentException("Weather vertices must be the complete direct particle-format mesh");
        }
        if (vertices == null || vertices.capacity() < bytes) {
            ByteBuffer replacement = MemoryUtil.memAlloc(Math.max(bytes, 64 * 1024));
            if (vertices != null) MemoryUtil.memFree(vertices);
            vertices = replacement;
        }
        vertices.clear().limit(bytes);
        MemoryUtil.memCopy(MemoryUtil.memAddress(source), MemoryUtil.memAddress(vertices), bytes);
        this.rainQuads = rainQuads;
        this.snowQuads = snowQuads;
        this.lightmap = lightmap;
        FogData currentFog = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
        fog.color.set(currentFog.color);
        fog.environmentalStart = currentFog.environmentalStart;
        fog.environmentalEnd = currentFog.environmentalEnd;
        fog.renderDistanceStart = currentFog.renderDistanceStart;
        fog.renderDistanceEnd = currentFog.renderDistanceEnd;
    }

    public void render(GpuTextureView finalColorTexture, GpuTextureView depthTexture) {
        if (rainQuads + snowQuads == 0) return;
        try {
            if (finalColorTexture == null || depthTexture == null || lightmap == null || !cameraReady) {
                throw new IllegalStateException("Weather requires this frame's Remix colour, depth and camera");
            }
            WeatherGpu.render(Minecraft.getInstance().gameRenderer.mainRenderTarget(), vertices,
                    rainQuads, snowQuads, lightmap, projection, modelView, fog, finalColorTexture, depthTexture);
            lastDrawCount = (rainQuads > 0 ? 1 : 0) + (snowQuads > 0 ? 1 : 0);
            lastQuadCount = rainQuads + snowQuads;
        } finally {
            rainQuads = snowQuads = 0;
            lightmap = null;
        }
    }

    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastQuadCount() { return lastQuadCount; }

    public void cleanup() {
        beginCaptureFrame();
        cameraReady = false;
        if (vertices != null) {
            MemoryUtil.memFree(vertices);
            vertices = null;
        }
    }
}
