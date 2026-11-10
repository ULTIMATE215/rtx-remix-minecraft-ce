/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkSlotMoveLog;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks into vanilla's BuiltChunk lifecycle.
 *
 * <ul>
 *   <li><b>setOrigin:</b> Resets per-slot block-entity and snapshot state when
 *       vanilla reuses a BuiltChunk for a new section, and logs the move so the
 *       frustum index can re-bucket exactly the slots that moved.
 *   <li><b>createRebuildTask:</b> Records which neighbour columns the region
 *       snapshot contained, so a later chunk-load re-flag from a column the
 *       capture already read can be dropped.
 *   <li><b>method_60908:</b> Tracks whether the section contains block
 *       entities and flags empty render data as a removal candidate. The
 *       render thread validates the live world section before removing
 *       Remix terrain because vanilla can publish temporary empty data.
 * </ul>
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin implements ChunkBuilderBlockEntityAccess, BuiltChunkNeighbourhoodAccess {

    @Shadow
    @Final
    public int index;

    @Shadow
    @Final
    BlockPos.MutableBlockPos renderOrigin;

    /** 26.2 dropped RenderSection.doesChunkExistAt; same create=false FULL-chunk probe. */
    private static boolean rtxremix$doesChunkExistAt(long sectionPos) {
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        return level != null && level.getChunkSource().getChunk(
                net.minecraft.core.SectionPos.x(sectionPos), net.minecraft.core.SectionPos.z(sectionPos),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
    }

    @Shadow
    public abstract net.minecraft.client.renderer.chunk.SectionMesh getSectionMesh();

    /** 1.21.11 dropped BuiltChunk.getSquaredCameraDistance; same centre-to-camera measure. */
    private double rtxremix$squaredCameraDistance() {
        net.minecraft.world.phys.Vec3 camera = net.minecraft.client.Minecraft.getInstance()
                .gameRenderer.mainCamera().position();
        double dx = renderOrigin.getX() + 8.0 - camera.x;
        double dy = renderOrigin.getY() + 8.0 - camera.y;
        double dz = renderOrigin.getZ() + 8.0 - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Shared by every slot: createRebuildTask runs only on the render thread
     * (updateChunks, ChunkBuilder.rebuild, the Remix recapture queue), so one
     * scratch position serves all of them.
     */

    private volatile boolean rtxremix$hasBlockEntities;

    /** Bit k set iff neighbour column (DX[k], DZ[k]) was loaded when the last region snapshot was taken. */
    private int rtxremix$snapshotMask;

    @Override
    public boolean rtxremix$hasBlockEntities() {
        return rtxremix$hasBlockEntities;
    }

    @Override
    public int rtxremix$snapshotMask() {
        return rtxremix$snapshotMask;
    }

    @Override
    public void rtxremix$clearSnapshotBit(int bit) {
        rtxremix$snapshotMask &= ~bit;
    }

    @Inject(method = "setSectionNode(J)V", at = @At("HEAD"))
    private void rtxremix$onSetOrigin(long sectionPos, CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
        rtxremix$snapshotMask = 0;
    }

    @Inject(method = "setSectionNode(J)V", at = @At("RETURN"))
    private void rtxremix$afterSetOrigin(long sectionPos, CallbackInfo ci) {
        ChunkSlotMoveLog.RENDER_THREAD.record(index);
    }

    /**
     * The probe sees exactly the columns the snapshot holds: ChunkRendererRegionBuilder.build
     * reads ClientChunkManager.getChunk(x, z, FULL, true), which returns the shared EmptyChunk
     * precisely when this create=false probe returns null, and nothing interleaves on the
     * render thread between the two. A null region (empty own section) is recorded too: it
     * has no geometry that a neighbour could make wrong.
     */
    @Inject(
            method = "createCompileTask(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask;",
            at = @At("RETURN"))
    private void rtxremix$recordSnapshotNeighbourhood(
            RenderSectionRegion region, CallbackInfoReturnable<?> cir) {
        // RETURN precedes the synchronous task.run in BuiltChunk.rebuild, so a first build still reads EMPTY here.
        boolean firstBuild = getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED;
        int mask = 0;
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            if (rtxremix$doesChunkExistAt(net.minecraft.core.SectionPos.asLong(
                    (renderOrigin.getX() >> 4) + NeighbourReflagFilter.DX[k],
                    renderOrigin.getY() >> 4,
                    (renderOrigin.getZ() >> 4) + NeighbourReflagFilter.DZ[k]))) {
                mask |= NeighbourReflagFilter.bit(k);
            }
        }
        rtxremix$snapshotMask = mask;
        if (NeighbourReflagFilter.isIncompleteFirstBuild(firstBuild, rtxremix$squaredCameraDistance(), mask)) {
            NeighbourReflagFilter.recordIncompleteFirstBuild();
        }
    }

    @Inject(method = "setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;", at = @At("HEAD"))
    private void rtxremix$onChunkDataSet(net.minecraft.client.renderer.chunk.SectionMesh chunkData, CallbackInfoReturnable<?> cir) {
        rtxremix$hasBlockEntities = !chunkData.getRenderableBlockEntities().isEmpty();

        if (chunkData.hasRenderableLayers()) {
            return;
        }
        int x = renderOrigin.getX();
        int y = renderOrigin.getY();
        int z = renderOrigin.getZ();
        if (x == -1 && y == -1 && z == -1) {
            return;
        }
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(x >> 4, y >> 4, z >> 4);
    }

    @Inject(method = "reset()V", at = @At("HEAD"), require = 0)
    private void rtxremix$onClear(CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
        rtxremix$snapshotMask = 0;
    }
}
