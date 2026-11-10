/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraEffectsSettingsTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @Test
    void offStillWritesEveryPostEffectSwitchOff() {
        Map<String, String> written = new LinkedHashMap<>();
        new CameraEffectsSettings(written::put).reapply();

        assertEquals("false", written.get(CameraEffectsSettings.ENABLE_KEY));
        assertEquals("false", written.get(CameraEffectsSettings.MOTION_BLUR_KEY));
        assertEquals("false", written.get(CameraEffectsSettings.CHROMATIC_ABERRATION_KEY));
        assertEquals("false", written.get(CameraEffectsSettings.VIGNETTE_KEY));
        assertFalse(written.containsKey(CameraEffectsSettings.SAMPLE_COUNT_KEY));
    }

    @Test
    void eachQualityStepMapsToItsSampleBudgetWithNoiseSamplingAlwaysOn() {
        Map<String, String> written = new LinkedHashMap<>();
        CameraEffectsSettings settings = new CameraEffectsSettings(written::put);

        settings.setQuality(CameraEffectsSettings.STANDARD);
        assertEquals("true", written.get(CameraEffectsSettings.ENABLE_KEY));
        assertEquals("8", written.get(CameraEffectsSettings.SAMPLE_COUNT_KEY));
        assertEquals("true", written.get(CameraEffectsSettings.NOISE_SAMPLE_KEY));
        assertEquals("false", written.get(CameraEffectsSettings.EMISSIVE_KEY));
        assertEquals("false", written.get(CameraEffectsSettings.VIGNETTE_KEY));

        settings.setQuality(CameraEffectsSettings.HIGH);
        assertEquals("16", written.get(CameraEffectsSettings.SAMPLE_COUNT_KEY));
        assertEquals("true", written.get(CameraEffectsSettings.EMISSIVE_KEY));

        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        assertEquals("32", written.get(CameraEffectsSettings.SAMPLE_COUNT_KEY));

        settings.setQuality(99);
        assertEquals(CameraEffectsSettings.ULTRA, settings.getQuality());
        assertEquals("48", written.get(CameraEffectsSettings.SAMPLE_COUNT_KEY));
        assertEquals("false", written.get(CameraEffectsSettings.CHROMATIC_ABERRATION_KEY));
    }

    @Test
    void theShutterIsATimeSoTheStreakLengthDoesNotDependOnFrameRate() {
        float shutterMs = 1000.0f / 48.0f;
        float at60 = CameraEffectsSettings.exposureFraction(shutterMs, 1.0f / 60.0f);
        float at144 = CameraEffectsSettings.exposureFraction(shutterMs, 1.0f / 144.0f);
        assertEquals(1.25f, at60, 1.0e-4f);
        assertEquals(3.0f, at144, 1.0e-4f);
        // Streak length is per-frame motion times the fraction; per-frame motion scales with
        // frame time, so the product is the same at both rates.
        assertEquals(at60 / 60.0f, at144 / 144.0f, 1.0e-4f);

        assertEquals(CameraEffectsSettings.MAX_EXPOSURE_FRACTION,
                CameraEffectsSettings.exposureFraction(41.7f, 1.0f / 240.0f), 0.0f);
        assertEquals(CameraEffectsSettings.MIN_EXPOSURE_FRACTION,
                CameraEffectsSettings.exposureFraction(4.0f, 0.2f), 0.0f);
    }

    @Test
    void frameTimeIsSmoothedAndOnlyAMeaningfulChangeIsWritten() {
        List<String> fractions = new ArrayList<>();
        CameraEffectsSettings settings = new CameraEffectsSettings((key, value) -> {
            if (CameraEffectsSettings.SHUTTER_KEY.equals(key)) {
                fractions.add(value);
            }
        });
        settings.setQuality(CameraEffectsSettings.HIGH);
        settings.setShutterMs(10.0f);
        int writesAfterSetup = fractions.size();

        long now = 0L;
        for (int frame = 0; frame < 200; frame++) {
            settings.onFrame(now, 0.0f);
            now += NANOS_PER_SECOND / 120;
        }
        float settled = Float.parseFloat(fractions.get(fractions.size() - 1));
        assertEquals(10.0f / (1000.0f / 120.0f), settled, 0.03f);
        assertTrue(fractions.size() - writesAfterSetup < 40, "writes are throttled: " + fractions.size());

        int writesBeforeSteadyFrames = fractions.size();
        for (int frame = 0; frame < 100; frame++) {
            settings.onFrame(now, 0.0f);
            now += NANOS_PER_SECOND / 120;
        }
        assertEquals(writesBeforeSteadyFrames, fractions.size(), "a steady frame rate writes nothing");

        // A loading hitch is not averaged in.
        now += 2 * NANOS_PER_SECOND;
        settings.onFrame(now, 0.0f);
        assertEquals(writesBeforeSteadyFrames, fractions.size());
    }

    @Test
    void shutterAndMaxBlurReachTheRuntimeOnlyWhileBlurIsOnAndStayInRange() {
        Map<String, String> written = new LinkedHashMap<>();
        CameraEffectsSettings settings = new CameraEffectsSettings(written::put);
        settings.setShutterMs(20.0f);
        settings.onFrame(0L, 0.0f);
        assertFalse(written.containsKey(CameraEffectsSettings.SHUTTER_KEY));

        settings.setQuality(CameraEffectsSettings.HIGH);
        settings.setMaxBlur(0.5f);
        assertEquals(Float.toString(CameraEffectsSettings.MAX_MAX_BLUR),
                written.get(CameraEffectsSettings.MAX_BLUR_KEY));
        // 20 ms at the assumed 60 fps before any frame is measured.
        assertEquals(1.2f, Float.parseFloat(written.get(CameraEffectsSettings.SHUTTER_KEY)), 1.0e-4f);

        settings.setShutterMs(1000.0f);
        assertEquals(CameraEffectsSettings.MAX_SHUTTER_MS, settings.getShutterMs(), 0.0f);
    }

    @Test
    void aFixedFrameTimeIsUsedAsIsWithoutSmoothing() {
        Map<String, String> written = new LinkedHashMap<>();
        CameraEffectsSettings settings = new CameraEffectsSettings(written::put);
        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        settings.setShutterMs(1000.0f / 48.0f);

        // An offline export takes seconds per frame on the wall clock but plays at 60 fps.
        long now = 0L;
        for (int frame = 0; frame < 3; frame++) {
            settings.onFrame(now, 1.0f / 60.0f);
            now += 3 * NANOS_PER_SECOND;
        }
        assertEquals(60.0f / 48.0f, Float.parseFloat(written.get(CameraEffectsSettings.SHUTTER_KEY)), 1.0e-4f);
    }

    // ─── frame generation ────────────────────────────────────────────────

    /** A 1080p-wide viewport with a 90 degree horizontal field of view. */
    private static final int VIEWPORT_WIDTH = 1920;
    private static final float HORIZONTAL_FOV = 90.0f;

    @Test
    void withFrameGenerationOffEveryWriteIsWhatItWasWithoutTheAdaptation() {
        List<String> plain = new ArrayList<>();
        List<String> withCamera = new ArrayList<>();
        CameraEffectsSettings reference = new CameraEffectsSettings((k, v) -> plain.add(k + "=" + v), true);
        CameraEffectsSettings adapted = new CameraEffectsSettings((k, v) -> withCamera.add(k + "=" + v), true);
        for (CameraEffectsSettings settings : List.of(reference, adapted)) {
            settings.setQuality(CameraEffectsSettings.CINEMATIC);
            settings.setShutterMs(33.18f);
        }
        adapted.setFrameGeneration(false);

        long now = 0L;
        float yaw = 0.0f;
        for (int frame = 0; frame < 300; frame++) {
            // An uneven frame rate and a fast, wrapping turn, which would adapt with FG on.
            long step = NANOS_PER_SECOND / (frame % 3 == 0 ? 35 : 50);
            reference.onFrame(now, 0.0f);
            adapted.onFrame(now, 0.0f, yaw, 10.0f, HORIZONTAL_FOV, VIEWPORT_WIDTH);
            now += step;
            yaw += 7.5f;
        }
        assertEquals(plain, withCamera);
        assertTrue(plain.contains(CameraEffectsSettings.JITTER_KEY + "=0.8"));
    }

    @Test
    void theFrameRateScaleRisesFromItsFloorAtThirtyToFullAtSixty() {
        assertEquals(CameraEffectsSettings.FG_MIN_FPS_SCALE,
                CameraEffectsSettings.fgExposureScale(20.0f, 0.0f, 0.05f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f),
                1.0e-6f);
        assertEquals(CameraEffectsSettings.FG_MIN_FPS_SCALE,
                CameraEffectsSettings.fgExposureScale(30.0f, 0.0f, 1.0f / 30.0f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f),
                1.0e-6f);
        float at45 = CameraEffectsSettings.fgExposureScale(45.0f, 0.0f, 1.0f / 45.0f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f);
        assertEquals((CameraEffectsSettings.FG_MIN_FPS_SCALE + 1.0f) * 0.5f, at45, 1.0e-6f);
        assertEquals(1.0f,
                CameraEffectsSettings.fgExposureScale(60.0f, 0.0f, 1.0f / 60.0f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f),
                1.0e-6f);
        assertEquals(1.0f,
                CameraEffectsSettings.fgExposureScale(144.0f, 0.0f, 1.0f / 144.0f, 0.0f, 0.0f, 1.0f),
                1.0e-6f);
    }

    @Test
    void aFastTurnShortensTheStreakToItsPixelBudgetButNotPastTheFloor() {
        // 120 deg/s at 60 fps sweeps 2 degrees a frame, 42.7 px at 21.3 px per degree.
        float moderate = CameraEffectsSettings.fgExposureScale(
                60.0f, 120.0f, 1.0f / 60.0f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f);
        assertEquals(32.0f / (2.0f * VIEWPORT_WIDTH / HORIZONTAL_FOV), moderate, 1.0e-4f);
        // The budget scales with the viewport, so the same turn at 4K scales the same.
        assertEquals(moderate, CameraEffectsSettings.fgExposureScale(
                60.0f, 120.0f, 1.0f / 60.0f, 2 * VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f), 1.0e-4f);
        // 360 deg/s would need 0.25; the floor holds it at 0.4.
        assertEquals(CameraEffectsSettings.FG_MIN_MOTION_SCALE, CameraEffectsSettings.fgExposureScale(
                60.0f, 360.0f, 1.0f / 60.0f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f), 1.0e-6f);
        // A slow pan inside the budget is untouched.
        assertEquals(1.0f, CameraEffectsSettings.fgExposureScale(
                60.0f, 30.0f, 1.0f / 60.0f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f), 1.0e-6f);
        // The smaller of the two scales wins.
        assertEquals(CameraEffectsSettings.FG_MIN_FPS_SCALE, CameraEffectsSettings.fgExposureScale(
                30.0f, 120.0f, 1.0f / 30.0f, VIEWPORT_WIDTH, HORIZONTAL_FOV, 1.0f), 1.0e-6f);
    }

    @Test
    void theAdaptedFractionStaysWithinOneRealFrame() {
        assertEquals(1.0f, CameraEffectsSettings.adaptedExposureFraction(2.0f, 1.0f), 0.0f);
        assertEquals(0.8f, CameraEffectsSettings.adaptedExposureFraction(2.0f, 0.4f), 1.0e-6f);
        assertEquals(CameraEffectsSettings.MIN_EXPOSURE_FRACTION,
                CameraEffectsSettings.adaptedExposureFraction(0.1f, 0.35f), 0.0f);
    }

    @Test
    void theScaleShrinksQuicklyAndRecoversSlowly() {
        float shrunk = CameraEffectsSettings.easeFgScale(1.0f, 0.4f, 0.08f);
        float recovered = CameraEffectsSettings.easeFgScale(0.4f, 1.0f, 0.08f);
        // One attack time constant covers 63 % of the way down; the same time recovers 12 %.
        assertEquals(1.0f - 0.6f * (1.0f - (float) Math.exp(-1.0)), shrunk, 1.0e-5f);
        assertTrue(recovered - 0.4f < 0.6f * 0.15f, "recovered " + recovered);
        assertEquals(0.5f, CameraEffectsSettings.easeFgScale(0.5f, 0.5f, 1.0f), 0.0f);
    }

    @Test
    void yawDifferencesWrapAroundTheCircle() {
        assertEquals(10.0f, CameraEffectsSettings.wrapDegrees(370.0f), 1.0e-4f);
        assertEquals(-10.0f, CameraEffectsSettings.wrapDegrees(350.0f), 1.0e-4f);
        assertEquals(10.0f, CameraEffectsSettings.wrapDegrees(-350.0f), 1.0e-4f);
        assertEquals(-20.0f, CameraEffectsSettings.wrapDegrees(-740.0f), 1.0e-4f);
    }

    @Test
    void withFrameGenerationAFlickCutsTheBlurAtOnceAndItEasesBackAfterwards() {
        Map<String, String> written = new LinkedHashMap<>();
        CameraEffectsSettings settings = new CameraEffectsSettings(written::put, true);
        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        settings.setShutterMs(33.18f);
        settings.setFrameGeneration(true);

        long[] now = {0L};
        float[] yaw = {0.0f};
        // A still camera at a 40 fps base: the frame-rate scale alone applies.
        float physical = CameraEffectsSettings.exposureFraction(33.18f, 1.0f / 40.0f);
        float still = CameraEffectsSettings.adaptedExposureFraction(physical,
                CameraEffectsSettings.fgExposureScale(40.0f, 0.0f, 1.0f / 40.0f,
                        VIEWPORT_WIDTH, HORIZONTAL_FOV, physical));
        runFrames(settings, now, yaw, 120, 40, 0.0f);
        assertEquals(still, shutter(written), 0.021f * still);

        // A 360 deg/s flick: within a quarter second the fraction is near its floor.
        runFrames(settings, now, yaw, 10, 40, 360.0f / 40.0f);
        float flick = shutter(written);
        assertTrue(flick < 0.9f * still, "flick fraction " + flick + " against " + still);
        assertTrue(flick >= CameraEffectsSettings.FG_MIN_MOTION_SCALE * physical * 0.98f, "flick " + flick);

        // Stopping: a quarter second later the blur is still well short of where it was ...
        runFrames(settings, now, yaw, 10, 40, 0.0f);
        assertTrue(shutter(written) < 0.9f * still, "recovery is slow: " + shutter(written));
        // ... and three seconds later it is back.
        runFrames(settings, now, yaw, 120, 40, 0.0f);
        assertEquals(still, shutter(written), 0.021f * still);
    }

    @Test
    void withFrameGenerationALowBaseFrameRateShortensTheBlurButNeverRemovesIt() {
        Map<String, String> written = new LinkedHashMap<>();
        CameraEffectsSettings settings = new CameraEffectsSettings(written::put, true);
        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        settings.setShutterMs(33.18f);
        settings.setFrameGeneration(true);

        long[] now = {0L};
        float[] yaw = {0.0f};
        runFrames(settings, now, yaw, 300, 25, 0.0f);
        float physical = CameraEffectsSettings.exposureFraction(33.18f, 1.0f / 25.0f);
        assertEquals(physical * CameraEffectsSettings.FG_MIN_FPS_SCALE, shutter(written), 0.02f * physical);
    }

    @Test
    void jitterIsWrittenOnlyWhenFrameGenerationChanges() {
        List<String> jitter = new ArrayList<>();
        CameraEffectsSettings settings = new CameraEffectsSettings((key, value) -> {
            if (CameraEffectsSettings.JITTER_KEY.equals(key)) {
                jitter.add(value);
            }
        }, true);
        // Off: a change is remembered but not written.
        settings.setFrameGeneration(true);
        settings.setFrameGeneration(false);
        assertTrue(jitter.isEmpty());

        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        assertEquals(List.of("0.8"), jitter);
        settings.setFrameGeneration(true);
        settings.setFrameGeneration(true);
        assertEquals(List.of("0.8", "0.35"), jitter);

        long[] now = {0L};
        float[] yaw = {0.0f};
        runFrames(settings, now, yaw, 60, 40, 3.0f);
        assertEquals(2, jitter.size(), "frames write no jitter");

        // A reapply restates the frame-generation jitter, not the quality's.
        settings.reapply();
        assertEquals("0.35", jitter.get(jitter.size() - 1));
        settings.setFrameGeneration(false);
        assertEquals("0.8", jitter.get(jitter.size() - 1));
        settings.setQuality(CameraEffectsSettings.STANDARD);
        assertEquals("0.6", jitter.get(jitter.size() - 1));
    }

    @Test
    void adaptedWritesAreAtLeastFiftyMillisecondsApart() {
        List<Long> writeTimes = new ArrayList<>();
        long[] now = {0L};
        CameraEffectsSettings settings = new CameraEffectsSettings((key, value) -> {
            if (CameraEffectsSettings.SHUTTER_KEY.equals(key)) {
                writeTimes.add(now[0]);
            }
        }, true);
        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        // 10 ms at 120 fps is 1.2 frames, so the adapted fraction moves below its one-frame cap.
        settings.setShutterMs(10.0f);
        settings.setFrameGeneration(true);
        writeTimes.clear();

        float[] yaw = {0.0f};
        // A camera that keeps starting and stopping, at a 120 fps base, so the target moves a lot.
        for (int burst = 0; burst < 20; burst++) {
            runFrames(settings, now, yaw, 6, 120, burst % 2 == 0 ? 4.0f : 0.0f);
        }
        assertTrue(writeTimes.size() > 2, "the adaptation wrote: " + writeTimes.size());
        for (int i = 1; i < writeTimes.size(); i++) {
            long gap = writeTimes.get(i) - writeTimes.get(i - 1);
            assertTrue(gap >= CameraEffectsSettings.FG_MIN_WRITE_INTERVAL_NANOS, "gap " + gap);
        }
    }

    @Test
    void anExportKeepsThePhysicalFractionEvenWithFrameGenerationOn() {
        Map<String, String> written = new LinkedHashMap<>();
        CameraEffectsSettings settings = new CameraEffectsSettings(written::put, true);
        settings.setFrameGeneration(true);
        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        settings.setShutterMs(1000.0f / 48.0f);
        // The apply already wrote the adapted fraction, capped at one frame.
        assertEquals(1.0f, shutter(written), 0.0f);
        assertEquals("0.35", written.get(CameraEffectsSettings.JITTER_KEY));

        long now = 0L;
        float yaw = 0.0f;
        for (int frame = 0; frame < 3; frame++) {
            settings.onFrame(now, 1.0f / 60.0f, yaw, 0.0f, HORIZONTAL_FOV, VIEWPORT_WIDTH);
            now += 3 * NANOS_PER_SECOND;
            yaw += 20.0f;
        }
        assertEquals(60.0f / 48.0f, shutter(written), 1.0e-4f);
        // The export draws the player's own jitter too, not the frame-generation one ...
        assertEquals("0.8", written.get(CameraEffectsSettings.JITTER_KEY));

        // ... and the first measured frame after it restores both.
        settings.onFrame(now, 0.0f, yaw, 0.0f, HORIZONTAL_FOV, VIEWPORT_WIDTH);
        assertEquals("0.35", written.get(CameraEffectsSettings.JITTER_KEY));
        assertEquals(1.0f, shutter(written), 0.0f);
    }

    @Test
    void aboveTheOneFrameCapAFlickShortensTheBlurWithoutFirstEasingDownToTheCap() {
        Map<String, String> written = new LinkedHashMap<>();
        CameraEffectsSettings settings = new CameraEffectsSettings(written::put, true);
        settings.setQuality(CameraEffectsSettings.CINEMATIC);
        settings.setShutterMs(33.18f);
        settings.setFrameGeneration(true);

        // A 60 fps base: the physical fraction is about 2, so a still camera sits at the cap.
        long[] now = {0L};
        float[] yaw = {0.0f};
        runFrames(settings, now, yaw, 120, 60, 0.0f);
        assertEquals(1.0f, shutter(written), 0.0f);
        assertTrue(settings.fgScale() <= 1.0f
                / CameraEffectsSettings.exposureFraction(33.18f, 1.0f / 60.0f) + 1.0e-4f,
                "the applied scale is held at the cap: " + settings.fgScale());

        // A 360 deg/s flick of a tenth of a second. Easing the raw scale from 1 would leave
        // the fraction pinned at the cap for about 145 ms; from the cap it moves at once.
        runFrames(settings, now, yaw, 6, 60, 6.0f);
        assertTrue(shutter(written) < 0.95f, "flick fraction " + shutter(written));
    }

    @Test
    void theKillSwitchLeavesFrameGenerationUnadapted() {
        List<String> plain = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        CameraEffectsSettings reference = new CameraEffectsSettings((k, v) -> plain.add(k + "=" + v), false);
        CameraEffectsSettings switchedOff = new CameraEffectsSettings((k, v) -> disabled.add(k + "=" + v), false);
        for (CameraEffectsSettings settings : List.of(reference, switchedOff)) {
            settings.setQuality(CameraEffectsSettings.CINEMATIC);
            settings.setShutterMs(33.18f);
        }
        switchedOff.setFrameGeneration(true);
        assertTrue(switchedOff.isFrameGeneration());

        long now = 0L;
        float yaw = 0.0f;
        for (int frame = 0; frame < 200; frame++) {
            reference.onFrame(now, 0.0f, yaw, 0.0f, HORIZONTAL_FOV, VIEWPORT_WIDTH);
            switchedOff.onFrame(now, 0.0f, yaw, 0.0f, HORIZONTAL_FOV, VIEWPORT_WIDTH);
            now += NANOS_PER_SECOND / 30;
            yaw += 12.0f;
        }
        assertEquals(plain, disabled);
    }

    /** Renders frames at a steady rate, turning the camera by a fixed yaw each frame. */
    private static void runFrames(CameraEffectsSettings settings, long[] now, float[] yaw,
                                  int frames, int fps, float yawPerFrame) {
        for (int frame = 0; frame < frames; frame++) {
            settings.onFrame(now[0], 0.0f, yaw[0], 0.0f, HORIZONTAL_FOV, VIEWPORT_WIDTH);
            now[0] += NANOS_PER_SECOND / fps;
            yaw[0] += yawPerFrame;
        }
    }

    private static float shutter(Map<String, String> written) {
        return Float.parseFloat(written.get(CameraEffectsSettings.SHUTTER_KEY));
    }
}
