/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;

/** Updates a camera sample immediately while preserving the clock's configured tick rate. */
public interface ClientClockAccess {
    void rtxremix$setTotalTicks(Holder<WorldClock> clock, long ticks);
}
