/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.WorldClock;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Client clock samples are immediate; server writes coalesce into one task per session. */
final class CameraClockSync {
    private Pending pending;

    void apply(Minecraft client, long ticks) {
        ClientLevel level = client.level;
        if (level == null) return;
        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElse(null);
        if (clock != null) {
            ((ClientClockAccess) level.clockManager()).rtxremix$setTotalTicks(clock, ticks);
            level.environmentAttributes().invalidateTickCache();
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            clear();
            return;
        }
        if (pending == null || pending.level != level || pending.server != server) {
            clear();
            pending = new Pending(client, level, server);
        }
        pending.time.set(ticks);
        if (pending.queued.compareAndSet(false, true)) server.execute(pending);
    }

    void clear() {
        if (pending != null) pending.cancelled = true;
        pending = null;
    }

    private static final class Pending implements Runnable {
        final Minecraft client;
        final ClientLevel level;
        final MinecraftServer server;
        final AtomicLong time = new AtomicLong();
        final AtomicBoolean queued = new AtomicBoolean();
        volatile boolean cancelled;

        Pending(Minecraft client, ClientLevel level, MinecraftServer server) {
            this.client = client;
            this.level = level;
            this.server = server;
        }

        @Override
        public void run() {
            queued.set(false);
            if (cancelled || client.level != level || client.getSingleplayerServer() != server) return;
            long ticks = time.get();
            for (var world : server.getAllLevels()) {
                Holder<WorldClock> clock = world.dimensionType().defaultClock().orElse(null);
                if (clock != null && server.clockManager().getTotalTicks(clock) != ticks) {
                    server.clockManager().setTotalTicks(clock, ticks);
                }
            }
        }
    }
}
