/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.minecraft.community.DaylightSky;
import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;
import com.nvidia.rtxremix.minecraft.vulkan.HeadlessVulkan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the atmospheric passes and their asynchronous readbacks on a real Vulkan queue. */
@EnabledIfSystemProperty(named = "rtxremix.test.cloudGpu", matches = "true")
class AtmosphericSkyGpuTest {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 16;
    private HeadlessVulkan work;
    private AtmosphericSkyGenerator generator;
    private boolean daylight;
    private float skyFill, halo;

    @BeforeEach
    void prepare() {
        daylight = DaylightSky.isEnabled();
        skyFill = DaylightSky.getSkyFillTuning();
        halo = DaylightSky.getHaloBoostTuning();
        DaylightSky.setSkyFillTuning(1);
        DaylightSky.setHaloBoostTuning(1);
        work = new HeadlessVulkan();
        generator = new AtmosphericSkyGenerator(work, WIDTH, HEIGHT);
    }

    @AfterEach
    void release() {
        if (generator != null) generator.destroy();
        DaylightSky.setEnabled(daylight);
        DaylightSky.setSkyFillTuning(skyFill);
        DaylightSky.setHaloBoostTuning(halo);
        if (work != null) work.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothModelsProduceFiniteDaylightAndTheMeanOfTheirPublishedPixels(boolean physical) {
        DaylightSky.setEnabled(physical);
        float[] mean = new float[3];
        ByteBuffer pixels = render(day(), mean);
        assertNotNull(pixels);
        assertEquals(WIDTH * HEIGHT * 8, pixels.remaining());
        double[] sum = new double[3];
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
            for (int channel = 0; channel < 3; channel++) {
                float radiance = Float.float16ToFloat(pixels.getShort(pixel * 8 + channel * 2));
                assertTrue(Float.isFinite(radiance) && radiance >= 0, "invalid sky radiance");
                if (pixel < WIDTH * HEIGHT / 2) sum[channel] += radiance;
            }
            assertEquals(1, Float.float16ToFloat(pixels.getShort(pixel * 8 + 6)));
        }
        for (int channel = 0; channel < 3; channel++) {
            assertTrue(mean[channel] > 0.01f, "daytime sky must carry light");
            assertEquals(sum[channel] / (WIDTH * HEIGHT / 2), mean[channel], mean[channel] * 0.002,
                    "readback mean must describe the published upper hemisphere");
        }
        assertNull(generator.generate(day(), mean, 0.8f, true), "unchanged output needs no new readback");
    }

    @Test
    void switchingModelsRebuildsTheLutAndReturningToAModelReproducesItsOutput() {
        DaylightSky.setEnabled(false);
        float[] mean = new float[3];
        render(day(), mean);
        float[] legacyMean = mean.clone();
        ByteBuffer legacyLut = lut();
        DaylightSky.setEnabled(true);
        render(day(), mean);
        ByteBuffer physicalLut = lut();
        assertNotEquals(-1, legacyLut.mismatch(physicalLut), "the models have different atmospheres");
        for (int offset = 0; offset < physicalLut.limit(); offset += 16) {
            for (int channel = 0; channel < 3; channel++) {
                float value = physicalLut.getFloat(offset + channel * 4);
                assertTrue(Float.isFinite(value) && value >= 0 && value <= 1, "invalid transmittance");
            }
        }
        DaylightSky.setEnabled(false);
        render(day(), mean);
        assertArrayEquals(legacyMean, mean);
        assertEquals(-1, legacyLut.mismatch(lut()));
    }

    @Test
    void invalidationDiscardsCompletedOldWorldReadbacks() {
        DaylightSky.setEnabled(false);
        float[] mean = {-1, -1, -1};
        assertNull(generator.generate(day(), mean, 0.8f, true));
        work.submit();
        generator.invalidate();
        assertFalse(generator.hasOutput());
        assertNull(generator.generate(SkyLightState.empty(), mean, 0.8f, true));
        assertArrayEquals(new float[]{-1, -1, -1}, mean);
        work.submit();
        ByteBuffer pixels = generator.generate(SkyLightState.empty(), mean, 0.8f, true);
        assertNotNull(pixels);
        assertArrayEquals(new float[]{0, 0, 0}, mean);
        for (int offset = 0; offset < pixels.limit(); offset += 8) {
            for (int channel = 0; channel < 3; channel++) assertEquals(0, pixels.getShort(offset + channel * 2));
        }
    }

    private ByteBuffer render(SkyLightState state, float[] mean) {
        generator.generate(state, mean, 0.8f, true);
        work.submit();
        return generator.generate(state, mean, 0.8f, true);
    }

    private ByteBuffer lut() {
        ByteBuffer result = BufferUtils.createByteBuffer(AtmosphericSkyGenerator.LUT_WIDTH
                * AtmosphericSkyGenerator.LUT_HEIGHT * 16);
        generator.readTransmittanceLut(result);
        return result;
    }

    private static SkyLightState day() {
        return new SkyLightState(new float[]{0, 1, 0}, new float[]{0, -1, 0},
                new float[]{4, 4, 4}, new float[3], new float[]{4, 4, 4}, new float[3], 0.0046f, 0.0046f);
    }
}
