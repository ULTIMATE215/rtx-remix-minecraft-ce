/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.function.BiConsumer;

/**
 * Motion blur through the Remix post-effects pass, off by default.
 *
 * <p>Remix gates motion blur, chromatic aberration, and vignette behind one
 * {@code rtx.postfx.enable} switch, and its graphics presets write that switch
 * to the user configuration. Every apply therefore writes the whole set: the
 * master switch, motion blur, and the other two effects explicitly off, so a
 * preset or a runtime default never brings them along.
 *
 * <p>The shutter is a time, not a share of a frame. Remix stretches each pixel
 * along its motion over one frame times {@code exposureFraction}, with no frame
 * time in the formula, so a fixed fraction blurs half as far at 120 fps as at
 * 60. {@link #onFrame(long, float)} divides the shutter time by a smoothed frame time
 * every frame instead, so a streak is as long at any frame rate as a real
 * camera with that shutter would draw it.
 *
 * <p>DLSS frame generation breaks that model. The generated frame is interpolated between
 * two real frames that each already carry a full streak, so a streak longer than the gap
 * between real frames overlaps its neighbour and the interpolator drags it back and forth:
 * the image wobbles like jelly, worst at a low base frame rate and during a fast turn,
 * where the interpolation error is largest. While {@link #setFrameGeneration frame
 * generation} is on, the physical fraction is therefore scaled down by
 * {@link #fgExposureScale} and capped at one real frame, then eased back as the base frame
 * rate recovers and the camera settles. The scale shrinks fast and recovers slowly, so a
 * flick is caught at once and the blur does not pulse back. Quality, shutter, and maximum
 * blur, which the player chose, are never rewritten by the adaptation. With frame generation
 * off, every write is exactly what it was without the adaptation.
 * {@code -Drtxremix.motionBlur.fgAdaptive=false} turns the adaptation off.
 */
public final class CameraEffectsSettings {

    /** Motion blur quality steps, as the integer setting stores them. */
    public static final int OFF = 0;
    public static final int STANDARD = 1;
    public static final int HIGH = 2;
    public static final int CINEMATIC = 3;
    public static final int ULTRA = 4;

    public static final int DEFAULT_QUALITY = OFF;
    /** Shutter time in milliseconds: 1/48 s, a film camera's 180-degree shutter at 24 fps. */
    public static final float DEFAULT_SHUTTER_MS = 1000.0f / 48.0f;
    /** 1/250 s. */
    public static final float MIN_SHUTTER_MS = 4.0f;
    /** 1/24 s, a full-open shutter at film frame rate. */
    public static final float MAX_SHUTTER_MS = 1000.0f / 24.0f;
    public static final float DEFAULT_MAX_BLUR = 0.06f;
    public static final float MIN_MAX_BLUR = 0.01f;
    public static final float MAX_MAX_BLUR = 0.25f;

    /** Bounds on the per-frame fraction written to Remix; its own UI allows 0.01 to 3. */
    static final float MIN_EXPOSURE_FRACTION = 0.05f;
    static final float MAX_EXPOSURE_FRACTION = 3.0f;
    /** Relative change of the fraction that is written; smaller drifts are not worth a config write. */
    static final float EXPOSURE_WRITE_THRESHOLD = 0.02f;
    /** Weight of the newest frame in the smoothed frame time. */
    static final float FRAME_TIME_SMOOTHING = 0.1f;
    /** Frame time assumed before any frame is measured. */
    static final float INITIAL_FRAME_SECONDS = 1.0f / 60.0f;
    /** Frame times outside this range (a hitch, a loading screen, a pause) are not averaged in. */
    static final float MIN_FRAME_SECONDS = 1.0f / 1000.0f;
    static final float MAX_FRAME_SECONDS = 0.25f;

