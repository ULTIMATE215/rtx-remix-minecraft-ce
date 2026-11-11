/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.clouds;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.community.LightningFlash;
import org.lwjgl.BufferUtils;
import com.mojang.blaze3d.buffers.GpuFence;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanImage;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanWork;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The far layer of the volumetric clouds: ray-marches the cloud field over the whole sky dome
 * with {@code shaders/cloud_sky.comp} and composites it over the atmosphere panorama, at the
 * dome resolution of the quality level, then reads the result back for the dome texture.
 *
 * <p>Each update marches a checkerboard quarter of the dome with a fresh jitter and blends it
 * into what that texel held, so four updates refresh the whole sky and noise averages out; a
 * camera jump, a time skip, or a new cloud base marches everything at once.
 *
 * <p>The image an update returns is a persistently mapped readback the caller may keep reading
 * across frames: it stays held, out of the readback ring's rotation, until the caller releases
 * it or takes a newer one, so the sky can upload a large dome a band per frame.
 *
 * <p>Render thread only. Compute, texture uploads and readbacks use Minecraft's Vulkan
 * encoder, with command-local descriptors and deferred destruction after GPU completion.
 */
public final class CloudSkyCompositor {

    private static final String SHADER_RESOURCE = "/assets/rtx-remix-renderer/shaders/cloud_sky.comp";
    private static final int PASS_CLOUD = 0;
    private static final int PASS_COMPOSE = 1;
    private static final int PASS_REDUCE = 2;
    /** Readback buffers in flight or held; the sky sizes its per-buffer band view cache by this. */
    public static final int READBACK_RING_SIZE = 3;
    private static final int UBO_BYTES = 12 * 4 * Float.BYTES;

    /** Cloud extinction per block at density 1: a dense cumulus core, about 15 m visibility. */
    static final float EXTINCTION = 0.06f;
    static final float AMBIENT_SCALE = 1.0f;
    static final float GROUND_ALBEDO = 0.25f;
    /** Haze the daylight conf layer's medium adds near the ground, per block. */
    static final float EXTRA_HAZE = 1.0f / 90000.0f;
    /** Weight of a fresh march against the texel's history; low enough to average the jitter out. */
    private static final float HISTORY_BLEND = 0.35f;

    private final int width;
    private final int height;
    private final int upperHeight;
    private final int outputBytes;
    private final int readbackBytes;
    private final VolumetricClouds.Quality quality;

    private final VulkanWork work;
    private final CloudSkyResources resources;
    private boolean closed;
    private final ByteBuffer parameters = BufferUtils.createByteBuffer(UBO_BYTES);
    private final ByteBuffer occupancyUpload =
            BufferUtils.createByteBuffer(VolumetricClouds.OCCUPANCY_SIZE * VolumetricClouds.OCCUPANCY_SIZE);
    private int uploadedOccupancyVersion = -1;

    private final GpuFence[] readbackFences = new GpuFence[READBACK_RING_SIZE];
    private final long[] readbackGenerations = new long[READBACK_RING_SIZE];
    private long generation;
    private final ReadbackRing readbackRing = new ReadbackRing(READBACK_RING_SIZE);

    private long lastUpdateTick = Long.MIN_VALUE;
    private int updateIndex;
    private int checkerPhase;
    private final CloudHistory history = new CloudHistory();
    private long lastLightningRevision = Long.MIN_VALUE;

    private final float[] occlusionScratch =
            new float[VolumetricClouds.OCCLUSION_MAP_WIDTH * VolumetricClouds.OCCLUSION_MAP_HEIGHT];

    /**
     * Lights and camera for one update; directions are world space.
     *
     * <p>Mutable and reused: the sky fills one instance per frame through {@link #set}, because
     * a fresh object every frame is garbage on the render thread for no benefit. {@link #set}
     * copies the vectors into arrays this instance owns, so a caller may keep reusing its own
     * scratch arrays, and nothing that reads an {@code Inputs} may keep it past the call it was
     * passed to; {@link CloudHistory} copies the values it remembers. The accessors keep the
     * names of the record this used to be.
     */
    public static final class Inputs {
        private final float[] sunDirection = new float[3];
        private final float[] moonDirection = new float[3];
        private final float[] sunIlluminance = new float[3];
        private final float[] moonIlluminance = new float[3];
        private float sunAngularRadius;
        private float moonAngularRadius;
        private double cameraX;
        private double cameraY;
        private double cameraZ;
        private double dayTime;
        private boolean physicalAtmosphere;
        private float skyFill;

