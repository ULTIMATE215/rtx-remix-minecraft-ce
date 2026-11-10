/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.minecraft.client.renderer.chunk.RenderRegionCache;
import org.jetbrains.annotations.Nullable;

/**
 * What {@code LevelExtractorMixin} exposes on 26.2's {@code LevelExtractor}, which took over
 * section dirty tracking and the per-frame region cache from {@code LevelRenderer}.
 */
public interface LevelExtractorAccess {

    /**
     * Clears the section's dirty flag so the next extraction does not queue a second
     * compile for a section the mod already queued.
     */
    void rtxremix$setSectionNotDirty(long sectionNode);

    /** Whether the resident section already awaits a vanilla rebuild. */
    boolean rtxremix$isSectionDirty(long sectionNode);

    int rtxremix$blockEntitySectionScans();

    int rtxremix$blockEntitySectionCount();

    /**
     * Hands over the region cache this frame's extraction built, once, so its snapshots
     * can be released after {@code LevelRenderer.compileSections} has created its tasks.
     */
    @Nullable
    RenderRegionCache rtxremix$takeFrameRegionCache();
}
