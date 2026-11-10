/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import org.joml.Vector3d;

/**
 * Vanilla's line-of-sight probe for sections far from the camera, without its per-step
 * allocations.
 *
 * <p>The 26.2 walk probes from the current section's corner, using JOML's normalization and
 * distance operations. Reusing the two vectors preserves that arithmetic, including JOML's
 * fused operations, without allocating for each neighbour. One instance belongs to each
 * visibility graph, so a background full walk never shares scratch with partial updates.
 */
public final class FarChunkProbe {

    /** Answers whether the section holding a block position exists and is in the graph. */
    @FunctionalInterface
    public interface GraphLookup {
        boolean contains(int blockX, int blockY, int blockZ);
    }

    /** Squared distance at which the ray is close enough to the camera to stop. */
    private static final double NEAR_CAMERA_DISTANCE_SQUARED = 3600.0;
    private final Vector3d point = new Vector3d();
    private final Vector3d advance = new Vector3d();

    /**
     * Runs the probe from the current section at {@code origin} toward its neighbour on
     * {@code axis}, toward the camera at ({@code cameraX}, {@code cameraY}, {@code cameraZ}).
     *
     * @param cameraSectionCenterX camera section origin plus eight, per axis; picks the corner
     *                             of the current section the ray starts from
     * @param step                 ray advance per iteration in blocks
     * @param worldTopYExclusive   one past the highest block (320 for the overworld);
     *                             a point above it or below
     *                             {@code worldBottomY} leaves the world, which passes
     * @param worldBottomY         inclusive bottom of the world
     * @return true when every probed section is in the graph or the ray left the world
     */
    public boolean reachesCamera(
            int originX, int originY, int originZ,
            Direction.Axis axis,
            int cameraSectionCenterX, int cameraSectionCenterY, int cameraSectionCenterZ,
            double cameraX, double cameraY, double cameraZ,
            double step,
            int worldTopYExclusive, int worldBottomY,
            GraphLookup graph) {
        double pointX = originX + (axis == Direction.Axis.X
                ? (cameraSectionCenterX > originX ? 16 : 0)
                : (cameraSectionCenterX < originX ? 16 : 0));
        double pointY = originY + (axis == Direction.Axis.Y
                ? (cameraSectionCenterY > originY ? 16 : 0)
                : (cameraSectionCenterY < originY ? 16 : 0));
        double pointZ = originZ + (axis == Direction.Axis.Z
                ? (cameraSectionCenterZ > originZ ? 16 : 0)
                : (cameraSectionCenterZ < originZ ? 16 : 0));

        point.set(pointX, pointY, pointZ);
        advance.set(cameraX, cameraY, cameraZ).sub(point).normalize().mul(step);
        while (point.distanceSquared(cameraX, cameraY, cameraZ) > NEAR_CAMERA_DISTANCE_SQUARED) {
            point.add(advance);
            if (point.y > worldTopYExclusive || point.y < worldBottomY) {
                return true;
            }
            if (!graph.contains(
                    Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z))) {
                return false;
            }
        }
        return true;
    }
}