        /** An all-zero frame, to be filled with {@link #set} before use. */
        public Inputs() {}

        public Inputs(float[] sunDirection, float[] moonDirection,
                      float[] sunIlluminance, float[] moonIlluminance,
                      float sunAngularRadius, float moonAngularRadius,
                      double cameraX, double cameraY, double cameraZ,
                      double dayTime, boolean physicalAtmosphere, float skyFill) {
            set(sunDirection, moonDirection, sunIlluminance, moonIlluminance,
                    sunAngularRadius, moonAngularRadius, cameraX, cameraY, cameraZ,
                    dayTime, physicalAtmosphere, skyFill);
        }

        /** Replaces every value; the vectors are copied, never retained. Allocates nothing. */
        public Inputs set(float[] sunDirection, float[] moonDirection,
                          float[] sunIlluminance, float[] moonIlluminance,
                          float sunAngularRadius, float moonAngularRadius,
                          double cameraX, double cameraY, double cameraZ,
                          double dayTime, boolean physicalAtmosphere, float skyFill) {
            System.arraycopy(sunDirection, 0, this.sunDirection, 0, 3);
            System.arraycopy(moonDirection, 0, this.moonDirection, 0, 3);
            System.arraycopy(sunIlluminance, 0, this.sunIlluminance, 0, 3);
            System.arraycopy(moonIlluminance, 0, this.moonIlluminance, 0, 3);
            this.sunAngularRadius = sunAngularRadius;
            this.moonAngularRadius = moonAngularRadius;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.dayTime = dayTime;
            this.physicalAtmosphere = physicalAtmosphere;
            this.skyFill = skyFill;
            return this;
        }

        /** This instance's own array; writes to it change this frame. */
        public float[] sunDirection() { return sunDirection; }
        public float[] moonDirection() { return moonDirection; }
        public float[] sunIlluminance() { return sunIlluminance; }
        public float[] moonIlluminance() { return moonIlluminance; }
        public float sunAngularRadius() { return sunAngularRadius; }
        public float moonAngularRadius() { return moonAngularRadius; }
        public double cameraX() { return cameraX; }
        public double cameraY() { return cameraY; }
        public double cameraZ() { return cameraZ; }
        public double dayTime() { return dayTime; }
        public boolean physicalAtmosphere() { return physicalAtmosphere; }
        public float skyFill() { return skyFill; }
    }

    public CloudSkyCompositor(VolumetricClouds.Quality quality, int atmosphereWidth, int atmosphereHeight) {
        this(VulkanWork.minecraft(), quality, atmosphereWidth, atmosphereHeight);
    }

    CloudSkyCompositor(VulkanWork work, VolumetricClouds.Quality quality, int atmosphereWidth, int atmosphereHeight) {
        this.work = work;
        this.quality = quality;
        width = quality.domeWidth();
        height = quality.domeHeight();
        upperHeight = height / 2;
        outputBytes = width * height * 4 * Short.BYTES;
        readbackBytes = width * (height + 1) * 4 * Short.BYTES;
        resources = new CloudSkyResources(work, loadShaderSource(atmosphereWidth, atmosphereHeight), width, height);
    }

    public int width() { return width; }
    public int height() { return height; }
    public int outputBytes() { return outputBytes; }
    public VolumetricClouds.Quality quality() { return quality; }

    /**
     * Marches and composites when an update is due, and returns the oldest finished readback.
     *
     * @param atmosphereTexture the atmosphere panorama (with its mean row) to composite over
     * @param transmittanceLut the atmosphere's transmittance LUT
     * @param upperMean receives the composited upper-hemisphere mean when a readback is returned
     * @return the finished dome image (width × height RGBA16F), or null when none is ready. The
     *         buffer is a persistently mapped readback slot that stays <em>held</em>: no later
     *         readback is written into it until {@link #releaseHeldReadback()}, until a later
     *         call returns a newer image (which takes over the hold), or until the history is
     *         discarded, which {@link #holdsReadback()} reports. That is what lets the sky upload
     *         it a band per frame across several frames.
     */
    public ByteBuffer update(VulkanImage atmosphereTexture, VulkanImage transmittanceLut, Inputs inputs, float[] upperMean) {
        if (closed) throw new IllegalStateException("Cloud sky compositor is closed");
        CloudNoise noise = CloudNoise.sharedIfReady();
        if (noise == null) {
            return null;
        }
        double worldTime = VolumetricClouds.worldTime();
        long tick = (long) Math.floor(worldTime);
        boolean reset = history.needsReset(inputs, worldTime, VolumetricClouds.params().base());
        if (reset) {
            restart();
        }
        boolean due = reset || (tick != lastUpdateTick && Math.floorMod(tick, quality.updateTicks()) == 0)
                || tick < lastUpdateTick;
        if (due || LightningFlash.cloudRevision() != lastLightningRevision) {
            dispatch(noise, atmosphereTexture, transmittanceLut, inputs, worldTime, reset,
                    reset || history.isMoving(inputs), due);
            lastLightningRevision = LightningFlash.cloudRevision();
            if (due) {
                lastUpdateTick = tick;
                history.record(inputs, worldTime, VolumetricClouds.params().base());
            }
        }
        return consumeCompletedReadback(upperMean);
    }

