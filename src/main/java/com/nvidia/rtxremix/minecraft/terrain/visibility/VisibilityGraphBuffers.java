/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;

/**
 * The buffers behind vanilla's visibility state, kept across walks instead of allocated per
 * walk: the per-slot info list, the waiting-section map, the frustum index and the
 * {@link ChunkInfoPool}. Two sets of them alternate, because at most two states exist at
 * once: the published one the render thread reads and extends incrementally, and the one a
 * full walk is filling on the visibility thread. Vanilla submits a full walk only from the
 * render thread at the start of a frame and only when the previous walk has published, so
 * when a walk starts refilling a buffer set, the render thread has already moved to the
 * other.
 *
 * <p>Each GraphStorage constructor takes the other set, after vanilla has joined any previous
 * full walk. A new view area discards the old sets even when its dimensions are unchanged.
 */
public final class VisibilityGraphBuffers {
    /** One state's worth of buffers. */
    public static final class Buffers {
        public final SectionToNodeMap infos;
        public final Long2ObjectOpenHashMap<LongList> waitingSections = new Long2ObjectOpenHashMap<>();
        public final ChunkInfoPool infoPool;
        public final BuiltChunkFrustumIndex index = new BuiltChunkFrustumIndex();
        public final SectionVisibilityWalk walk;

        Buffers(int chunkCount) {
            infos = new SectionToNodeMap(Math.max(1, chunkCount));
            infoPool = new ChunkInfoPool(chunkCount);
            walk = new SectionVisibilityWalk(infos, infoPool, index);
        }

        void reset(ViewArea viewArea) {
            Arrays.fill(infos.nodes, null);
            waitingSections.clear();
            infoPool.beginWalk();
            index.reset(viewArea);
        }
    }

    private static Buffers[] sets = new Buffers[0];
    private static ViewArea storage;
    private static int current;

    private VisibilityGraphBuffers() {
    }

    /** Called once per GraphStorage construction, before its map and lookup are installed. */
    public static synchronized Buffers acquire(ViewArea viewArea) {
        if (storage != viewArea) {
            storage = viewArea;
            sets = new Buffers[] {new Buffers(viewArea.size()), new Buffers(viewArea.size())};
            current = 0;
        } else {
            current ^= 1;
        }
        sets[current].reset(viewArea);
        return sets[current];
    }

    /** Releases the old world's slots after vanilla has joined its visibility worker. */
    public static synchronized void clear() {
        storage = null;
        sets = new Buffers[0];
    }
}
