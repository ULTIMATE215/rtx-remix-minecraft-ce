/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import com.nvidia.rtxremix.minecraft.mixin.FrustumAccessor;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * A coarse index owned by one visibility graph. Its buffers alternate with the graph's node
 * map, so the render thread and the full-walk worker never mutate the same index. Slot moves
 * since the start of a walk are replayed before querying even if they predate publication.
 * The visible and nearby sets match vanilla's membership; output follows graph discovery
 * order rather than the Octree's spatial order. Remix terrain draws do not use this order.
 */
public final class BuiltChunkFrustumIndex {
    private static final HierarchicalFrustumCuller.EntryBounds<RenderSection> BOUNDS = (section, out) -> {
        AABB box = section.getBoundingBox();
        out[0] = box.minX; out[1] = box.minY; out[2] = box.minZ;
        out[3] = box.maxX; out[4] = box.maxY; out[5] = box.maxZ;
    };
    private final HierarchicalFrustumCuller<RenderSection> culler = new HierarchicalFrustumCuller<>();
    private long reconciledSequence;
    private int centerX, centerY, centerZ;
    private final Frustum frustum = new Frustum(new Matrix4f(), new Matrix4f());
    private List<RenderSection> visible;
    private List<RenderSection> nearby;
    private long hash;
    private final IntConsumer rebucket = slot -> culler.rebucket(slot, BOUNDS);
    private final HierarchicalFrustumCuller.BoxClassifier classifier = this::classify;
    private final Predicate<RenderSection> exactTest = section -> frustum.isVisible(section.getBoundingBox());
    private final Consumer<RenderSection> appendVisible = this::append;
    private final Consumer<RenderSection> hashSection = section -> hash +=
            (section.getSectionNode() * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;

    void reset(ViewArea viewArea) {
        culler.clear();
        reconciledSequence = ChunkSlotMoveLog.RENDER_THREAD.sequence();
        SectionPos center = viewArea.getCameraSectionPos();
        centerX = (center.x() << 4) + 8;
        centerY = (center.y() << 4) + 8;
        centerZ = (center.z() << 4) + 8;
    }

    public void add(RenderSection section) {
        AABB box = section.getBoundingBox();
        culler.add(section, section.index, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public int size() {
        return culler.size();
    }

    public void apply(Frustum source, List<RenderSection> visible, List<RenderSection> nearby) {
        long sequence = ChunkSlotMoveLog.RENDER_THREAD.sequence();
        if (sequence != reconciledSequence) {
            if (!ChunkSlotMoveLog.RENDER_THREAD.replay(reconciledSequence, sequence, rebucket)) {
                culler.rebucketAll(BOUNDS);
            }
            reconciledSequence = sequence;
        }
        // Matches SectionOcclusionGraph.offsetFrustum, including its camera-cube expansion.
        frustum.set(source);
        frustum.offsetToFullyIncludeCameraCube(8);
        this.visible = visible;
        this.nearby = nearby;
        try {
            culler.forEachVisible(classifier, exactTest, appendVisible);
        } finally {
            this.visible = null;
            this.nearby = null;
        }
    }

    private int classify(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        FrustumAccessor access = (FrustumAccessor) frustum;
        double x = access.rtxremix$getX(), y = access.rtxremix$getY(), z = access.rtxremix$getZ();
        int result = access.rtxremix$getFrustumIntersection().intersectAab(
                (float) (minX - x), (float) (minY - y), (float) (minZ - z),
                (float) (maxX - x), (float) (maxY - y), (float) (maxZ - z));
        return result == FrustumIntersection.INSIDE ? HierarchicalFrustumCuller.INSIDE
                : result == FrustumIntersection.INTERSECT ? HierarchicalFrustumCuller.INTERSECT
                : HierarchicalFrustumCuller.OUTSIDE;
    }

    private void append(RenderSection section) {
        visible.add(section);
        AABB box = section.getBoundingBox();
        if (centerX > box.minX - 32 && centerX < box.maxX + 32
                && centerY > box.minY - 32 && centerY < box.maxY + 32
                && centerZ > box.minZ - 32 && centerZ < box.maxZ + 32) {
            nearby.add(section);
        }
    }

    /** Position hash independent of the order in which the traversal discovered sections. */
    public long positionHash() {
        hash = 0;
        culler.forEachVisible((a, b, c, d, e, f) -> HierarchicalFrustumCuller.INSIDE,
                section -> true, hashSection);
        return hash;
    }
}
