/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.clouds;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * The tileable noise volumes the volumetric clouds are carved from, generated once and
 * deterministically, so the near meshes (CPU) and the far sky (GPU, uploaded as textures)
 * read the very same bytes.
 *
 * <ul>
 *   <li>{@link #BASE_SIZE}³ RGBA8 base shape: R is Perlin-Worley, G/B/A inverted Worley
 *       fBm at 4, 8 and 16 cells per tile.</li>
 *   <li>{@link #DETAIL_SIZE}³ RGBA8 erosion detail: R/G/B inverted Worley fBm at 2, 4 and 8
 *       cells per tile.</li>
 *   <li>{@link #WEATHER_SIZE}² RGBA8 weather map: R coverage, G cloud type (0 stratocumulus,
 *       0.5 cumulus, 1 cumulonimbus), B ridged cirrus filaments, A smooth single-octave
 *       Perlin noise the cirrus sheet layers into fibres (B and A: far layer only).</li>
 * </ul>
 *
 * <p>Layout is x fastest, then y, then z, four bytes per texel, which is what
 * Vulkan RGBA8 images expect for tightly packed texels.
 */
public final class CloudNoise {

    public static final int BASE_SIZE = 128;
    public static final int DETAIL_SIZE = 32;
    public static final int WEATHER_SIZE = 512;
    static final int SEED = 0x5C1E_C10D;

    private static volatile CompletableFuture<CloudNoise> pending;

    final byte[] base;
    final byte[] detail;
    final byte[] weather;

    CloudNoise(byte[] base, byte[] detail, byte[] weather) {
        this.base = base;
        this.detail = detail;
        this.weather = weather;
    }

    /** The shared volumes, generated on a worker the first time anyone asks. */
    public static CompletableFuture<CloudNoise> shared() {
        CompletableFuture<CloudNoise> future = pending;
        if (future == null) {
            synchronized (CloudNoise.class) {
                future = pending;
                if (future == null) {
                    future = CompletableFuture.supplyAsync(CloudNoise::generate);
                    pending = future;
                }
            }
        }
        return future;
    }

    /** The shared volumes if they are ready, else null without blocking. */
    public static CloudNoise sharedIfReady() {
        CompletableFuture<CloudNoise> future = shared();
        return future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    public byte[] base() { return base; }
    public byte[] detail() { return detail; }
    public byte[] weather() { return weather; }

    static CloudNoise generate() {
        return new CloudNoise(generateBase(), generateDetail(), generateWeather());
    }

    static byte[] generateBase() {
        int n = BASE_SIZE;
        byte[] out = new byte[n * n * n * 4];
        IntStream.range(0, n).parallel().forEach(z -> {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double u = (x + 0.5) / n;
                    double v = (y + 0.5) / n;
                    double w = (z + 0.5) / n;
                    double perlin = 0.0;
                    double amplitude = 1.0;
                    double total = 0.0;
                    for (int octave = 0; octave < 3; octave++) {
                        int period = 4 << octave;
                        perlin += amplitude * perlin(u * period, v * period, w * period, period, SEED + octave);
                        total += amplitude;
                        amplitude *= 0.5;
                    }
                    perlin = clamp01(perlin / total * 0.75 + 0.5);
                    double worley4 = worleyFbm(u, v, w, 4, SEED + 11);
                    double worley8 = worleyFbm(u, v, w, 8, SEED + 23);
                    double worley16 = worleyFbm(u, v, w, 16, SEED + 37);
                    // Perlin-Worley (Schneider 2015): billowy Perlin, its valleys cut by Worley cells.
                    double perlinWorley = clamp01(remap(perlin, worley4 - 1.0, 1.0, 0.0, 1.0));
                    int index = ((z * n + y) * n + x) * 4;
                    out[index] = toByte(perlinWorley);
                    out[index + 1] = toByte(worley4);
                    out[index + 2] = toByte(worley8);
                    out[index + 3] = toByte(worley16);
                }
            }
        });
        return out;
    }

    static byte[] generateDetail() {
        int n = DETAIL_SIZE;
        byte[] out = new byte[n * n * n * 4];
        IntStream.range(0, n).parallel().forEach(z -> {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double u = (x + 0.5) / n;
                    double v = (y + 0.5) / n;
                    double w = (z + 0.5) / n;
                    int index = ((z * n + y) * n + x) * 4;
                    out[index] = toByte(worleyFbm(u, v, w, 2, SEED + 101));
                    out[index + 1] = toByte(worleyFbm(u, v, w, 4, SEED + 113));
                    out[index + 2] = toByte(worleyFbm(u, v, w, 8, SEED + 127));
                    out[index + 3] = (byte) 0xff;
                }
            }
        });
        return out;
    }

    static byte[] generateWeather() {
        int n = WEATHER_SIZE;
        byte[] out = new byte[n * n * 4];
        IntStream.range(0, n).parallel().forEach(y -> {
            for (int x = 0; x < n; x++) {
                double u = (x + 0.5) / n;
                double v = (y + 0.5) / n;
                double coverageNoise = 0.0;
                double amplitude = 1.0;
                double total = 0.0;
                for (int octave = 0; octave < 4; octave++) {
                    int period = 12 << octave;
                    coverageNoise += amplitude * perlin(u * period, v * period, 0.37, period, SEED + 211 + octave);
                    total += amplitude;
                    amplitude *= 0.5;
                }
                coverageNoise = clamp01(coverageNoise / total * 0.8 + 0.5);
                // Inverted Worley breaks the cover into separate patches about 500 blocks across.
                double clumps = worley(u, v, 0.5, 32, SEED + 223, true);
                double coverage = clamp01((0.55 * coverageNoise + 0.45 * clumps) * 1.4 - 0.28);

                double typeNoise = 0.6 * perlin(u * 2, v * 2, 0.71, 2, SEED + 241)
                        + 0.4 * perlin(u * 4, v * 4, 0.13, 4, SEED + 251);
                double type = clamp01(typeNoise * 0.9 + 0.5);

                // Ridged fBm: thin bright filaments for the cirrus sheet, which the far layer
                // stretches across the wind.
                double cirrus = 0.0;
                double cirrusAmplitude = 1.0;
                double cirrusTotal = 0.0;
                for (int octave = 0; octave < 4; octave++) {
                    int period = 8 << octave;
                    double ridge = 1.0 - Math.abs(perlin(u * period, v * period, 0.53, period, SEED + 271 + octave));
                    cirrus += cirrusAmplitude * ridge * ridge;
                    cirrusTotal += cirrusAmplitude;
                    cirrusAmplitude *= 0.5;
                }
                double cirrusFields = clamp01(perlin(u * 3, v * 3, 0.29, 3, SEED + 281) * 1.2 + 0.5);
                cirrus = clamp01(cirrus / cirrusTotal * (0.35 + 0.9 * cirrusFields));

                // Plain gradient noise, one octave of 16 cells over 32 texels each: smooth enough
                // to be stretched and layered at any scale, which the cirrus sheet does to draw
                // long, thin fibres without the texel steps of the channels above.
                double fibre = clamp01(perlin(u * 16, v * 16, 0.61, 16, SEED + 291) * 0.7 + 0.5);

                int index = (y * n + x) * 4;
                out[index] = toByte(coverage);
                out[index + 1] = toByte(type);
                out[index + 2] = toByte(cirrus);
                out[index + 3] = toByte(fibre);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------
    //  Tileable noise primitives
    // ------------------------------------------------------------------

    /** Inverted three-octave Worley fBm, 1 at feature points, tileable at {@code cells} cells per unit. */
    static double worleyFbm(double u, double v, double w, int cells, int seed) {
        return worley(u, v, w, cells, seed, false) * 0.625
                + worley(u, v, w, cells * 2, seed + 1, false) * 0.25
                + worley(u, v, w, cells * 4, seed + 2, false) * 0.125;
    }

    /**
     * Inverted Worley noise over the unit cube (or square when {@code flat}), {@code cells}
     * cells per unit, wrapped so it tiles.
     */
    static double worley(double u, double v, double w, int cells, int seed, boolean flat) {
        double x = u * cells;
        double y = v * cells;
        double z = flat ? 0.0 : w * cells;
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        double best = Double.MAX_VALUE;
        int zRange = flat ? 0 : 1;
        for (int dz = -zRange; dz <= zRange; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx;
                    int ny = cy + dy;
                    int nz = cz + dz;
                    int wx = Math.floorMod(nx, cells);
                    int wy = Math.floorMod(ny, cells);
                    int wz = flat ? 0 : Math.floorMod(nz, cells);
                    int h = hash(wx, wy, wz, seed);
                    double px = nx + unit(h);
                    double py = ny + unit(hash(h, 1, 0, seed));
                    double pz = flat ? 0.0 : nz + unit(hash(h, 2, 0, seed));
                    double ddx = px - x;
                    double ddy = py - y;
                    double ddz = pz - z;
                    double distance = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (distance < best) {
                        best = distance;
                    }
                }
            }
        }
        return 1.0 - clamp01(Math.sqrt(best));
    }

    /** Gradient noise in roughly [-1, 1], tileable with the given lattice period. */
    static double perlin(double x, double y, double z, int period, int seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fy);
        double w = fade(fz);
        double result = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            int ox = corner & 1;
            int oy = (corner >> 1) & 1;
            int oz = (corner >> 2) & 1;
            int h = hash(
                    Math.floorMod(x0 + ox, period),
                    Math.floorMod(y0 + oy, period),
                    Math.floorMod(z0 + oz, period),
                    seed);
            double dot = gradient(h, fx - ox, fy - oy, fz - oz);
            double weight = (ox == 1 ? u : 1.0 - u) * (oy == 1 ? v : 1.0 - v) * (oz == 1 ? w : 1.0 - w);
            result += weight * dot;
        }
        return result;
    }

    private static double gradient(int hash, double x, double y, double z) {
        return switch (hash & 15) {
            case 0, 12 -> x + y;
            case 1, 13 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x + z;
            case 5 -> -x + z;
            case 6 -> x - z;
            case 7 -> -x - z;
            case 8 -> y + z;
            case 9, 14 -> -y + z;
            case 10 -> y - z;
            default -> -y - z;
        };
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    static int hash(int x, int y, int z, int seed) {
        int h = seed;
        h ^= x * 0x27d4eb2d;
        h = Integer.rotateLeft(h, 13) * 0x165667b1;
        h ^= y * 0x1b873593;
        h = Integer.rotateLeft(h, 15) * 0x85ebca6b;
        h ^= z * 0xc2b2ae35;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    private static double unit(int hash) {
        return (hash >>> 8) / (double) (1 << 24);
    }

    static double remap(double value, double low, double high, double newLow, double newHigh) {
        return newLow + (value - low) / Math.max(high - low, 1.0e-6) * (newHigh - newLow);
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
    }

    private static byte toByte(double value) {
        return (byte) Math.round(clamp01(value) * 255.0);
    }
}
