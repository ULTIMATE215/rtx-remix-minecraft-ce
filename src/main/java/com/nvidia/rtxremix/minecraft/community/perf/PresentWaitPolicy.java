/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.perf;

/**
 * Decides, once per present, whether Remix may wait for Minecraft's composite
 * on the GPU ({@link Mode#PIPELINED}) or must block the render thread until
 * Minecraft's Vulkan submission is done.
 *
 * <p>Blocking is the safe mode: once Present returns, every client command of the
 * frame has finished. Pipelining gives that guarantee up, so it runs only in
 * steady gameplay frames. Any open screen or Remix menu, a frame without a
 * world, a switched-off technique, and the first {@link #SETTLE_FRAMES} frames
 * after any of those or after a {@link #discontinuity} all block. Menus block
 * because DLSS, frame generation and resolution changes made there reallocate
 * the interop images.
 *
 * <p>Switching in either direction is safe: each client-done signal covers all
 * earlier client work, so a blocking wait for frame N's signal implies every
 * earlier frame finished.
 *
 * <p>A present or interop failure is a strike; {@link #STRIKE_LIMIT} strikes
 * within {@link #STRIKE_WINDOW_FRAMES} frames tell the caller to veto the
 * technique. Pure logic, no Vulkan or Remix calls, no allocation.
 */
public final class PresentWaitPolicy {

    /** Why a present pipelines or blocks, in order of precedence. */
    public enum Mode {
        OFF, VETOED, NO_WORLD, REMIX_MENU, SCREEN_OPEN, SETTLING, PIPELINED;

        /** Whether a mode blocks for a reason other than settling, which re-arms the settle. */
        boolean holdsSettle() {
            return ordinal() < SETTLING.ordinal();
        }
    }

    /** Blocking frames after any interruption before pipelining resumes. */
    public static final int SETTLE_FRAMES = 4;
    public static final int STRIKE_LIMIT = 3;
    public static final long STRIKE_WINDOW_FRAMES = 600;

    private static final Mode[] MODES = Mode.values();

    private long frame;
    private int settleRemaining = SETTLE_FRAMES;
    private int lastGeneration = Integer.MIN_VALUE;
    private Mode last = Mode.OFF;

    private final long[] strikeFrames = new long[STRIKE_LIMIT];
    private int strikeCount;
    private int strikeNext;

    private final int[] counts = new int[MODES.length];

    /**
     * Decides one present.
     *
     * @param active whether the pipelining technique is on
     * @param vetoed whether the technique was vetoed this session
     * @param generation {@link TopOptimization#generation()}; any change settles first
     * @param worldFrame whether this frame submitted a world scene through the main path
     * @param remixMenuOpen whether a Remix menu holds input
     * @param screenOpen whether a Minecraft screen is open
     */
    public Mode decide(boolean active, boolean vetoed, int generation,
                       boolean worldFrame, boolean remixMenuOpen, boolean screenOpen) {
        frame++;
        if (generation != lastGeneration) {
            lastGeneration = generation;
            settleRemaining = SETTLE_FRAMES;
        }
        Mode mode;
        if (!active) {
            mode = vetoed ? Mode.VETOED : Mode.OFF;
        } else if (!worldFrame) {
            mode = Mode.NO_WORLD;
        } else if (remixMenuOpen) {
            mode = Mode.REMIX_MENU;
        } else if (screenOpen) {
            mode = Mode.SCREEN_OPEN;
        } else if (settleRemaining > 0) {
            settleRemaining--;
            mode = Mode.SETTLING;
        } else {
            mode = Mode.PIPELINED;
        }
        if (mode.holdsSettle()) {
            settleRemaining = SETTLE_FRAMES;
        }
        counts[mode.ordinal()]++;
        last = mode;
        return mode;
    }

    /** Blocks the next {@link #SETTLE_FRAMES} presents, after anything that may reallocate the interop images. */
    public void discontinuity() {
        settleRemaining = SETTLE_FRAMES;
    }

    /**
     * Records a present or interop failure, which also settles. Returns true
     * when {@link #STRIKE_LIMIT} failures fell within {@link #STRIKE_WINDOW_FRAMES}
     * frames, so the caller should veto the technique.
     */
    public boolean failure() {
        settleRemaining = SETTLE_FRAMES;
        strikeFrames[strikeNext] = frame;
        strikeNext = (strikeNext + 1) % STRIKE_LIMIT;
        if (strikeCount < STRIKE_LIMIT) {
            strikeCount++;
        }
        if (strikeCount < STRIKE_LIMIT) {
            return false;
        }
        long oldest = strikeFrames[strikeNext];
        return frame - oldest < STRIKE_WINDOW_FRAMES;
    }

    /** The mode of the most recent present. */
    public Mode lastMode() {
        return last;
    }

    public boolean lastPresentPipelined() {
        return last == Mode.PIPELINED;
    }

    /** Presents in {@code mode} since the last {@link #resetCounts}. */
    public int count(Mode mode) {
        return counts[mode.ordinal()];
    }

    public int totalCount() {
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        return total;
    }

    public void resetCounts() {
        java.util.Arrays.fill(counts, 0);
    }
}
