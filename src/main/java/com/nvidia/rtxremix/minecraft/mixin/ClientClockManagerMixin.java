/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.camera.ClientClockAccess;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin implements ClientClockAccess {
    @Shadow protected abstract ClientClockManager.ClockInstance getInstance(Holder<WorldClock> clock);

    @Override
    public void rtxremix$setTotalTicks(Holder<WorldClock> clock, long ticks) {
        ClientClockManager.ClockInstance instance = getInstance(clock);
        instance.totalTicks = ticks;
        instance.partialTick = 0;
    }
}