    /**
     * Whether motion blur adapts to frame generation. Read once: it is a diagnostic switch for
     * comparing against the unadapted blur, not a setting.
     */
    static final boolean FG_ADAPTIVE_ENABLED =
            Boolean.parseBoolean(System.getProperty("rtxremix.motionBlur.fgAdaptive", "true"));
    /**
     * Jitter strength while frame generation is on. Jitter scatters the blur samples along the
     * streak, which the interpolator sees as noise that moves between real frames and smears
     * into the generated one. Noise sampling itself stays on: without it the two sample walkers
     * degenerate and the streak is drawn to one side only.
     */
    static final float FG_JITTER_STRENGTH = 0.35f;
    /** Base frame rates between which the frame-rate scale rises from its floor to 1. */
    static final float FG_MIN_SCALE_FPS = 30.0f;
    static final float FG_FULL_SCALE_FPS = 60.0f;
    /**
     * Floor of the frame-rate scale. Blur is reduced, never switched off, because a player who
     * chose it still wants the look at a low base frame rate.
     */
    static final float FG_MIN_FPS_SCALE = 0.35f;
    /** Floor of the camera-motion scale, so a fast turn shortens the streak but keeps it. */
    static final float FG_MIN_MOTION_SCALE = 0.4f;
    /**
     * Longest camera streak, in pixels at {@link #FG_REFERENCE_VIEWPORT_WIDTH_PX}, that the
     * interpolator carries without visibly dragging it. It scales with the viewport so the
     * limit is the same share of the screen at any resolution.
     */
    static final float FG_MAX_STREAK_PX = 32.0f;
    static final float FG_REFERENCE_VIEWPORT_WIDTH_PX = 1920.0f;
    /**
     * Cap on the fraction while frame generation is on: a streak no longer than the gap between
     * two real frames, so neighbouring real frames' streaks never overlap in a generated one.
     */
    static final float FG_MAX_EXPOSURE_FRACTION = 1.0f;
    /** Time constant of the applied scale while it shrinks: a flick or a hitch is caught at once. */
    static final float FG_SCALE_ATTACK_SECONDS = 0.08f;
    /** Time constant while it recovers: slow enough that the blur eases back instead of pulsing. */
    static final float FG_SCALE_RELEASE_SECONDS = 0.6f;
    /** Time constant of the smoothed camera angular speed, which removes mouse-sample jitter. */
    static final float ANGULAR_SPEED_SMOOTHING_SECONDS = 0.03f;
    /**
     * Shortest gap between two adapted writes. The adapted fraction moves every frame during a
     * turn, and a config write per frame would cost more than the precision is worth.
     */
    static final long FG_MIN_WRITE_INTERVAL_NANOS = 50_000_000L;

    static final String ENABLE_KEY = "rtx.postfx.enable";
    static final String MOTION_BLUR_KEY = "rtx.postfx.enableMotionBlur";
    static final String CHROMATIC_ABERRATION_KEY = "rtx.postfx.enableChromaticAberration";
    static final String VIGNETTE_KEY = "rtx.postfx.enableVignette";
    static final String SAMPLE_COUNT_KEY = "rtx.postfx.motionBlurSampleCount";
    static final String NOISE_SAMPLE_KEY = "rtx.postfx.enableMotionBlurNoiseSample";
    static final String JITTER_KEY = "rtx.postfx.motionBlurJitterStrength";
    static final String EMISSIVE_KEY = "rtx.postfx.enableMotionBlurEmissive";
    static final String MIN_VELOCITY_KEY = "rtx.postfx.motionBlurMinimumVelocityThresholdInPixel";
    static final String SHUTTER_KEY = "rtx.postfx.exposureFraction";
    static final String MAX_BLUR_KEY = "rtx.postfx.blurDiameterFraction";

    private final BiConsumer<String, String> configWriter;
    private final boolean fgAdaptive;

    private int quality = DEFAULT_QUALITY;
    private float shutterMs = DEFAULT_SHUTTER_MS;
    private float maxBlur = DEFAULT_MAX_BLUR;

    private float smoothedFrameSeconds = INITIAL_FRAME_SECONDS;
    private long lastFrameNanos;
    private boolean hasLastFrame;
    /** Fraction last written to Remix, or a negative value when none was. */
    private float writtenExposureFraction = -1.0f;

    /** Whether Remix's frame generation was last seen on. */
    private boolean frameGeneration;
    /** Applied frame-generation scale, eased toward its target; negative until the first frame. */
    private float fgScale = -1.0f;
    /** Clock reading of the last adapted write, valid while {@link #adaptedWriteTimed}. */
    private long lastAdaptedWriteNanos;
    private boolean adaptedWriteTimed;
    /**
     * Whether the last frame had a fixed frame time, such as a video export's. An export is
     * not frame generated at the rate it renders, so it bypasses the whole adaptation, the
     * jitter included.
     */
    private boolean fixedFrameTime;
    private float lastYawDegrees;
    private float lastPitchDegrees;
    private boolean hasLastAngles;
    private float angularSpeedDegreesPerSecond;

    public CameraEffectsSettings(BiConsumer<String, String> configWriter) {
        this(configWriter, FG_ADAPTIVE_ENABLED);
    }