    /**
     * Whether the image the last non-null {@link #update} returned is still held, so reading it
     * is safe. False once it was released or the history was discarded; a reader must then stop
     * using that buffer, because the GPU may already be writing a new readback into it.
     */
    public boolean holdsReadback() {
        return readbackRing.holds();
    }

    /**
     * Returns the held image's slot to the ring once the reader no longer needs it. Until then
     * the ring has one slot fewer for readbacks in flight, and an update that finds no free
     * slot keeps its march on the GPU but skips the readback. Harmless when nothing is held.
     */
    public void releaseHeldReadback() {
        readbackRing.release();
    }

    /** Forgets the accumulated sky and its published results, for a new world. */
    public void invalidate() {
        discardHistory();
        VolumetricClouds.clearFarResults();
    }

    /**
     * Forgets the accumulated sky within the same world, so the next update marches everything,
     * but keeps the published results until that march lands. Clearing them here would show a
     * cloudless sun and sky for the frames before the readback.
     */
    private void restart() {
        discardHistory();
        VolumetricClouds.restartFarResults();
    }

    private void discardHistory() {
        history.invalidate();
        generation++;
        lastUpdateTick = Long.MIN_VALUE;
        // The held image belongs to the scene just discarded, and the reader must not finish
        // publishing it. Releasing it here also keeps the ring's one-slot invariant: every
        // readback still in flight now has an older generation and will be dropped unread,
        // which must not happen while an even older slot stays held behind them.
        readbackRing.release();
    }

    /**
     * @param reset whether the history is invalid, which also restarts the checkerboard
     * @param full whether to march every texel without history, as for a moving camera
     */
    private void dispatch(CloudNoise noise, VulkanImage atmosphereTexture, VulkanImage transmittanceLut,
                          Inputs inputs, double worldTime, boolean reset, boolean full, boolean march) {
        try (ProfileScope ignored = ProfileScope.begin("Cloud.Dome.Dispatch")) {
            VkCommandBuffer commands = work.begin();
            int phase = full ? -1 : checkerPhase;
            fillParameters(inputs, worldTime, phase, full ? 1.0f : HISTORY_BLEND);
            resources.prepare(commands, noise, atmosphereTexture, transmittanceLut, parameters);
            uploadOccupancyIfChanged(commands);
            if (march) {
                resources.dispatch(commands, PASS_CLOUD,
                        divideRoundUp(phase < 0 ? width : (width + 1) / 2, 8),
                        divideRoundUp(phase < 0 ? height : (height + 1) / 2, 8));
            }
            resources.dispatch(commands, PASS_COMPOSE, divideRoundUp(width, 8), divideRoundUp(height, 8));
            resources.dispatch(commands, PASS_REDUCE, 1, 1);
            work.execute(commands);
            enqueueReadback();
        }
        if (march) {
            checkerPhase = reset ? 0 : (checkerPhase + 1) & 3;
            updateIndex++;
        }
    }

