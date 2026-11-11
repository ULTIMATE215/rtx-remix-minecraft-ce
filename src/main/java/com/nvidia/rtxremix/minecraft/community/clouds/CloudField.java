/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.clouds;

/**
 * The volumetric cloud density, a pure function of a position in the drifting cloud frame
 * and a few weather parameters. {@code shaders/cloud_sky.comp} implements the same function
 * line for line ({@code cloudDensity}); a change here must be made there too.
 *
 * <p>Distances are blocks (metres). Field coordinates are world coordinates minus the wind
 * offset, wrapped to {@link #WEATHER_PERIOD}, which every other period divides, so the GPU
 * can work in single precision and both sides land on the same texels.
 */
public final class CloudField {

    /** Period of the base shape volume, 8 blocks per texel: puffs 75–250 blocks across. */
    public static final double BASE_PERIOD = 1024.0;
    /** Period of the erosion detail volume, 8 blocks per texel. */
    public static final double DETAIL_PERIOD = 256.0;
    /** Period of the weather map, 32 blocks per texel. */
    public static final double WEATHER_PERIOD = 16384.0;
    /**
     * Cloud base above vanilla's cloud plane (192 in the Overworld), so the base sits near
     * y 400: about 330 blocks over the sea, where clouds read as weather far overhead rather
     * than as fog the treetops nearly touch.
     */
    public static final float BASE_ABOVE_VANILLA = 208.0f;
    /** Tallest cloud a cumulonimbus reaches above its base. */
    public static final float MAX_THICKNESS = 1500.0f;
    /** Wind, blocks per game tick (about 5 m/s from the west-southwest). */
    public static final double WIND_X_PER_TICK = 0.24;
    public static final double WIND_Z_PER_TICK = 0.07;
    /**
     * Period of the cirrus sheet's field, which every cirrus scale in {@code cloud_sky.comp}
     * divides; far longer than {@link #WEATHER_PERIOD}, so the sheets can be tens of km wide.
     */
    public static final double CIRRUS_PERIOD = 1 << 20;
    /** The jet stream carries the cirrus faster than the cumulus below, along the same heading. */
    public static final double CIRRUS_WIND_SCALE = 1.6;
    /** Default sky cover setting, which maps to no coverage bias. */
    public static final float DEFAULT_COVERAGE = 0.45f;

    private CloudField() {}

    /**
     * Weather inputs of the density function.
     *
     * @param base cloud base altitude, world Y
     * @param coverageBias added to the weather map's coverage
     * @param typeBias added to the weather map's cloud type (thunder pushes toward cumulonimbus)
     * @param rain rain strength, 0..1, which flattens convective tops
     */
    public record Params(float base, float coverageBias, float typeBias, float rain) {

        /** The default weather at the default cloud base, for state before the first frame. */
        public static Params defaults() {
            return of(192.0f + BASE_ABOVE_VANILLA, DEFAULT_COVERAGE, 0.0f, 0.0f);
        }

        public static Params of(float base, float coverageSetting, float rain, float thunder) {
            return of(base, coverageSetting, rain, thunder, 0.0f);
        }

        /**
         * @param night 0 by day, 1 at night: a clear night keeps only a few scattered clouds, so
         *              they do not hide the moon and stars and darken the whole scene
         */
        public static Params of(float base, float coverageSetting, float rain, float thunder, float night) {
            float r = clamp01(rain);
            float t = clamp01(thunder);
            // The default setting scatters separate cumulus patches over about a third of the
            // sky; 0.15 leaves a few small clouds and 0.85 is nearly overcast.
            float bias = (coverageSetting - DEFAULT_COVERAGE) * 0.9f - 0.18f + 0.51f * r + 0.15f * t
                    - 0.17f * clamp01(night) * (1.0f - r);
            return new Params(base, bias, 0.85f * t, r);
        }

        /**
         * The same weather in steps of 1/32, so a slow weather change rebuilds the near
         * meshes a few dozen times rather than every frame, and both layers read equal values.
         */
        public Params quantized() {
            return new Params(Math.round(base * 4.0f) / 4.0f, step(coverageBias), step(typeBias), step(rain));
        }

        private static float step(float value) {
            return Math.round(value * 32.0f) / 32.0f;
        }
    }

