/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.Queue;
import java.util.function.Consumer;

/** Vanilla's 26.2 traversal, with state-owned nodes, probe vectors and block positions. */
public final class SectionVisibilityWalk implements FarChunkProbe.GraphLookup {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double STEP = Math.ceil(Math.sqrt(3.0) * 16.0);
    private final SectionToNodeMap infos;
    private final ChunkInfoPool pool;
    private final BuiltChunkFrustumIndex index;
    private final FarChunkProbe probe = new FarChunkProbe();
    private final BlockPos.MutableBlockPos probePosition = new BlockPos.MutableBlockPos();
    private ViewArea viewArea;

    public SectionVisibilityWalk(SectionToNodeMap infos, ChunkInfoPool pool, BuiltChunkFrustumIndex index) {
        this.infos = infos;
        this.pool = pool;
        this.index = index;
    }

    public void run(ViewArea area, GraphStorage storage, Vec3 camera, Queue<Node> queue,
                    boolean smartCull, Consumer<RenderSection> onSectionAdded,
                    LongOpenHashSet emptySections, LongOpenHashSet loadedChunks) {
        viewArea = area;
        try {
            walk(storage, camera, queue, smartCull, onSectionAdded, emptySections, loadedChunks);
        } finally {
            viewArea = null;
        }
    }

    private void walk(GraphStorage storage, Vec3 camera, Queue<Node> queue,
                      boolean smartCull, Consumer<RenderSection> onSectionAdded,
                      LongOpenHashSet emptySections, LongOpenHashSet loadedChunks) {
        int cameraX = SectionPos.blockToSectionCoord(camera.x);
        int cameraY = SectionPos.blockToSectionCoord(camera.y);
        int cameraZ = SectionPos.blockToSectionCoord(camera.z);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            RenderSection section = node.section;
            long position = section.getSectionNode();
            long chunk = ChunkPos.fromSectionNode(position);
            if (!loadedChunks.contains(chunk)) {
                storage.sectionsWaitingForChunkLoads.computeIfAbsent(chunk, key -> new LongArrayList()).add(position);
                continue;
            }
            if (!emptySections.contains(section.getSectionNode())) {
                if (storage.sectionTree.add(section)) {
                    index.add(section);
                    onSectionAdded.accept(section);
                }
            } else {
                section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY);
            }
            int x = SectionPos.x(position);
            int y = SectionPos.y(position);
            int z = SectionPos.z(position);
            boolean far = Math.abs(x - cameraX) > 3 || Math.abs(y - cameraY) > 3 || Math.abs(z - cameraZ) > 3;
            for (Direction direction : DIRECTIONS) {
                long neighbourPos = section.getNeighborSectionNode(direction);
                if (!ChunkTrackingView.isInViewDistance(cameraX, cameraZ, viewArea.getViewDistance(),
                        SectionPos.x(neighbourPos), SectionPos.z(neighbourPos))
                        || Math.abs(cameraY - SectionPos.y(neighbourPos)) > viewArea.getViewDistance()) continue;
                RenderSection neighbour = viewArea.getRenderSection(neighbourPos);
                if (neighbour == null || smartCull && node.hasDirection(direction.getOpposite())) continue;
                if (smartCull && node.hasSourceDirections() && !canSeeThrough(node, section.getSectionMesh(), direction)) continue;
                if (smartCull && far && !probe.reachesCamera(x << 4, y << 4, z << 4, direction.getAxis(),
                        (cameraX << 4) + 8, (cameraY << 4) + 8, (cameraZ << 4) + 8,
                        camera.x, camera.y, camera.z, STEP, viewArea.maxY(), viewArea.minY(), this)) continue;
                Node existing = infos.get(neighbour);
                if (existing != null) {
                    existing.addSourceDirection(direction);
                } else {
                    Node discovered = pool.acquire(neighbour, direction, node.step + 1);
                    discovered.setDirections(node.directions, direction);
                    queue.add(discovered);
                    infos.put(neighbour, discovered);
                }
            }
        }
    }

    private static boolean canSeeThrough(Node node, SectionMesh mesh, Direction direction) {
        for (int i = 0; i < DIRECTIONS.length; i++) {
            if (node.hasSourceDirection(i) && mesh.facesCanSeeEachother(DIRECTIONS[i].getOpposite(), direction)) return true;
        }
        return false;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        RenderSection passed = viewArea.getRenderSectionAt(probePosition.set(x, y, z));
        return passed != null && infos.get(passed) != null;
    }
}