    private void fillParameters(Inputs inputs, double worldTime, int phase, float blend) {
        CloudField.Params params = VolumetricClouds.params();
        float fieldX = (float) CloudField.fieldX(inputs.cameraX(), worldTime);
        float fieldZ = (float) CloudField.fieldZ(inputs.cameraZ(), worldTime);
        putVec4(0, fieldX, (float) inputs.cameraY(), fieldZ, updateIndex & 0xffff);
        putVec4(16, params.base(), params.coverageBias(), params.typeBias(), params.rain());
        putVec4(32, inputs.sunDirection()[0], inputs.sunDirection()[1], inputs.sunDirection()[2],
                inputs.sunAngularRadius());
        putVec4(48, inputs.moonDirection()[0], inputs.moonDirection()[1], inputs.moonDirection()[2],
                inputs.moonAngularRadius());
        putVec4(64, inputs.sunIlluminance()[0], inputs.sunIlluminance()[1], inputs.sunIlluminance()[2],
                quality.primarySteps());
        putVec4(80, inputs.moonIlluminance()[0], inputs.moonIlluminance()[1], inputs.moonIlluminance()[2],
                quality.lightSteps());
        boolean occupancy = VolumetricClouds.occupancyEnabled();
        putVec4(96, params.base() + VolumetricClouds.NEAR_CAP_ABOVE_BASE, occupancy ? 1.0f : 0.0f, blend, phase);
        putVec4(112, EXTINCTION, AMBIENT_SCALE, GROUND_ALBEDO, EXTRA_HAZE);
        double absoluteFieldX = inputs.cameraX() - worldTime * CloudField.WIND_X_PER_TICK;
        double absoluteFieldZ = inputs.cameraZ() - worldTime * CloudField.WIND_Z_PER_TICK;
        long cameraTileX = (long) Math.floor(absoluteFieldX / VolumetricClouds.TILE_SIZE);
        long cameraTileZ = (long) Math.floor(absoluteFieldZ / VolumetricClouds.TILE_SIZE);
        putVec4(128,
                (float) (VolumetricClouds.occupancyTileX() - cameraTileX),
                (float) (VolumetricClouds.occupancyTileZ() - cameraTileZ),
                0.0f, 0.0f);
        putVec4(144,
                (float) CloudField.cirrusX(inputs.cameraX(), worldTime),
                (float) CloudField.cirrusZ(inputs.cameraZ(), worldTime),
                0.0f, 0.0f);
        putVec4(160, (float) (LightningFlash.strikeX() - inputs.cameraX()),
                params.base() - LightningFlash.CLOUD_LIGHT_BELOW_BASE,
                (float) (LightningFlash.strikeZ() - inputs.cameraZ()), LightningFlash.CLOUD_LIGHT_RADIUS);
        putVec4(176, LightningFlash.cloudRadiance(0), LightningFlash.cloudRadiance(1),
                LightningFlash.cloudRadiance(2), 0.0f);
    }

    private void putVec4(int offset, float x, float y, float z, float w) {
        parameters.putFloat(offset, x);
        parameters.putFloat(offset + 4, y);
        parameters.putFloat(offset + 8, z);
        parameters.putFloat(offset + 12, w);
    }

    private void uploadOccupancyIfChanged(VkCommandBuffer commands) {
        int version = VolumetricClouds.occupancyVersion();
        if (version == uploadedOccupancyVersion) return;
        occupancyUpload.clear();
        occupancyUpload.put(VolumetricClouds.occupancy()).flip();
        resources.uploadOccupancy(commands, occupancyUpload);
        uploadedOccupancyVersion = version;
    }

    private void enqueueReadback() {
        // A slot held for band uploads is never overwritten, even across scene invalidation.
        if (!readbackRing.canEnqueue()) return;
        int index = readbackRing.writeIndex();
        VkCommandBuffer commands = work.begin();
        resources.finalSky().copyTo(commands, resources.readbacks[index]);
        work.execute(commands);
        readbackFences[index] = work.fence();
        readbackGenerations[index] = generation;
        readbackRing.enqueued();
    }

    private ByteBuffer consumeCompletedReadback(float[] upperMean) {
        while (readbackRing.pending() > 0) {
            int index = readbackRing.oldestPending();
            GpuFence fence = readbackFences[index];
            if (!fence.awaitCompletion(0)) {
                return null;
            }
            fence.close();
            readbackFences[index] = null;
            // Keep in-flight buffers fenced until completion, but never publish an older scene.
            // The returned slot becomes the held one, releasing any slot held before it: the
            // reader switches to the newer image in the same frame.
            boolean current = readbackGenerations[index] == generation;
            readbackRing.consumeOldest(current);
            if (current) {
                return decodeReadback(resources.readbacks[index].read(), upperMean);
            }
        }
        return null;
    }

    private ByteBuffer decodeReadback(ByteBuffer mapped, float[] upperMean) {
        mapped.position(0).limit(readbackBytes);

        int row = outputBytes;
        upperMean[0] = half(mapped, row);
        upperMean[1] = half(mapped, row + 2);
        upperMean[2] = half(mapped, row + 4);
        float sun = half(mapped, row + 8);
        float moon = half(mapped, row + 10);
        float cover = half(mapped, row + 12);
        float sunDisc = half(mapped, row + 16);
        float moonDisc = half(mapped, row + 18);
        for (int texel = 0; texel < occlusionScratch.length / 4; texel++) {
            int offset = row + (3 + texel) * 8;
            for (int k = 0; k < 4; k++) {
                occlusionScratch[texel * 4 + k] = half(mapped, offset + k * 2);
            }
        }
        VolumetricClouds.publishFarResults(sun, moon, sunDisc, moonDisc, cover, occlusionScratch);

        mapped.position(0).limit(outputBytes);
        return mapped;
    }

