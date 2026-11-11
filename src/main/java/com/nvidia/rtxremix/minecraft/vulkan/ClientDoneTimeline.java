/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/**
 * The client-done timeline values Vulkan signals and Remix's Present waits for.
 *
 * <p>Present must only ever wait for a value Vulkan submitted a signal for. A
 * blocking wait for a value that is never signaled ends in the runtime's
 * timeout; a GPU-side wait never ends at all. So the value to present
 * advances only after the encoder submits the signal to the queue without an
 * error. A failed attempt still consumes its value, so every value the client
 * is asked to signal is strictly greater than any it may already hold.
 */
public final class ClientDoneTimeline {

    private long requested;
    private long signaled;
    private long pending;

    /** Returns the next value to signal; resolve it with {@link #signalAccepted} or {@link #signalFailed}. */
    public long beginSignal() {
        if (pending != 0) {
            throw new IllegalStateException("The signal of " + pending + " was never resolved");
        }
        pending = ++requested;
        return pending;
    }

    /** Records that Vulkan submitted the pending signal, so Present may wait for it. */
    public void signalAccepted() {
        requirePending();
        signaled = pending;
        pending = 0;
    }

    /** Records that the pending signal failed; Present keeps waiting for the last accepted value. */
    public void signalFailed() {
        requirePending();
        pending = 0;
    }

    /** The value Present may wait for: always one Vulkan submitted a signal for, or 0 before the first. */
    public long valueToPresent() {
        return signaled;
    }

    /** The highest value ever handed out by {@link #beginSignal}. */
    public long lastRequested() {
        return requested;
    }

    private void requirePending() {
        if (pending == 0) {
            throw new IllegalStateException("No client-done signal is pending");
        }
    }
}