    /** Thickness of a cloud of the given type: thin stratocumulus, cumulus, towering cumulonimbus. */
    public static float thickness(float type) {
        return lerp(140.0f, 600.0f, smoothstep(0.1f, 0.6f, type))
                + 900.0f * smoothstep(0.72f, 1.0f, type);
    }

    /**
     * Density at a field position, 0 for clear air, about 1 in a cloud's core.
     *
     * @param fieldX field X, any value (it is wrapped)
     * @param y world Y
     * @param fieldZ field Z, any value (it is wrapped)
     * @param withDetail whether to apply the erosion detail
     */
    public static float density(CloudNoise noise, double fieldX, double y, double fieldZ,
                                Params params, boolean withDetail, float[] scratch) {
        sample2D(noise.weather, CloudNoise.WEATHER_SIZE,
                fieldX / WEATHER_PERIOD, fieldZ / WEATHER_PERIOD, scratch);
        float coverage = clamp01(scratch[0] + params.coverageBias());
        if (coverage <= 0.0f) {
            return 0.0f;
        }
        float type = clamp01(scratch[1] * (1.0f - 0.4f * params.rain()) + params.typeBias());
        float thickness = thickness(type);
        float heightFraction = (float) ((y - params.base()) / thickness);
        if (heightFraction <= 0.0f || heightFraction >= 1.0f) {
            return 0.0f;
        }
        return densityAt(noise, fieldX, y, fieldZ, coverage, type, heightFraction, withDetail, scratch);
    }

    /** The column part of {@link #density}: coverage and type from the weather map, or -1 coverage when clear. */
    public static void column(CloudNoise noise, double fieldX, double fieldZ, Params params, float[] out) {
        sample2D(noise.weather, CloudNoise.WEATHER_SIZE,
                fieldX / WEATHER_PERIOD, fieldZ / WEATHER_PERIOD, out);
        float coverage = clamp01(out[0] + params.coverageBias());
        float type = clamp01(out[1] * (1.0f - 0.4f * params.rain()) + params.typeBias());
        out[0] = coverage;
        out[1] = type;
        out[2] = thickness(type);
    }

    /** {@link #density} for a column whose coverage, type, and height fraction are known. */
    public static float densityAt(CloudNoise noise, double fieldX, double y, double fieldZ,
                                  float coverage, float type, float heightFraction,
                                  boolean withDetail, float[] scratch) {
        sample3D(noise.base, CloudNoise.BASE_SIZE,
                fieldX / BASE_PERIOD, y / BASE_PERIOD, fieldZ / BASE_PERIOD, scratch);
        float lowFrequency = scratch[1] * 0.625f + scratch[2] * 0.25f + scratch[3] * 0.125f;
        float shape = clamp01(remap(scratch[0], lowFrequency - 1.0f, 1.0f, 0.0f, 1.0f));
        float topFade = lerp(0.3f, 0.75f, type);
        float profile = smoothstep(0.0f, 0.1f, heightFraction)
                * (1.0f - smoothstep(topFade, 1.0f, heightFraction));
        shape *= profile;
        // Convective clouds narrow toward their tops; a cumulonimbus spreads into an anvil.
        float anvil = smoothstep(0.75f, 1.0f, type)
                * smoothstep(0.8f, 0.9f, heightFraction)
                * (1.0f - smoothstep(0.95f, 1.0f, heightFraction));
        float localCoverage = clamp01(coverage * (1.0f - 0.35f * heightFraction * (1.0f - type))
                + 0.5f * anvil * coverage);
        float density = clamp01(remap(shape, 1.0f - localCoverage, 1.0f, 0.0f, 1.0f)) * localCoverage;
        if (density <= 0.0f || !withDetail) {
            return density;
        }
        sample3D(noise.detail, CloudNoise.DETAIL_SIZE,
                fieldX / DETAIL_PERIOD, y / DETAIL_PERIOD, fieldZ / DETAIL_PERIOD, scratch);
        float detail = scratch[0] * 0.625f + scratch[1] * 0.25f + scratch[2] * 0.125f;
        // Wispy at the base, billowy at the top.
        float erosion = lerp(detail, 1.0f - detail, clamp01(heightFraction * 4.0f));
        return clamp01(remap(density, erosion * 0.35f, 1.0f, 0.0f, 1.0f));
    }