    private static float half(ByteBuffer buffer, int offset) {
        return Float.float16ToFloat(buffer.getShort(offset));
    }

    private String loadShaderSource(int atmosphereWidth, int atmosphereHeight) {
        try (InputStream stream = CloudSkyCompositor.class.getResourceAsStream(SHADER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing cloud sky shader " + SHADER_RESOURCE);
            }
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int versionStart = source.indexOf("#version");
            if (versionStart < 0) {
                throw new IllegalStateException("Cloud sky shader has no #version directive");
            }
            int versionEnd = source.indexOf('\n', versionStart) + 1;
            return source.substring(0, versionEnd)
                    + "#define SKY_WIDTH " + width + "\n"
                    + "#define SKY_HEIGHT " + height + "\n"
                    + "#define ATMOSPHERE_WIDTH " + atmosphereWidth + "\n"
                    + "#define ATMOSPHERE_HEIGHT " + atmosphereHeight + "\n"
                    + AtmosphereDefines.get()
                    + source.substring(versionEnd);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read cloud sky shader " + SHADER_RESOURCE, e);
        }
    }

    public void destroy() {
        if (closed) return;
        closed = true;
        for (int index = 0; index < READBACK_RING_SIZE; index++) {
            if (readbackFences[index] != null) readbackFences[index].close();
            readbackFences[index] = null;
        }
        work.retire(resources);
        readbackRing.reset();
        history.invalidate();
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * Slot accounting for the readback ring, kept apart from Vulkan so it can be tested.
     *
     * <p>Slots are written in ring order and read back in the same order. A consumed slot can
     * stay <em>held</em> while its reader still uploads from it; at most one slot is held, and
     * consuming a newer one moves the hold to it. The held slot is always the one just behind
     * the oldest pending slot, so the next write would land on it exactly when
     * {@code pending + held == size}; {@link #canEnqueue()} refuses then, and also refuses
     * outright whenever the write index names the held slot, so a dropped stale slot can never
     * open a path to overwriting the held one.
     */
    static final class ReadbackRing {
        private final int size;
        private int writeIndex;
        private int readIndex;
        private int pending;
        private int held = -1;

        ReadbackRing(int size) {
            if (size < 2) {
                throw new IllegalArgumentException("A readback ring needs at least two slots: " + size);
            }
            this.size = size;
        }

        /** Whether a readback may be written into {@link #writeIndex()} now. */
        boolean canEnqueue() {
            return pending + (held >= 0 ? 1 : 0) < size && writeIndex != held;
        }

        /** The slot the next readback is written into; only valid when {@link #canEnqueue()}. */
        int writeIndex() {
            return writeIndex;
        }

        /** Records that a readback was written into {@link #writeIndex()}. */
        void enqueued() {
            if (!canEnqueue()) {
                throw new IllegalStateException("Readback ring has no free slot");
            }
            writeIndex = (writeIndex + 1) % size;
            pending++;
        }

        int pending() {
            return pending;
        }

        /** The oldest slot still in flight; only valid while {@link #pending()} is positive. */
        int oldestPending() {
            return readIndex;
        }

        /**
         * Takes the oldest pending slot off the queue.
         *
         * @param hold whether its reader keeps it, which releases any slot held before; a slot
         *             dropped unread (an older scene) is free at once and leaves the hold alone
         * @return the slot
         */
        int consumeOldest(boolean hold) {
            if (pending == 0) {
                throw new IllegalStateException("No readback is pending");
            }
            int index = readIndex;
            readIndex = (readIndex + 1) % size;
            pending--;
            if (hold) {
                held = index;
            }
            return index;
        }

        boolean holds() {
            return held >= 0;
        }

        /** The held slot, or -1. */
        int heldIndex() {
            return held;
        }

        void release() {
            held = -1;
        }

        /** Forgets every slot, for when the buffers themselves are deleted. */
        void reset() {
            writeIndex = 0;
            readIndex = 0;
            pending = 0;
            held = -1;
        }
    }

    /** The atmosphere's {@code PHYS_*} defines, supplied by the sky package. */
    public static final class AtmosphereDefines {
        private static volatile String defines = "";

        private AtmosphereDefines() {}

        public static void set(String value) { defines = value; }
        static String get() { return defines; }
    }
}
