/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.perf;

/**
 * Self-check for submit overlap, where Submit returns before the runtime has
 * handed the frame to the GPU queue.
 *
 * <p>Minecraft then waits on a Remix-done value whose signal may not be submitted
 * yet, which timeline semaphores allow. What must still hold is that the
 * interop info read right after Submit already describes this frame. The
 * guard learns, from ordinary blocking submits, by how much the Remix-done
 * value advances per Submit. It allows overlap only after
 * {@link #LEARN_FRAMES} consecutive ordinary submits advanced it by the same
 * positive step. An overlapped submit must then advance it by exactly that
 * step and keep a valid, unchanged image handle; otherwise the caller vetoes
 * the technique.
 */
public final class SubmitOverlapGuard {

    public static final int LEARN_FRAMES = 30;

    private long lastValue = -1L;
    private long lastHandle;
    private long learnedStep;
    private int stableFrames;

    /**
     * Records one main-path Submit's interop info.
     *
     * @return false when an overlapped submit broke the learned pattern
     */
    public boolean record(boolean overlapped, long doneValue, long imageHandle) {
        boolean ok = true;
        if (lastValue >= 0) {
            long step = doneValue - lastValue;
            if (overlapped) {
                ok = learnedStep > 0 && step == learnedStep
                        && validHandle(imageHandle) && imageHandle == lastHandle;
                if (!ok) {
                    stableFrames = 0;
                }
            } else if (step > 0 && step == learnedStep) {
                stableFrames++;
            } else {
                learnedStep = step;
                stableFrames = step > 0 ? 1 : 0;
            }
        } else if (overlapped) {
            ok = false;
        }
        lastValue = doneValue;
        lastHandle = imageHandle;
        return ok;
    }

    /** Whether ordinary submits have shown a stable step long enough to try overlap. */
    public boolean ready() {
        return learnedStep > 0 && stableFrames >= LEARN_FRAMES;
    }

    /** Forgets the learned step, after anything that may change how the runtime counts. */
    public void reset() {
        lastValue = -1L;
        lastHandle = 0L;
        learnedStep = 0L;
        stableFrames = 0;
    }

    static boolean validHandle(long handle) {
        return handle != 0L && handle != -1L;
    }
}