    /** Field coordinate of a world coordinate at a world time: world minus wind, wrapped. */
    public static double fieldX(double worldX, double worldTime) {
        return wrap(worldX - worldTime * WIND_X_PER_TICK);
    }

    public static double fieldZ(double worldZ, double worldTime) {
        return wrap(worldZ - worldTime * WIND_Z_PER_TICK);
    }

    /** Cirrus field coordinate of a world coordinate at a world time, wrapped to {@link #CIRRUS_PERIOD}. */
    public static double cirrusX(double worldX, double worldTime) {
        return wrap(worldX - worldTime * WIND_X_PER_TICK * CIRRUS_WIND_SCALE, CIRRUS_PERIOD);
    }

    public static double cirrusZ(double worldZ, double worldTime) {
        return wrap(worldZ - worldTime * WIND_Z_PER_TICK * CIRRUS_WIND_SCALE, CIRRUS_PERIOD);
    }

    static double wrap(double value, double period) {
        double wrapped = value % period;
        return wrapped < 0.0 ? wrapped + period : wrapped;
    }

    public static double wrap(double value) {
        double wrapped = value % WEATHER_PERIOD;
        return wrapped < 0.0 ? wrapped + WEATHER_PERIOD : wrapped;
    }

    // ------------------------------------------------------------------
    //  Texture sampling, linear filtering with repeat addressing
    // ------------------------------------------------------------------

    static void sample2D(byte[] texture, int size, double u, double v, float[] out) {
        double x = u * size - 0.5;
        double y = v * size - 0.5;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = (float) (x - x0);
        float fy = (float) (y - y0);
        int xa = Math.floorMod(x0, size);
        int xb = (xa + 1) % size;
        int ya = Math.floorMod(y0, size);
        int yb = (ya + 1) % size;
        for (int channel = 0; channel < 4; channel++) {
            float c00 = texel(texture, (ya * size + xa) * 4 + channel);
            float c10 = texel(texture, (ya * size + xb) * 4 + channel);
            float c01 = texel(texture, (yb * size + xa) * 4 + channel);
            float c11 = texel(texture, (yb * size + xb) * 4 + channel);
            out[channel] = lerp(lerp(c00, c10, fx), lerp(c01, c11, fx), fy);
        }
    }

    static void sample3D(byte[] texture, int size, double u, double v, double w, float[] out) {
        double x = u * size - 0.5;
        double y = v * size - 0.5;
        double z = w * size - 0.5;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        float fx = (float) (x - x0);
        float fy = (float) (y - y0);
        float fz = (float) (z - z0);
        int xa = Math.floorMod(x0, size);
        int xb = (xa + 1) % size;
        int ya = Math.floorMod(y0, size);
        int yb = (ya + 1) % size;
        int za = Math.floorMod(z0, size);
        int zb = (za + 1) % size;
        int i000 = ((za * size + ya) * size + xa) * 4;
        int i100 = ((za * size + ya) * size + xb) * 4;
        int i010 = ((za * size + yb) * size + xa) * 4;
        int i110 = ((za * size + yb) * size + xb) * 4;
        int i001 = ((zb * size + ya) * size + xa) * 4;
        int i101 = ((zb * size + ya) * size + xb) * 4;
        int i011 = ((zb * size + yb) * size + xa) * 4;
        int i111 = ((zb * size + yb) * size + xb) * 4;
        for (int channel = 0; channel < 4; channel++) {
            float c00 = lerp(texel(texture, i000 + channel), texel(texture, i100 + channel), fx);
            float c10 = lerp(texel(texture, i010 + channel), texel(texture, i110 + channel), fx);
            float c01 = lerp(texel(texture, i001 + channel), texel(texture, i101 + channel), fx);
            float c11 = lerp(texel(texture, i011 + channel), texel(texture, i111 + channel), fx);
            out[channel] = lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
        }
    }

    private static float texel(byte[] texture, int index) {
        return (texture[index] & 0xff) / 255.0f;
    }

    static float remap(float value, float low, float high, float newLow, float newHigh) {
        return newLow + (value - low) / Math.max(high - low, 1.0e-4f) * (newHigh - newLow);
    }

    static float smoothstep(float edge0, float edge1, float value) {
        float t = clamp01((value - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }
}