    /** Lets a test choose the adaptation without a system property. */
    CameraEffectsSettings(BiConsumer<String, String> configWriter, boolean fgAdaptive) {
        this.configWriter = configWriter;
        this.fgAdaptive = fgAdaptive;
    }

    public int getQuality() {
        return quality;
    }

    public void setQuality(int value) {
        quality = Math.max(OFF, Math.min(ULTRA, value));
        apply();
    }

    public float getShutterMs() {
        return shutterMs;
    }

    public void setShutterMs(float value) {
        shutterMs = Math.max(MIN_SHUTTER_MS, Math.min(MAX_SHUTTER_MS, value));
        apply();
    }

    public float getMaxBlur() {
        return maxBlur;
    }

    public void setMaxBlur(float value) {
        maxBlur = Math.max(MIN_MAX_BLUR, Math.min(MAX_MAX_BLUR, value));
        apply();
    }

    /** Writes the current state again, for after something else rewrote the post-effect options. */
    public void reapply() {
        apply();
    }

    public boolean isFrameGeneration() {
        return frameGeneration;
    }

    /**
     * Records whether Remix's frame generation is on. Only a change does anything: the adapted
     * scale restarts from its target, and while blur is on the jitter strength is rewritten for
     * the new state. Repeating the current state writes nothing, so a caller may report the
     * state every frame.
     */
    public void setFrameGeneration(boolean enabled) {
        if (enabled == frameGeneration) {
            return;
        }
        frameGeneration = enabled;
        fgScale = -1.0f;
        adaptedWriteTimed = false;
        if (!fgAdaptive || quality == OFF) {
            return;
        }
        configWriter.accept(JITTER_KEY, Float.toString(jitterStrength()));
    }

    /**
     * {@link #onFrame(long, float, float, float, float, int)} without a camera, so frame
     * generation adapts to the base frame rate only.
     */
    public void onFrame(long nowNanos, float fixedFrameSeconds) {
        onFrame(nowNanos, fixedFrameSeconds, Float.NaN, Float.NaN, 0.0f, 0);
    }

    /**
     * Folds this frame's time into the smoothed frame time and, while blur is on, updates
     * Remix's exposure fraction when it moved enough to matter. Call once per rendered frame.
     * Allocates nothing unless it writes.
     *
     * @param fixedFrameSeconds a frame time to use as is instead of the measured one, such as
     *     the frame time of a video export that renders far slower than it plays; 0 to measure.
     *     A fixed frame time also bypasses the frame-generation adaptation, jitter included,
     *     because an export is not frame generated at the rate it renders.
     * @param yawDegrees the rendered camera's yaw, unwrapped or wrapped; NaN when unknown
     * @param pitchDegrees the rendered camera's pitch; NaN when unknown
     * @param horizontalFovDegrees the world camera's horizontal field of view; 0 when unknown
     * @param viewportWidthPx the rendered width in pixels; 0 when unknown
     */
    public void onFrame(long nowNanos, float fixedFrameSeconds, float yawDegrees, float pitchDegrees,
                        float horizontalFovDegrees, int viewportWidthPx) {
        // The measured gap when it is a normal frame, else -1; and the same gap clamped for
        // easing, so a hitch still advances the ease by a bounded step.
        float measuredSeconds = -1.0f;
        float stepSeconds = 0.0f;
        if (fixedFrameSeconds > 0.0f) {
            smoothedFrameSeconds = fixedFrameSeconds;
        } else if (hasLastFrame) {
            float seconds = (nowNanos - lastFrameNanos) * 1.0e-9f;
            if (seconds >= MIN_FRAME_SECONDS && seconds <= MAX_FRAME_SECONDS) {
                smoothedFrameSeconds += (seconds - smoothedFrameSeconds) * FRAME_TIME_SMOOTHING;
                measuredSeconds = seconds;
            }
            stepSeconds = Math.max(0.0f, Math.min(MAX_FRAME_SECONDS, seconds));
        }
        lastFrameNanos = nowNanos;
        hasLastFrame = true;
        trackAngularSpeed(yawDegrees, pitchDegrees, measuredSeconds);
        boolean fixed = fixedFrameSeconds > 0.0f;
        if (fixed != fixedFrameTime) {
            // Entering or leaving an export flips the jitter as a frame-generation change
            // does, and the scale restarts from its target rather than easing from a value
            // left over from before the export.
            fixedFrameTime = fixed;
            fgScale = -1.0f;
            adaptedWriteTimed = false;
            if (fgAdaptive && frameGeneration && quality != OFF) {
                configWriter.accept(JITTER_KEY, Float.toString(jitterStrength()));
            }
        }
        if (quality == OFF) {
            return;
        }
        float physical = exposureFraction(shutterMs, smoothedFrameSeconds);
        if (!isAdapting()) {
            if (writtenExposureFraction < 0.0f
                    || Math.abs(physical - writtenExposureFraction)
                            > EXPOSURE_WRITE_THRESHOLD * writtenExposureFraction) {
                writeExposureFraction(physical);
            }
            return;
        }

        // Above this scale the one-frame cap, not the scale, decides the fraction. Holding
        // both the target and the applied scale under it keeps the ease out of that dead zone:
        // otherwise, at a base rate where the physical fraction is well above one frame, a
        // flick would first have to ease the scale down to the cap before the streak moved,
        // turning the quick attack into a sluggish one. Clamping changes no fraction.
        float ceiling = FG_MAX_EXPOSURE_FRACTION / physical;
        float target = Math.min(ceiling, fgExposureScale(1.0f / smoothedFrameSeconds,
                angularSpeedDegreesPerSecond, smoothedFrameSeconds, viewportWidthPx,
                horizontalFovDegrees, physical));
        fgScale = fgScale < 0.0f ? target : easeFgScale(Math.min(ceiling, fgScale), target, stepSeconds);
        float fraction = adaptedExposureFraction(physical, fgScale);
        if (writtenExposureFraction < 0.0f) {
            writeAdaptedExposureFraction(fraction, nowNanos);
            return;
        }
        if (Math.abs(fraction - writtenExposureFraction)
                <= EXPOSURE_WRITE_THRESHOLD * writtenExposureFraction) {
            return;
        }
        long sinceLastWrite = nowNanos - lastAdaptedWriteNanos;
        // A clock that went backwards, such as the switch from an export's clock to the wall
        // clock, must not hold writes back until it catches up.
        if (!adaptedWriteTimed || sinceLastWrite < 0L || sinceLastWrite >= FG_MIN_WRITE_INTERVAL_NANOS) {
            writeAdaptedExposureFraction(fraction, nowNanos);
        }
    }

