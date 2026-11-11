/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.buffers.GpuFence;
import com.nvidia.rtxremix.minecraft.community.DaylightSky;
import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanBuffer;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanImage;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanWork;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/** Physical and legacy atmospheric integration on Minecraft's Vulkan queue, with asynchronous readback. */
final class AtmosphericSkyGenerator {
    static final int WIDTH = 256;
    static final int HEIGHT = 128;
    static final int UPPER_HEIGHT = HEIGHT / 2;
    static final int OUTPUT_BYTES = WIDTH * HEIGHT * 4 * Short.BYTES;
    static final int READBACK_BYTES = WIDTH * (HEIGHT + 1) * 4 * Short.BYTES;
    static final int LUT_WIDTH = 1024;
    static final int LUT_HEIGHT = 256;
    static final int MULTISCATTERING_LUT_SIZE = 32;
    private static final int MODEL_LEGACY = 0;
    private static final int MODEL_PHYSICAL = 1;
    private static final int READBACK_RING_SIZE = 3;
    private static final String SHADER_RESOURCE = "/assets/rtx-remix-renderer/shaders/sky_atmosphere.comp";

    private final float[] sunDirection = new float[3];
    private final float[] moonDirection = new float[3];
    private final VulkanWork work;
    private final AtmosphericSkyResources resources;
    private final int outputBytes;
    private final ByteBuffer output;
    private final ByteBuffer skyParameters = BufferUtils.createByteBuffer(80);
    private final ByteBuffer publishedSkyParameters = BufferUtils.createByteBuffer(80);
    private final GpuFence[] readbackFences = new GpuFence[READBACK_RING_SIZE];
    private final long[] readbackGenerations = new long[READBACK_RING_SIZE];
    private long generation;
    private int readbackWriteIndex, readbackReadIndex, pendingReadbacks;
    private int lutModel = -1;
    private boolean hasPublishedSkyParameters;
    private boolean closed;

    AtmosphericSkyGenerator() { this(WIDTH, HEIGHT); }

    AtmosphericSkyGenerator(int width, int height) { this(VulkanWork.minecraft(), width, height); }

    AtmosphericSkyGenerator(VulkanWork work, int width, int height) {
        if (width < 8 || height < 4 || (height & 1) != 0) {
            throw new IllegalArgumentException("Sky resolution requires width >= 8 and an even height >= 4");
        }
        this.work = work;
        outputBytes = width * height * 4 * Short.BYTES;
        output = BufferUtils.createByteBuffer(outputBytes);
        resources = new AtmosphericSkyResources(work, loadShaderSource(width, height), width, height);
    }

