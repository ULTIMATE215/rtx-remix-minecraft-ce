/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.LevelExtractorAccess;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkArrayIterator;
import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import java.util.Arrays;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 moved section dirty tracking and the per-frame region cache from
 * {@code LevelRenderer} into {@code LevelExtractor}; the hooks that lived on
 * those {@code WorldRendererMixin} targets live here now.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin implements LevelExtractorAccess {

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    @Nullable
    private SectionUpdateTracker sectionUpdateTracker;

    @Shadow
    public abstract void setSectionDirty(int sectionX, int sectionY, int sectionZ);

    @Nullable
    private RenderRegionCache rtxremix$frameRegionCache;
    private RenderSection[] rtxremix$blockEntitySections = new RenderSection[256];
    private final BuiltChunkArrayIterator rtxremix$blockEntityIterator = new BuiltChunkArrayIterator();
    private int rtxremix$blockEntitySectionCount;
    private int rtxremix$blockEntitySectionScans;

    @Redirect(method = "extractVisibleBlockEntities", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;iterator()Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"))
    private ObjectListIterator<RenderSection> rtxremix$blockEntitySections(ObjectArrayList<RenderSection> visible) {
        rtxremix$blockEntitySectionScans = visible.size();
        if (!RtxRemixMod.isRtxEnabled()) return visible.iterator();
        int count = 0;
        for (int i = 0; i < visible.size(); i++) {
            RenderSection section = visible.get(i);
            if (((ChunkBuilderBlockEntityAccess) section).rtxremix$hasBlockEntities()) {
                if (count == rtxremix$blockEntitySections.length) {
                    rtxremix$blockEntitySections = Arrays.copyOf(rtxremix$blockEntitySections, count * 2);
                }
                rtxremix$blockEntitySections[count++] = section;
            }
        }
        Arrays.fill(rtxremix$blockEntitySections, count, Math.max(count, rtxremix$blockEntitySectionCount), null);
        rtxremix$blockEntitySectionCount = count;
        return rtxremix$blockEntityIterator.reset(rtxremix$blockEntitySections, count);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void rtxremix$releaseWorld(ClientLevel level, CallbackInfo ci) {
        Arrays.fill(rtxremix$blockEntitySections, null);
        rtxremix$blockEntitySectionCount = 0;
        rtxremix$blockEntitySectionScans = 0;
        RenderRegionCache cache = rtxremix$takeFrameRegionCache();
        if (cache != null) ((RegionBuilderRelease) cache).rtxremix$releaseSnapshots();
    }

    @Override public int rtxremix$blockEntitySectionScans() { return rtxremix$blockEntitySectionScans; }
    @Override public int rtxremix$blockEntitySectionCount() { return rtxremix$blockEntitySectionCount; }

    /**
     * Hidden water-top culling reads farther than vanilla's one-block rebuild
     * margin. Expand horizontal invalidation so a ceiling or fluid update
     * cannot leave a neighboring section's occlusion decision stale.
     */
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionRebuildMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled() || sectionUpdateTracker == null) {
            return;
        }

        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        int minSectionX = SectionPos.blockToSectionCoord(minX - radius);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + radius);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    setSectionDirty(sectionX, sectionY, sectionZ);
                }
            }
        }
        ci.cancel();
    }

    /**
     * Chunk-load and light-data re-flags enter here. Vanilla's 3x3x3 loop is replaced
     * wholesale because the rule must tell the loading column from its neighbours.
     */
    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$filterChunkLoadReflags(int cx, int sy, int cz, CallbackInfo ci) {
        ViewArea viewArea = levelRenderer.viewArea();
        if (!RtxRemixMod.isRtxEnabled() || !NeighbourReflagFilter.ENABLED || viewArea == null) {
            return;
        }
        ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$flagLoadedSection(cx, sy, cz);
        ci.cancel();
    }

    /** The extraction's region cache, released after LevelRenderer.compileSections. */
    @WrapOperation(
            method = "extract",
            at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;"))
    private RenderRegionCache rtxremix$captureFrameRegionCache(Operation<RenderRegionCache> original) {
        // A frame that extracted but never reached LevelRenderer.compileSections left its cache
        // here; its section updates were discarded with the render state, so release it now.
        RenderRegionCache stale = rtxremix$frameRegionCache;
        if (stale != null) {
            ((RegionBuilderRelease) stale).rtxremix$releaseSnapshots();
        }
        RenderRegionCache cache = original.call();
        rtxremix$frameRegionCache = cache;
        return cache;
    }

    @Override
    public void rtxremix$setSectionNotDirty(long sectionNode) {
        if (sectionUpdateTracker == null) {
            return;
        }
        SectionUpdateTracker.SectionDirtyState state = sectionUpdateTracker.getDirtyState(sectionNode);
        if (state != null) {
            state.setNotDirty();
        }
    }

    @Override
    public boolean rtxremix$isSectionDirty(long sectionNode) {
        if (sectionUpdateTracker == null) return false;
        SectionUpdateTracker.SectionDirtyState state = sectionUpdateTracker.getDirtyState(sectionNode);
        return state != null && state.isDirty();
    }

    @Override
    @Nullable
    public RenderRegionCache rtxremix$takeFrameRegionCache() {
        RenderRegionCache cache = rtxremix$frameRegionCache;
        rtxremix$frameRegionCache = null;
        return cache;
    }
}
