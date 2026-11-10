/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

/** Staged vertex/index allocations awaiting upload, read under the dispatcher's copy lock. */
public interface PendingUploadsAccess {
    int rtxremix$pendingUploads();
}
