/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTextureLoaderTest {

    private static final long MS = 1_000_000L;

    /** Holds submitted jobs until the test runs them, standing in for the loader thread. */
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runNext() {
            queued.removeFirst().run();
        }

        void runAll() {
            while (!queued.isEmpty()) runNext();
        }
    }

    /** A clock that advances a fixed step every time it is read, and counts its reads. */
    private static final class SteppingClock implements LongSupplier {
        final long step;
        long now;
        int reads;

        SteppingClock(long step) {
            this.step = step;
        }

        @Override
        public long getAsLong() {
            reads++;
            long value = now;
            now += step;
            return value;
        }
    }

    private record Delivery(String key, String value, Throwable failure) {}

    private static final class RecordingSink implements EntityTextureLoader.Sink<String, String> {
        final List<Delivery> deliveries = new ArrayList<>();

        @Override
        public void accept(String key, String value, Throwable failure) {
            deliveries.add(new Delivery(key, value, failure));
        }
    }

    private final ManualExecutor executor = new ManualExecutor();
    private final RecordingSink sink = new RecordingSink();

    private int drain(EntityTextureLoader<String, String> loader, int max) {
        return loader.drain(max, 2 * MS, new SteppingClock(0), sink);
    }

    @Test
    void aSecondRequestForAPendingKeyQueuesNothing() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(4, executor);
        AtomicInteger runs = new AtomicInteger();

        assertTrue(loader.request("zombie", () -> "a" + runs.incrementAndGet()));
        assertTrue(loader.request("zombie", () -> "b" + runs.incrementAndGet()));

        assertEquals(1, executor.queued.size());
        assertEquals(1, loader.inFlight());
        assertTrue(loader.isPending("zombie"));
        executor.runAll();
        assertEquals(1, drain(loader, 2));
        assertEquals(List.of(new Delivery("zombie", "a1", null)), sink.deliveries);
        assertFalse(loader.isPending("zombie"));
        assertEquals(0, loader.inFlight());
    }

    @Test
    void inFlightJobsAreBoundedUntilDrained() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(2, executor);

        assertTrue(loader.request("a", () -> "A"));
        assertTrue(loader.request("b", () -> "B"));
        assertFalse(loader.hasCapacity());
        assertFalse(loader.request("c", () -> "C"));
        assertFalse(loader.isPending("c"));

        // Completing a job does not free its slot: the result still holds memory until drained.
        executor.runAll();
        assertFalse(loader.hasCapacity());
        assertEquals(2, drain(loader, 2));
        assertTrue(loader.hasCapacity());
        assertTrue(loader.request("c", () -> "C"));
    }

    @Test
    void drainStopsAtTheItemLimitAndLeavesTheRestForLaterFrames() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(8, executor);
        for (String key : List.of("a", "b", "c")) loader.request(key, () -> key.toUpperCase());
        executor.runAll();

        assertEquals(2, drain(loader, 2));
        assertEquals(1, drain(loader, 2));
        assertEquals(List.of("a", "b", "c"),
                sink.deliveries.stream().map(Delivery::key).toList());
        assertEquals(0, loader.inFlight());
    }

    @Test
    void drainStopsOnceTheTimeBudgetIsSpent() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(8, executor);
        for (String key : List.of("a", "b", "c")) loader.request(key, () -> key);
        executor.runAll();

        // Every clock read is 3 ms after the last, so the first delivery exhausts 2 ms.
        SteppingClock clock = new SteppingClock(3 * MS);
        assertEquals(1, loader.drain(8, 2 * MS, clock, sink));
        assertEquals(2, clock.reads);

        SteppingClock fast = new SteppingClock(0);
        assertEquals(2, loader.drain(8, 2 * MS, fast, sink));
    }

    @Test
    void anIdleDrainNeitherReadsTheClockNorDelivers() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(8, executor);
        SteppingClock clock = new SteppingClock(MS);

        assertFalse(loader.hasCompleted());
        assertEquals(0, loader.drain(2, 2 * MS, clock, sink));
        assertEquals(0, clock.reads);
    }

    @Test
    void theItemLimitCostsOneClockReadPerDeliveryThatLeavesRoom() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(8, executor);
        for (String key : List.of("a", "b", "c")) loader.request(key, () -> key);
        executor.runAll();

        SteppingClock clock = new SteppingClock(0);
        assertEquals(2, loader.drain(2, 2 * MS, clock, sink));
        assertEquals(2, clock.reads);
    }

    @Test
    void aCancelledRequestSkipsItsWorkAndIsDroppedOnArrival() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(4, executor);
        AtomicInteger runs = new AtomicInteger();
        loader.request("bed", () -> "v" + runs.incrementAndGet());

        loader.cancel("bed");
        assertFalse(loader.isPending("bed"));
        executor.runAll();

        assertEquals(0, runs.get());
        assertEquals(0, drain(loader, 2));
        assertTrue(sink.deliveries.isEmpty());
        assertEquals(1, loader.discardedCount());
        assertEquals(0, loader.inFlight());
    }

    @Test
    void onlyTheLatestRequestForAKeyIsDelivered() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(4, executor);
        loader.request("atlas", () -> "stale");
        executor.runNext();
        // Re-stitched atlas: the first result is back but no longer wanted.
        loader.cancel("atlas");
        loader.request("atlas", () -> "fresh");
        executor.runNext();

        assertEquals(1, drain(loader, 4));
        assertEquals(List.of(new Delivery("atlas", "fresh", null)), sink.deliveries);
        assertEquals(0, loader.inFlight());
    }

    @Test
    void invalidateAllDropsCompletedResultsAtOnce() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(2, executor);
        loader.request("a", () -> "A");
        loader.request("b", () -> "B");
        executor.runAll();

        long before = loader.generation();
        loader.invalidateAll();

        assertEquals(before + 1, loader.generation());
        assertFalse(loader.hasCompleted());
        assertEquals(0, loader.inFlight());
        assertEquals(0, loader.pendingCount());
        assertTrue(loader.hasCapacity());
        assertEquals(0, drain(loader, 2));
        assertTrue(sink.deliveries.isEmpty());
    }

    @Test
    void aJobFromBeforeInvalidateAllSkipsItsWorkAndIsDroppedOnArrival() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(4, executor);
        AtomicInteger runs = new AtomicInteger();
        loader.request("pig", () -> "old" + runs.incrementAndGet());

        loader.invalidateAll();
        // The same key requested under the new generation is a separate job.
        loader.request("pig", () -> "new");
        assertEquals(2, loader.inFlight());
        executor.runAll();

        assertEquals(0, runs.get());
        assertEquals(1, drain(loader, 4));
        assertEquals(List.of(new Delivery("pig", "new", null)), sink.deliveries);
        assertEquals(1, loader.discardedCount());
        assertEquals(0, loader.inFlight());
    }

    @Test
    void aFailingJobDeliversItsFailure() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(4, executor);
        IllegalStateException boom = new IllegalStateException("corrupt png");
        loader.request("cow", () -> {
            throw boom;
        });
        executor.runAll();

        assertEquals(1, drain(loader, 2));
        Delivery delivery = sink.deliveries.get(0);
        assertNull(delivery.value());
        assertSame(boom, delivery.failure());
        assertFalse(loader.isPending("cow"));
    }

    @Test
    void aNullResultIsDeliveredAsAnAnswer() {
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(4, executor);
        loader.request("missing", () -> null);
        executor.runAll();

        assertEquals(1, drain(loader, 2));
        assertEquals(List.of(new Delivery("missing", null, null)), sink.deliveries);
    }

    @Test
    void aRefusedJobLeavesNothingPending() {
        Executor refusing = command -> {
            throw new RejectedExecutionException("shut down");
        };
        EntityTextureLoader<String, String> loader = new EntityTextureLoader<>(4, refusing);

        assertFalse(loader.request("x", () -> "X"));
        assertFalse(loader.isPending("x"));
        assertEquals(0, loader.inFlight());
    }

    @Test
    void theDaemonLoaderRunsJobsOffTheCallingThread() throws Exception {
        EntityTextureLoader<String, String> loader =
                EntityTextureLoader.withDaemonThread(4, "entity-texture-loader-test");
        try {
            Thread caller = Thread.currentThread();
            loader.request("t", () -> Thread.currentThread() == caller ? "same" : "other");
            long deadline = System.nanoTime() + 5_000 * MS;
            while (!loader.hasCompleted() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, drain(loader, 2));
            assertEquals("other", sink.deliveries.get(0).value());
        } finally {
            loader.shutdown();
        }
        assertFalse(loader.request("after", () -> "X"));
    }
}