    /** Returns an independent completed image, or null while it is still on the GPU. */
    ByteBuffer generate(SkyLightState lightState, float[] upperMean, float mieScatteringAnisotropy,
                        boolean publishedOutputValid) {
        if (closed) throw new IllegalStateException("Atmospheric generator is closed");
        if (upperMean.length < 3) throw new IllegalArgumentException("Upper-mean output requires three floats");
        if (!Float.isFinite(mieScatteringAnisotropy) || mieScatteringAnisotropy < 0 || mieScatteringAnisotropy >= 1) {
            throw new IllegalArgumentException("Mie scattering anisotropy must be in [0, 1)");
        }
        boolean changed;
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Parameters")) {
            setSkyTextureDirection(sunDirection, lightState.sunPosition());
            setSkyTextureDirection(moonDirection, lightState.moonPosition());
            normalize(sunDirection);
            normalize(moonDirection);
            changed = fillSkyParameters(lightState, mieScatteringAnisotropy);
        }
        ByteBuffer completed = consumeCompletedReadback(upperMean);
        if ((!changed && publishedOutputValid) || pendingReadbacks == READBACK_RING_SIZE) return completed;
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Dispatch")) {
            VkCommandBuffer commands = work.begin();
            int model = activeModel();
            resources.record(commands, skyParameters, model, model != lutModel);
            resources.finalSky().copyTo(commands, resources.readbacks[readbackWriteIndex]);
            work.execute(commands);
            readbackFences[readbackWriteIndex] = work.fence();
            readbackGenerations[readbackWriteIndex] = generation;
            readbackWriteIndex = (readbackWriteIndex + 1) % READBACK_RING_SIZE;
            pendingReadbacks++;
            lutModel = model;
            markSkyParametersDispatched();
        }
        return completed;
    }

    private ByteBuffer consumeCompletedReadback(float[] upperMean) {
        ByteBuffer completed = null;
        while (pendingReadbacks > 0) {
            int index = readbackReadIndex;
            GpuFence fence = readbackFences[index];
            if (!fence.awaitCompletion(0)) break;
            fence.close();
            readbackFences[index] = null;
            if (readbackGenerations[index] == generation) {
                ByteBuffer mapped = resources.readbacks[index].read();
                upperMean[0] = Float.float16ToFloat(mapped.getShort(outputBytes));
                upperMean[1] = Float.float16ToFloat(mapped.getShort(outputBytes + 2));
                upperMean[2] = Float.float16ToFloat(mapped.getShort(outputBytes + 4));
                // The GPU may reuse this ring slot before the caller finishes its native upload.
                output.clear();
                MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), MemoryUtil.memAddress(output), outputBytes);
                completed = output;
            }
            readbackReadIndex = (readbackReadIndex + 1) % READBACK_RING_SIZE;
            pendingReadbacks--;
        }
        return completed;
    }

    void readTransmittanceLut(ByteBuffer destination) {
        int bytes = LUT_WIDTH * LUT_HEIGHT * 4 * Float.BYTES;
        if (!destination.isDirect() || destination.capacity() < bytes || !hasOutput()) {
            throw new IllegalArgumentException("LUT readback requires generated output and a complete direct destination");
        }
        VulkanBuffer readback = new VulkanBuffer(work, bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try {
            VkCommandBuffer commands = work.begin();
            resources.transmittance().copyTo(commands, readback);
            work.execute(commands);
            try (GpuFence fence = work.fence()) {
                work.submit();
                if (!fence.awaitCompletion(5_000_000_000L)) throw new IllegalStateException("Atmospheric LUT readback timed out");
                destination.clear().limit(bytes);
                MemoryUtil.memCopy(MemoryUtil.memAddress(readback.read()), MemoryUtil.memAddress(destination), bytes);
            }
        } finally {
            work.retire(readback);
        }
    }

    /** Old scene readbacks remain fenced until completion, but can no longer be published. */
    void invalidate() {
        generation++;
        hasPublishedSkyParameters = false;
    }

    void destroy() {
        if (closed) return;
        closed = true;
        for (GpuFence fence : readbackFences) if (fence != null) fence.close();
        work.retire(resources);
    }

    int outputBytes() { return outputBytes; }
    VulkanImage finalSkyTexture() { return resources.finalSky(); }
    VulkanImage transmittanceLutTexture() { return resources.transmittance(); }
    boolean hasOutput() { return hasPublishedSkyParameters && lutModel >= 0; }

    private boolean fillSkyParameters(
            SkyLightState lightState,
            float mieScatteringAnisotropy) {
        putVectorAndScalar(
                0,
                sunDirection,
                lightState.sunAngularRadius());
        putVectorAndScalar(
                16,
                moonDirection,
                lightState.moonAngularRadius());
        putVectorAndScalar(
                32,
                lightState.sunSourceRadiance(),
                atmosphereScatteringScale(
                        lightState.sunPosition()[1]));
        putVectorAndScalar(
                48,
                lightState.moonSourceRadiance(),
                0.0f);
        skyParameters.putFloat(64, mieScatteringAnisotropy);
        skyParameters.putFloat(68, DaylightSky.haloBoost());
        skyParameters.putFloat(72, activeModel() == MODEL_PHYSICAL ? 1.0f : 0.0f);
        skyParameters.putFloat(76, 0.0f);

        return !hasPublishedSkyParameters || skyParameters.mismatch(publishedSkyParameters) >= 0;
    }

    /** Records the block the dispatch just ran on, so the next identical block can skip it. */
    private void markSkyParametersDispatched() {
        publishedSkyParameters.clear();
        publishedSkyParameters.put(skyParameters.duplicate());
        publishedSkyParameters.clear();
        hasPublishedSkyParameters = true;
    }

    private void putVectorAndScalar(
            int offset,
            float[] vector,
            float scalar) {
        skyParameters.putFloat(offset, vector[0]);
        skyParameters.putFloat(offset + Float.BYTES, vector[1]);
        skyParameters.putFloat(offset + 2 * Float.BYTES, vector[2]);
        skyParameters.putFloat(offset + 3 * Float.BYTES, scalar);
    }

    private static int activeModel() {
        return DaylightSky.isPhysicalAtmosphere() ? MODEL_PHYSICAL : MODEL_LEGACY;
    }

    private static String loadShaderSource(int width, int height) {
        try (InputStream stream =
                AtmosphericSkyGenerator.class.getResourceAsStream(
                        SHADER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing atmospheric sky shader " + SHADER_RESOURCE);
            }
            String source = new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8);
            return withSkyDimensions(source, width, height);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read atmospheric sky shader "
                            + SHADER_RESOURCE,
                    e);
        }
    }

    /**
     * Inserts the size defines directly below the shader's {@code #version} directive.
     * GLSL requires that directive to precede every statement, and the file opens with
     * its licence header, so the defines cannot simply follow the first line.
     */
    private static String withSkyDimensions(String source, int width, int height) {
        int versionStart = source.indexOf("#version");
        if (versionStart < 0) {
            throw new IllegalStateException(
                    "Atmospheric sky shader has no #version directive");
        }
        int versionEnd = source.indexOf('\n', versionStart) + 1;
        return source.substring(0, versionEnd)
                + "#define SKY_WIDTH " + width + "\n"
                + "#define SKY_HEIGHT " + height + "\n"
                + AtmosphereModel.shaderDefines()
                + source.substring(versionEnd);
    }

    private static void setSkyTextureDirection(
            float[] output,
            float[] direction) {
        output[0] = -direction[0];
        output[1] = direction[1];
        output[2] = direction[2];
    }

    private static void normalize(float[] vector) {
        float length = (float) Math.sqrt(
                vector[0] * vector[0]
                        + vector[1] * vector[1]
                        + vector[2] * vector[2]);
        if (length > 0.0f) {
            vector[0] /= length;
            vector[1] /= length;
            vector[2] /= length;
        }
    }

    /**
     * Factor from a light's Remix radiance to the illuminance the sky shader scatters. The
     * physical model uses the distant light's own conversion, pi, at every hour, so the sky
     * and the light it comes with keep their real ratio by day and by night; the look's
     * sky-fill factor scales it. The legacy model keeps upstream's empirical day and night
     * multipliers.
     */
    static float atmosphereScatteringScale(float sunHeight) {
        if (DaylightSky.isPhysicalAtmosphere()) {
            return AtmosphereModel.DISTANT_LIGHT_IRRADIANCE_PER_RADIANCE * DaylightSky.skyFill();
        }
        float nightFactor =
                1.0f - smoothstep(-0.15f, 0.05f, sunHeight);
        return DaylightSky.dayScattering() * (1.0f - nightFactor) + 70.0f * nightFactor;
    }

    private static float smoothstep(
            float edge0,
            float edge1,
            float value) {
        float amount = Math.max(
                0.0f,
                Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return amount * amount * (3.0f - 2.0f * amount);
    }

}
