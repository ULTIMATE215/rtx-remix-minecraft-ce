/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * Collects animated sprite upload patches each tick and flushes them to Remix
 * in a single batched call before present.
 *
 * <p>LabPBR-aware: sprites that have {@code _n} / {@code _s} siblings are
 * re-processed on every animation frame so AO (from {@code _n.b}) and the
 * animated LabPBR material channels in {@code _s} stay in phase with albedo.
 * Without this, later albedo frames would overwrite the AO-modulated atlas
 * while roughness, metallic, SSS, and emissive atlases stayed stuck on the
 * init-time sidecar frame. Non-LabPBR sprites are copied as albedo only.
 *
 * <p>All patches for one frame across every animated atlas texture go out in
 * one {@code remixapi_UpdateTextureFromMemory} call using the multi-handle
 * batch API. This amortizes the JNI crossing and, on the dxvk side, lets mip
 * regeneration be scoped to the dirty rect per atlas instead of regenerating
 * the whole atlas once per atlas.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasAnimationForwarder.class);

    // Gate on patch queueing: read by addPatch and toggled by
    // init/clear/pause/resume, all of which run on the render thread. Each
    // toggle is sequenced against the state it guards at its own call site.
    private static volatile boolean active = false;

    private static Thread expectedThread;
    private static final List<PatchRecord> pendingPatches = new ArrayList<>();
    private static final Map<Identifier, Atlas> atlases = new HashMap<>();
    private static Target blockTarget;
    private static long generation;
    private static final ThreadLocal<Identifier> currentAtlasId = new ThreadLocal<>();

    // Reusable scratch between CPU-side patch composition and the final
    // DirectBuffer copy. Pre-sized for a typical 64x64 RGBA sprite so the
    // first frame of every animation lands without a realloc; grown
    // monotonically beyond that and never shrunk, so steady-state allocates
    // no garbage.
    private static final int kInitialScratchBytes = 64 * 64 * 4;
    private static byte[] scratch = new byte[kInitialScratchBytes];

    private static final class Atlas {
        final Identifier id;
        final Set<Identifier> sprites;
        final Map<Identifier, PatchRecord> records = new HashMap<>();
        final List<Target> targets = new ArrayList<>();

        Atlas(Identifier id, Set<Identifier> sprites) {
            this.id = id;
            this.sprites = Set.copyOf(sprites);
        }
    }

    /** A texture owner's registration. Close it before destroying any of its native handles. */
    public static final class Target implements AutoCloseable {
        private final Atlas atlas;
        private final Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR;
        private long albedo, emissive, roughness, metallic, sss;

        private Target(Atlas atlas, Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
                       long albedo, long emissive, long roughness, long metallic, long sss) {
            this.atlas = atlas;
            this.labPBR = labPBR;
            this.albedo = albedo;
            this.emissive = emissive;
            this.roughness = roughness;
            this.metallic = metallic;
            this.sss = sss;
        }

        @Override
        public void close() {
            if (!atlas.targets.remove(this)) return;
            if (atlas.targets.isEmpty()) {
                atlases.remove(atlas.id, atlas);
                pendingPatches.removeIf(patch -> patch.atlas == atlas);
                atlas.records.clear();
            }
        }
    }

    /** One reusable record per atlas and sprite; repeated ticks replace its pending sample. */
    private static final class PatchRecord {
        final Atlas atlas;
        final Identifier id;
        final String resourceName;
        int dstX, dstY, srcX, srcY, nextX, nextY, width, height, rowPitch;
        float nextBlend;
        long pixelPtr;
        boolean queued;

        PatchRecord(Atlas atlas, Identifier id) {
            this.atlas = atlas;
            this.id = id;
            resourceName = id.toString();
        }
    }

    /** Registers another native copy of an atlas, such as entity and particle item materials. */
    public static Target register(Identifier atlasId, Set<Identifier> sprites,
                                  Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
                                  long albedo, long emissive, long roughness, long metallic, long sss) {
        Atlas atlas = atlases.get(atlasId);
        if (atlas == null) {
            atlas = new Atlas(atlasId, sprites);
            atlases.put(atlasId, atlas);
        }
        Target target = new Target(atlas, labPBR, albedo, emissive, roughness, metallic, sss);
        atlas.targets.add(target);
        generation++;
        return target;
    }

    /** Changes on reload or resume so a paused sprite rejoins vanilla's current frame. */
    public static long generation() {
        return generation;
    }

    public static boolean accepts(Identifier spriteId) {
        Atlas atlas = atlases.get(currentAtlasId.get());
        return active && atlas != null && atlas.sprites.contains(spriteId);
    }

    public static void init(Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
                            Set<Identifier> blockAtlasSprites) {
        if (blockTarget != null) blockTarget.close();
        blockTarget = register(BlockTextureManager.BLOCK_ATLAS_ID, blockAtlasSprites,
                labPBR, 0, 0, 0, 0, 0);
        expectedThread = null;
        active = true;
        LOGGER.info("Atlas animation initialized ({} LabPBR sprites, {} block sprites)",
                labPBR.size(), blockAtlasSprites.size());
    }

    /**
     * Queues the exact frame vanilla sampled. Pixels must remain live through flush on this
     * render thread; another tick of the same sprite replaces both its pointer and sampling.
     */
    public static void addPatch(Identifier spriteId, int dstX, int dstY,
                               int srcX, int srcY, int width, int height,
                               int nextX, int nextY, float nextBlend,
                               long pixelPtr, int rowPitch) {
        if (!accepts(spriteId)) return;
        Thread current = Thread.currentThread();
        if (expectedThread == null) {
            expectedThread = current;
        } else if (current != expectedThread) {
            throw new IllegalStateException("Animated texture capture must run on the render thread");
        }
        Atlas atlas = atlases.get(currentAtlasId.get());
        PatchRecord record = atlas.records.get(spriteId);
        if (record == null) {
            record = new PatchRecord(atlas, spriteId);
            atlas.records.put(spriteId, record);
        }
        record.dstX = dstX;
        record.dstY = dstY;
        record.srcX = srcX;
        record.srcY = srcY;
        record.nextX = nextX;
        record.nextY = nextY;
        record.nextBlend = nextBlend;
        record.width = width;
        record.height = height;
        record.pixelPtr = pixelPtr;
        record.rowPitch = rowPitch;
        if (!record.queued) {
            record.queued = true;
            pendingPatches.add(record);
        }
    }

    /** Removes a sprite before its CPU pixels are released during atlas disposal. */
    public static void discard(Identifier atlasId, Identifier spriteId) {
        Atlas atlas = atlases.get(atlasId);
        PatchRecord record = atlas == null ? null : atlas.records.remove(spriteId);
        if (record != null && record.queued) {
            pendingPatches.remove(record);
            record.queued = false;
        }
    }

    private static void clearPending() {
        for (PatchRecord record : pendingPatches) record.queued = false;
        pendingPatches.clear();
    }

    /**
     * Builds one multi-handle batch covering animated albedo and LabPBR atlas
     * sidecars, then issues a single {@code remixapi_UpdateTextureFromMemory}
     * call. LabPBR data is applied per patch using pre-computed tables cached
     * at init.
     *
     * @param api            the Remix API handle
     * @param albedoHandle   handle of the albedo atlas texture (required)
     * @param emissiveHandle handle of the emissive atlas, or 0 if none exists
     * @param roughnessHandle handle of the roughness atlas, or 0 if none exists
     * @param metallicHandle handle of the metallic atlas, or 0 if none exists
     * @param sssHandle      handle of the SSS atlas, or 0 if none exists
     */
    public static void flush(RemixAPI api, long albedoHandle, long emissiveHandle,
                             long roughnessHandle, long metallicHandle, long sssHandle) {
        if (blockTarget != null) {
            blockTarget.albedo = albedoHandle;
            blockTarget.emissive = emissiveHandle;
            blockTarget.roughness = roughnessHandle;
            blockTarget.metallic = metallicHandle;
            blockTarget.sss = sssHandle;
        }
        if (pendingPatches.isEmpty()) return;
        List<ByteBuffer> ownedBuffers = new ArrayList<>();
        List<TextureBatchEntry> entries = new ArrayList<>();
        try {
            for (Atlas atlas : atlases.values()) {
                for (Target target : atlas.targets) appendTarget(target, entries, ownedBuffers);
            }
            if (!entries.isEmpty()) {
                TexturePatchInfo info = new TexturePatchInfo();
                info.entries = entries.toArray(new TextureBatchEntry[0]);
                api.updateTextureFromMemory(info);
            }
        } catch (Exception e) {
            LOGGER.error("Error flushing animation patches", e);
        } finally {
            for (ByteBuffer buffer : ownedBuffers) DirectBufferPool.release(buffer);
            clearPending();
        }
    }

    private static void appendTarget(Target target, List<TextureBatchEntry> entries, List<ByteBuffer> ownedBuffers) {
        long albedoHandle = target.albedo, emissiveHandle = target.emissive;
        long roughnessHandle = target.roughness, metallicHandle = target.metallic, sssHandle = target.sss;
        int count = 0;
        for (PatchRecord patch : pendingPatches) if (patch.atlas == target.atlas) count++;
        if (count == 0 || albedoHandle == 0) return;
        TexturePatch[] albedoPatches = new TexturePatch[count];
        List<TexturePatch> roughnessPatches = new ArrayList<>();
        List<TexturePatch> metallicPatches = new ArrayList<>();
        List<TexturePatch> emissivePatches = new ArrayList<>();
        List<TexturePatch> sssPatches = new ArrayList<>();

        int i = 0;
        for (PatchRecord p : pendingPatches) {
            if (p.atlas != target.atlas) continue;
            BlockTextureManager.AnimatedSpriteLabPBR pbr = target.labPBR.get(p.resourceName);

            final int patchBytes = p.width * p.height * 4;
            final int patchRowBytes = p.width * 4;
            ensureScratch(patchBytes);
            ByteBuffer srcBuf = MemoryUtil.memByteBuffer(p.pixelPtr, p.rowPitch * (p.height - 1) + patchRowBytes);

            // --- Albedo patch ---
            if (pbr != null && pbr.aoTable != null) {
                LabPBRConverter.applyAOToPatch(
                    scratch, patchRowBytes,
                    srcBuf, p.rowPitch,
                    pbr.aoTable, pbr.aoW, pbr.aoH,
                    p.srcX, p.srcY,
                    p.width, p.height);
            } else {
                LabPBRConverter.copyAlbedoPatch(
                    scratch, patchRowBytes,
                    srcBuf, p.rowPitch,
                    p.width, p.height);
            }
            // Keep the frame's holes in its colour, as the initial atlas upload does.
            LabPBRConverter.dilateTransparentRgb(scratch, p.width, 0, 0, p.width, p.height);
            ByteBuffer albedoDst = DirectBufferPool.acquire(patchBytes);
            albedoDst.put(scratch, 0, patchBytes);
            albedoDst.flip();
            ownedBuffers.add(albedoDst);

            albedoPatches[i++] = createPatch(p, albedoDst, patchRowBytes, TextureFormat.RGBA8_UNORM);

            // --- _s RGB material channels ---
            // LabPBR packs roughness/metallic/SSS into _s.rgb. These
            // atlases are separate Remix textures, so they need matching
            // animation patches just like _s.alpha emissive.
            if (pbr != null && pbr.sRoughnessTable != null && roughnessHandle != 0) {
                int patchR8Bytes = p.width * p.height;
                ensureScratch(patchR8Bytes);
                LabPBRConverter.buildR8Patch(
                    scratch, p.width,
                    pbr.sRoughnessTable, pbr.sW, pbr.sH,
                    p.srcX, p.srcY,
                    p.nextX, p.nextY,
                    p.nextBlend,
                    p.width, p.height);
                ByteBuffer roughnessDst = DirectBufferPool.acquire(patchR8Bytes);
                roughnessDst.put(scratch, 0, patchR8Bytes);
                roughnessDst.flip();
                ownedBuffers.add(roughnessDst);
                roughnessPatches.add(createPatch(p, roughnessDst, p.width, TextureFormat.R8_UNORM));
            }

            if (pbr != null && pbr.sMetallicTable != null && metallicHandle != 0) {
                int patchR8Bytes = p.width * p.height;
                ensureScratch(patchR8Bytes);
                LabPBRConverter.buildR8Patch(
                    scratch, p.width,
                    pbr.sMetallicTable, pbr.sW, pbr.sH,
                    p.srcX, p.srcY,
                    p.nextX, p.nextY,
                    p.nextBlend,
                    p.width, p.height);
                ByteBuffer metallicDst = DirectBufferPool.acquire(patchR8Bytes);
                metallicDst.put(scratch, 0, patchR8Bytes);
                metallicDst.flip();
                ownedBuffers.add(metallicDst);
                metallicPatches.add(createPatch(p, metallicDst, p.width, TextureFormat.R8_UNORM));
            }

            if (pbr != null && pbr.hasSss && pbr.sSssTable != null && sssHandle != 0) {
                LabPBRConverter.buildSssPatch(
                    scratch, patchRowBytes,
                    pbr.sSssTable, pbr.sW, pbr.sH,
                    p.srcX, p.srcY,
                    p.nextX, p.nextY,
                    p.nextBlend,
                    p.width, p.height);
                ByteBuffer sssDst = DirectBufferPool.acquire(patchBytes);
                sssDst.put(scratch, 0, patchBytes);
                sssDst.flip();
                ownedBuffers.add(sssDst);
                sssPatches.add(createPatch(p, sssDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
            }

            // --- Emissive patch (authored _s alpha, else a built-in mask) ---
            if (emissiveHandle == 0) continue;
            final boolean hasLabPBREmissive = pbr != null && pbr.hasPerPixelEmissive;
            BuiltinEmission.Mask builtinMask = hasLabPBREmissive ? null : BuiltinEmission.mask(p.id);

            // Skip sprites with neither. Vanilla block luminance affects
            // cast light, not surface emission.
            if (!hasLabPBREmissive && builtinMask == null) continue;

            if (hasLabPBREmissive) {
                LabPBRConverter.buildEmissivePatch(
                    scratch, patchRowBytes,
                    srcBuf, p.rowPitch,
                    pbr.sAlphaTable, pbr.sW, pbr.sH,
                    p.srcX, p.srcY,
                    p.nextX, p.nextY,
                    p.nextBlend,
                    p.width, p.height,
                    0f);
            } else {
                // The mask is over the sprite's own UV space, which every animation frame shares.
                LabPBRConverter.buildEmissiveMaskPatch(
                    scratch, patchRowBytes,
                    srcBuf, p.rowPitch,
                    p.width, p.height, builtinMask);
            }

            ByteBuffer emissiveDst = DirectBufferPool.acquire(patchBytes);
            emissiveDst.put(scratch, 0, patchBytes);
            emissiveDst.flip();
            ownedBuffers.add(emissiveDst);

            emissivePatches.add(createPatch(p, emissiveDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
        }

        addBatchEntry(entries, albedoHandle, albedoPatches);
        addBatchEntry(entries, roughnessHandle, roughnessPatches);
        addBatchEntry(entries, metallicHandle, metallicPatches);
        addBatchEntry(entries, emissiveHandle, emissivePatches);
        addBatchEntry(entries, sssHandle, sssPatches);

    }

    private static void ensureScratch(int byteCount) {
        if (scratch.length < byteCount) {
            scratch = new byte[byteCount];
        }
    }

    private static TexturePatch createPatch(PatchRecord p, ByteBuffer data, int rowPitch, int format) {
        TexturePatch patch = new TexturePatch();
        patch.dstX = p.dstX;
        patch.dstY = p.dstY;
        patch.width = p.width;
        patch.height = p.height;
        patch.pixelData = data;
        patch.rowPitch = rowPitch;
        patch.format = format;
        return patch;
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, TexturePatch[] patches) {
        if (handle == 0 || patches.length == 0) {
            return;
        }
        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = handle;
        entry.patches = patches;
        entries.add(entry);
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, List<TexturePatch> patches) {
        if (handle == 0 || patches.isEmpty()) {
            return;
        }
        addBatchEntry(entries, handle, patches.toArray(new TexturePatch[0]));
    }

    public static void clear() {
        // All registration, queueing and disposal run on the render thread.
        active = false;
        clearPending();
        atlases.clear();
        blockTarget = null;
        generation++;
        expectedThread = null;
        currentAtlasId.remove();
        LOGGER.info("AtlasAnimationForwarder cleared");
    }

    public static void beginAtlasUpload(Identifier atlasId) {
        currentAtlasId.set(atlasId);
    }

    public static void endAtlasUpload() {
        currentAtlasId.remove();
    }

    /**
     * Deactivates queueing and drops any pending records without wiping
     * any atlas registrations.
     * Use for reversible pauses like the RTX-toggle path where
     * {@link #resume()} should be a cheap flip without a resource-pack reload.
     */
    public static void pause() {
        active = false;
        clearPending();
        for (Atlas atlas : atlases.values()) atlas.records.clear();
        generation++;
    }

    public static void resume() {
        generation++;
        active = blockTarget != null;
    }

    private AtlasAnimationForwarder() {}
}