    /** Share of a frame the shutter stays open for, given its time and the frame time. */
    static float exposureFraction(float shutterMs, float frameSeconds) {
        float fraction = shutterMs * 1.0e-3f / Math.max(MIN_FRAME_SECONDS, frameSeconds);
        return Math.max(MIN_EXPOSURE_FRACTION, Math.min(MAX_EXPOSURE_FRACTION, fraction));
    }

    /**
     * Scale on the physical exposure fraction while frame generation is on: the smaller of a
     * frame-rate scale and a camera-motion scale.
     *
     * <p>The frame-rate scale rises smoothly from {@link #FG_MIN_FPS_SCALE} at
     * {@link #FG_MIN_SCALE_FPS} to 1 at {@link #FG_FULL_SCALE_FPS}, because the interpolation
     * error the streak magnifies grows as the gap between real frames does. The motion scale
     * estimates the camera streak in pixels, angular speed times the shutter's share of the
     * frame times pixels per degree, and shortens it to {@link #FG_MAX_STREAK_PX} (scaled to the
     * viewport) but no further than {@link #FG_MIN_MOTION_SCALE}. Unknown motion, viewport or
     * field of view leaves the motion scale at 1.
     *
     * @param baseFps the real, not generated, frame rate
     * @param realFrameSeconds the real frame time the physical fraction was computed from
     */
    static float fgExposureScale(float baseFps, float angularSpeedDegreesPerSecond, float realFrameSeconds,
                                 float viewportWidthPx, float horizontalFovDegrees, float physicalFraction) {
        float fpsScale = FG_MIN_FPS_SCALE
                + (1.0f - FG_MIN_FPS_SCALE) * smoothstep(FG_MIN_SCALE_FPS, FG_FULL_SCALE_FPS, baseFps);
        float motionScale = 1.0f;
        if (viewportWidthPx > 0.0f && horizontalFovDegrees > 0.0f && angularSpeedDegreesPerSecond > 0.0f) {
            float streakPx = angularSpeedDegreesPerSecond * realFrameSeconds
                    * (viewportWidthPx / horizontalFovDegrees) * physicalFraction;
            float maxStreakPx = FG_MAX_STREAK_PX * viewportWidthPx / FG_REFERENCE_VIEWPORT_WIDTH_PX;
            if (streakPx > maxStreakPx) {
                motionScale = Math.max(FG_MIN_MOTION_SCALE, maxStreakPx / streakPx);
            }
        }
        return Math.min(fpsScale, motionScale);
    }

