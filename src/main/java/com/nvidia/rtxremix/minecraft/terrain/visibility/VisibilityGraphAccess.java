/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

/** The buffers owned by one vanilla GraphStorage, shared only after its atomic publication. */
public interface VisibilityGraphAccess {
    VisibilityGraphBuffers.Buffers rtxremix$buffers();
}
