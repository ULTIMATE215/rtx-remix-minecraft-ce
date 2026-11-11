/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.clouds;

import com.nvidia.rtxremix.minecraft.community.LightningFlash;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import com.nvidia.rtxremix.minecraft.vulkan.HeadlessVulkan;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanBuffer;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanImage;
import static org.lwjgl.vulkan.VK10.*;

import static org.junit.jupiter.api.Assertions.*;



/** Actual GPU and readback coverage, run explicitly with Gradle's cloudGpuTest task. */
@EnabledIfSystemProperty(named = "rtxremix.test.cloudGpu", matches = "true")
class CloudSkyCompositorGpuTest {
    private HeadlessVulkan work;
    private CloudSkyCompositor compositor;
    private VulkanImage atmosphere, lut;

    @BeforeAll
    static void prepareNoise() {
        CloudNoise.shared().join();
    }

    @BeforeEach
    void prepare() {
        work = new HeadlessVulkan();
        LightningFlash.reset();
        VolumetricClouds.deactivate();
        // Thin, fully covered deck, wholly within the near mesh cap.
        VolumetricClouds.publishFrame(new CloudField.Params(400, 1, -1, 0), 0);
        VolumetricClouds.publishOccupancy(0, 0, (x, z) -> true);
        atmosphere = constantTexture(16, 9, 0);
        lut = constantTexture(1024, 256, 1);
        compositor = new CloudSkyCompositor(work, new VolumetricClouds.Quality(512, 96, 4, 320, 8, 1), 16, 8);
    }

    @AfterEach
    void release() {
        if (compositor != null) compositor.destroy();
        if (atmosphere != null) work.retire(atmosphere);
        if (lut != null) work.retire(lut);
        VolumetricClouds.deactivate();
        LightningFlash.reset();
        if (work != null) work.close();
    }

    @Test
    void nearCloudsOccludeTheDiscButNotTheAlreadyShadowedDistantLight() {
        float[] mean = new float[3];
        ByteBuffer pixels = render(frame(2), mean);
        assertNotNull(pixels);
        assertEquals(compositor.outputBytes(), pixels.remaining());
        assertEquals(1, VolumetricClouds.sunTransmittance(), 0.001);
        assertTrue(VolumetricClouds.sunDiscTransmittance() < 0.8f,
                "visible sun must be hidden by the near deck: " + VolumetricClouds.sunDiscTransmittance());
        assertTrue(mean[0] > 0, "the cloud shader produced illuminated clouds");
    }

    @Test
    void invalidationDiscardsAnAlreadyQueuedOldSceneReadback() throws Exception {
        float[] mean = new float[3];
        render(frame(2), mean);
        assertTrue(mean[0] > 0);
        // Queue a completed old image without consuming it, independent of GPU speed.
        Method enqueue = CloudSkyCompositor.class.getDeclaredMethod("enqueueReadback");
        enqueue.setAccessible(true);
        enqueue.invoke(compositor);
        work.submit();
        compositor.invalidate();
        render(frame(0), mean);
        assertArrayEquals(new float[]{0, 0, 0}, mean, 1.0e-6f,
                "a dark new scene must not receive the old illuminated dome or its mean");
    }

    @Test
    void lightningRecomposesWithoutRemarchingOrLeavingAFlashInHistory() throws Exception {
        float[] mean = new float[3];
        render(frame(0), mean);
        assertEquals(0, mean[2], 1.0e-6f);
        int marches = marchCount();
        LightningFlash.advance(2, 1.0f / 60);
        LightningFlash.observeStrike(0, 0, 0, 0);
        render(frame(0), mean);
        assertTrue(mean[2] > 0.001f, "the visible cloud deck must receive the lightning flash");
        assertTrue(mean[2] > mean[0], "the cloud flash has the bolt's cold tint");
        assertEquals(marches, marchCount(), "lightning must reuse the cloud march");
        LightningFlash.advance(0, 1);
        render(frame(0), mean);
        assertArrayEquals(new float[]{0, 0, 0}, mean, 1.0e-6f);
        assertEquals(marches, marchCount());
    }

    private ByteBuffer render(CloudSkyCompositor.Inputs frame, float[] mean) {
        ByteBuffer first = compositor.update(atmosphere, lut, frame, mean);
        work.submit();
        ByteBuffer completed = compositor.update(atmosphere, lut, frame, mean);
        return completed != null ? completed : first;
    }

    private int marchCount() throws Exception {
        Field field = CloudSkyCompositor.class.getDeclaredField("updateIndex");
        field.setAccessible(true);
        return field.getInt(compositor);
    }

    private static CloudSkyCompositor.Inputs frame(float sun) {
        return new CloudSkyCompositor.Inputs(new float[]{0, 1, 0}, new float[]{0, -1, 0},
                new float[]{sun, sun, sun}, new float[]{0, 0, 0}, 0.0046f, 0.0046f,
                0, 64, 0, 6000, true, 1);
    }

    private VulkanImage constantTexture(int width, int height, float value) {
        ByteBuffer data = BufferUtils.createByteBuffer(width * height * 16);
        while (data.hasRemaining()) data.putFloat(value);
        data.flip();
        VulkanImage texture = new VulkanImage(work, width, height, 1, VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        VulkanBuffer upload = new VulkanBuffer(work, data.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        upload.upload(data);
        var commands = work.begin();
        texture.upload(commands, upload);
        work.execute(commands);
        work.retire(upload);
        return texture;
    }
}
