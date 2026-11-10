/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.profiling;

/**
 * Lets the mod close {@code WorldRendererMixin}'s open profiler phase from
 * outside the mixin, so a Fabric world-render event that takes over the rest of
 * the frame is not billed to whichever vanilla phase happened to be open.
 * Render thread only.
 */
public interface WorldRendererProfilingAccess {
    void rtxremix$closeProfilerPhaseForFabricEvent();

    void rtxremix$recordTerrainDraw(boolean skipped);
}
