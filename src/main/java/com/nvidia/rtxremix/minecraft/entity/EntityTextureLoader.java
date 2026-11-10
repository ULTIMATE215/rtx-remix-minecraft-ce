/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Runs the CPU half of entity texture-set creation off the render thread and hands the
 * results back under a per-frame budget.
 *
 * <p>The first frame an entity type is seen used to read its PNG, decode its LabPBR
 * variants, and convert every channel on the render thread before any Remix texture
 * existed, which is a visible hitch with a high-resolution PBR pack. A job here produces
 * only heap data: the caller keeps every Remix handle on the render thread, so a result
 * is turned into textures by {@link #drain} there, at most a few per frame.
 *
 * <p>The queue is bounded by {@code maxInFlight}: a job counts from {@link #request}
 * until {@link #drain} or {@link #invalidateAll} takes its completed ticket back, so the
 * completed queue can never hold more than that many results. A key has at most one
 * ticket in flight, and only the ticket the key currently maps to is delivered: a
 * {@link #cancel} or {@link #invalidateAll} forgets the mapping, and the stale ticket is
 * discarded when it comes back. Results hold nothing but heap arrays, so discarding one
 * is dropping the reference.
 *
 * <p>Every method except the job itself is called on the render thread. The ticket's
 * result fields are written by the worker before it enqueues the ticket, and the
 * concurrent queue's hand-off publishes them.
 *
 * @param <K> request key, compared by {@code equals}
 * @param <R> result produced by a job
 */
final class EntityTextureLoader<K, R> {

    /** The CPU work of one request; runs on the loader thread. */
    @FunctionalInterface
    interface Job<R> {
        R load() throws Exception;
    }

    /** Receives a delivered result on the render thread; exactly one of value or failure is meaningful. */
    @FunctionalInterface
    interface Sink<K, R> {
        void accept(K key, R value, Throwable failure);
    }

    private static final class Ticket<K, R> {
        final K key;
        final long generation;
        final Job<R> job;
        /** Set by {@link #cancel}; lets a queued job that has not started skip its work. */
        volatile boolean cancelled;
        R value;
        Throwable failure;

        Ticket(K key, long generation, Job<R> job) {
            this.key = key;
            this.generation = generation;
            this.job = job;
        }
    }

    private final int maxInFlight;
    private final Executor executor;
    /** Render-thread map from key to the one ticket whose result is still wanted. */
    private final Map<K, Ticket<K, R>> pending = new HashMap<>();
    private final ConcurrentLinkedQueue<Ticket<K, R>> completed = new ConcurrentLinkedQueue<>();
    /** Jobs handed to the executor and not yet taken back off {@link #completed}. */
    private int inFlight;
    /**
     * Bumped by {@link #invalidateAll}. Volatile so a queued job whose generation has
     * passed can skip its work on the loader thread; delivery is decided on the render
     * thread, which owns the write.
     */
    private volatile long generation;
    private long discarded;

    EntityTextureLoader(int maxInFlight, Executor executor) {
        if (maxInFlight < 1) throw new IllegalArgumentException("maxInFlight must be at least 1");
        this.maxInFlight = maxInFlight;
        this.executor = executor;
    }

    /**
     * A loader backed by one low-priority daemon thread. The thread starts with the first
     * request and exits after it has been idle for a while, so a session that never meets a
     * new entity texture holds no thread.
     */
    static <K, R> EntityTextureLoader<K, R> withDaemonThread(int maxInFlight, String threadName) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return new EntityTextureLoader<>(maxInFlight, executor);
    }

    /** Whether a result for {@code key} is still wanted and not yet delivered. */
    boolean isPending(K key) {
        return pending.containsKey(key);
    }

    /** Whether another request can be queued now; a full loader asks the caller to retry next frame. */
    boolean hasCapacity() {
        return inFlight < maxInFlight;
    }

    /** Whether any completed ticket, wanted or stale, is waiting for {@link #drain}. Allocation-free. */
    boolean hasCompleted() {
        return !completed.isEmpty();
    }

    long generation() {
        return generation;
    }

    int inFlight() {
        return inFlight;
    }

    int pendingCount() {
        return pending.size();
    }

    /** Stale results dropped since construction, for diagnostics and tests. */
    long discardedCount() {
        return discarded;
    }

    /**
     * Queues {@code job} for {@code key}.
     *
     * @return true when the job is queued or one for the key already is; false when the
     *         loader is full or the executor refused it, in which case nothing is queued
     */
    boolean request(K key, Job<R> job) {
        if (pending.containsKey(key)) return true;
        if (inFlight >= maxInFlight) return false;
        Ticket<K, R> ticket = new Ticket<>(key, generation, job);
        pending.put(key, ticket);
        inFlight++;
        try {
            executor.execute(() -> run(ticket));
        } catch (RejectedExecutionException e) {
            pending.remove(key);
            inFlight--;
            return false;
        }
        return true;
    }

    private void run(Ticket<K, R> ticket) {
        try {
            if (!ticket.cancelled && ticket.generation == generation) {
                ticket.value = ticket.job.load();
            }
        } catch (Throwable t) {
            ticket.failure = t;
        } finally {
            // Always hand the ticket back, even unwanted, so the in-flight count balances.
            completed.offer(ticket);
        }
    }

    /** Forgets the request for {@code key}; its result is dropped when it comes back. */
    void cancel(K key) {
        Ticket<K, R> ticket = pending.remove(key);
        if (ticket != null) ticket.cancelled = true;
    }

    /**
     * Forgets every request and drops every result already back, for a resource reload,
     * a world change, or teardown. Jobs still running finish and are dropped on arrival.
     */
    void invalidateAll() {
        generation++;
        for (Ticket<K, R> ticket : pending.values()) ticket.cancelled = true;
        pending.clear();
        Ticket<K, R> ticket;
        while ((ticket = completed.poll()) != null) {
            inFlight--;
            discarded++;
        }
    }

    /**
     * Delivers completed results to {@code sink}, stopping after {@code maxDelivered}
     * results or once {@code budgetNanos} has passed since the drain began. Stale tickets
     * are dropped without counting against either limit. The clock is read once to set the
     * deadline and once after each delivery that leaves room for another, so the default of
     * two deliveries costs two reads.
     *
     * @return the number of results delivered
     */
    int drain(int maxDelivered, long budgetNanos, LongSupplier clock, Sink<K, R> sink) {
        if (completed.isEmpty() || maxDelivered <= 0) return 0;
        long deadline = clock.getAsLong() + budgetNanos;
        int delivered = 0;
        Ticket<K, R> ticket;
        while ((ticket = completed.poll()) != null) {
            inFlight--;
            if (ticket.generation != generation || pending.get(ticket.key) != ticket) {
                discarded++;
                continue;
            }
            pending.remove(ticket.key);
            delivered++;
            sink.accept(ticket.key, ticket.value, ticket.failure);
            if (delivered >= maxDelivered || clock.getAsLong() - deadline >= 0) break;
        }
        return delivered;
    }

    /** Drops everything and stops the loader thread; later requests are refused. */
    void shutdown() {
        invalidateAll();
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }
}