    /**
     * Eases the applied scale toward its target over {@code seconds}: quickly while it shrinks,
     * slowly while it recovers.
     */
    static float easeFgScale(float current, float target, float seconds) {
        float timeConstant = target < current ? FG_SCALE_ATTACK_SECONDS : FG_SCALE_RELEASE_SECONDS;
        float weight = 1.0f - (float) Math.exp(-Math.max(0.0f, seconds) / timeConstant);
        return current + (target - current) * weight;
    }

    /** The physical fraction scaled for frame generation, within one real frame. */
    static float adaptedExposureFraction(float physicalFraction, float scale) {
        return Math.max(MIN_EXPOSURE_FRACTION, Math.min(FG_MAX_EXPOSURE_FRACTION, physicalFraction * scale));
    }

    /** The applied frame-generation scale, or a negative value before it has a target. */
    float fgScale() {
        return fgScale;
    }

    /** The smoothed camera angular speed, in degrees per second. */
    float angularSpeedDegreesPerSecond() {
        return angularSpeedDegreesPerSecond;
    }

    private boolean isAdapting() {
        return fgAdaptive && frameGeneration && !fixedFrameTime;
    }

    private float jitterStrength() {
        if (isAdapting()) {
            return FG_JITTER_STRENGTH;
        }
        return quality >= CINEMATIC ? 0.8f : 0.6f;
    }

    /**
     * Folds this frame's camera rotation into the smoothed angular speed. Only a normally timed
     * frame contributes: a hitch would divide a turn by a long gap and report a slow camera.
     */
    private void trackAngularSpeed(float yawDegrees, float pitchDegrees, float seconds) {
        if (Float.isNaN(yawDegrees) || Float.isNaN(pitchDegrees)) {
            hasLastAngles = false;
            angularSpeedDegreesPerSecond = 0.0f;
            return;
        }
        if (hasLastAngles && seconds > 0.0f) {
            float yawDelta = wrapDegrees(yawDegrees - lastYawDegrees);
            float pitchDelta = pitchDegrees - lastPitchDegrees;
            float speed = (float) Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta) / seconds;
            float weight = 1.0f - (float) Math.exp(-seconds / ANGULAR_SPEED_SMOOTHING_SECONDS);
            angularSpeedDegreesPerSecond += (speed - angularSpeedDegreesPerSecond) * weight;
        }
        lastYawDegrees = yawDegrees;
        lastPitchDegrees = pitchDegrees;
        hasLastAngles = true;
    }

    /** Wraps an angle difference into [-180, 180], because Minecraft's yaw is not wrapped. */
    static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped > 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private void apply() {
        boolean on = quality != OFF;
        write(ENABLE_KEY, on);
        write(MOTION_BLUR_KEY, on);
        write(CHROMATIC_ABERRATION_KEY, false);
        write(VIGNETTE_KEY, false);
        if (!on) {
            writtenExposureFraction = -1.0f;
            return;
        }
        configWriter.accept(SAMPLE_COUNT_KEY, Integer.toString(sampleCount(quality)));
        // Without noise sampling Remix's two sample walkers degenerate: one takes every full
        // step and the other only the minimum, so the streak is drawn to one side only.
        write(NOISE_SAMPLE_KEY, true);
        configWriter.accept(JITTER_KEY, Float.toString(jitterStrength()));
        write(EMISSIVE_KEY, quality >= HIGH);
        configWriter.accept(MIN_VELOCITY_KEY, Float.toString(1.0f));
        configWriter.accept(MAX_BLUR_KEY, Float.toString(maxBlur));
        float physical = exposureFraction(shutterMs, smoothedFrameSeconds);
        writeExposureFraction(isAdapting()
                ? adaptedExposureFraction(physical, fgScale < 0.0f ? 1.0f : fgScale)
                : physical);
    }

    private void writeAdaptedExposureFraction(float fraction, long nowNanos) {
        writeExposureFraction(fraction);
        lastAdaptedWriteNanos = nowNanos;
        adaptedWriteTimed = true;
    }

    private void writeExposureFraction(float fraction) {
        configWriter.accept(SHUTTER_KEY, Float.toString(fraction));
        writtenExposureFraction = fraction;
    }

    static int sampleCount(int quality) {
        return switch (quality) {
            case STANDARD -> 8;
            case HIGH -> 16;
            case CINEMATIC -> 32;
            case ULTRA -> 48;
            default -> 0;
        };
    }

    private void write(String key, boolean value) {
        configWriter.accept(key, Boolean.toString(value));
    }
}
