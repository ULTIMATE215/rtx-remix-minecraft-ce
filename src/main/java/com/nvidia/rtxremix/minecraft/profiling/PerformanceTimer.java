/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.profiling;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight per-frame timing and counting instrumentation.
 *
 * Accumulates timing data and per-frame counters over a configurable
 * interval and logs average/min/max for each named section.
 */
public class PerformanceTimer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceTimer.class);

    private static final int REPORT_INTERVAL = 300;
    private static final int MAX_SECTIONS = 32;
    private static final int MAX_COUNTERS = 96;
    private static final String FRAME_PLOT_NAME = "Java.Frame.ms";

    // Timing sections
    private final String[] sectionNames = new String[MAX_SECTIONS];
    private final String[] sectionPlotNames = new String[MAX_SECTIONS];
    private final long[] sectionTotalNanos = new long[MAX_SECTIONS];
    private final long[] sectionMinNanos = new long[MAX_SECTIONS];
    private final long[] sectionMaxNanos = new long[MAX_SECTIONS];
    private final long[] sectionStartNanos = new long[MAX_SECTIONS];
    private int sectionCount = 0;

    // Per-frame counters (accumulated each frame, reported as avg/min/max per frame)
    private final String[] counterNames = new String[MAX_COUNTERS];
    private final String[] counterPlotNames = new String[MAX_COUNTERS];
    private final long[] counterFrameValue = new long[MAX_COUNTERS];
    private final long[] counterTotal = new long[MAX_COUNTERS];
    private final long[] counterMin = new long[MAX_COUNTERS];
    private final long[] counterMax = new long[MAX_COUNTERS];
    private int counterCount = 0;

    private int frameCount = 0;
    private long frameTotalNanos = 0;
    private long frameMinNanos = Long.MAX_VALUE;
    private long frameMaxNanos = 0;
    private long frameStartNanos = 0;

    private boolean benchmarkMode = false;

    // Per-frame snapshot data (valid between beginFrame/endFrame)
    private final long[] sectionLastNanos = new long[MAX_SECTIONS];
    private final long[] counterLastValue = new long[MAX_COUNTERS];

    // Community Edition: "Frame total" spans renderAndSubmitFrame only. The real
    // frame time is the wall-clock period between Minecraft frames, and the
    // outer rows time the interop and present work that runs after it.
    /** {@link #recordOuter} row: the Vulkan wait for Remix's output and its import. */
    public static final int OUTER_REMIX_TO_MINECRAFT = 0;
    /** {@link #recordOuter} row: the Vulkan copy of the finished frame back to Remix. */
    public static final int OUTER_MINECRAFT_TO_REMIX = 1;
    /** {@link #recordOuter} row: Remix's Present call, including any wait for Minecraft. */
    public static final int OUTER_PRESENT = 2;
    private static final String[] OUTER_NAMES = {"Interop Remix->Minecraft", "Interop Minecraft->Remix", "Remix.Present"};
    private final FrameTimeHistogram realFrames = new FrameTimeHistogram();
    private final long[] outerTotalNanos = new long[OUTER_NAMES.length];
    private final long[] outerMaxNanos = new long[OUTER_NAMES.length];
    private final int[] outerCount = new int[OUTER_NAMES.length];
    private Consumer<StringBuilder> reportAppendix;

    public int registerSection(String name) {
        if (sectionCount >= MAX_SECTIONS) {
            LOGGER.warn("Too many perf sections, ignoring: {}", name);
            return -1;
        }
        int idx = sectionCount++;
        sectionNames[idx] = name;
        sectionPlotNames[idx] = "Java.Section." + name + ".ms";
        sectionMinNanos[idx] = Long.MAX_VALUE;
        return idx;
    }

    /**
     * Registers a named per-frame counter. Call once during initialization.
     * Use {@link #count(int, long)} to add to it each frame.
     */
    public int registerCounter(String name) {
        if (counterCount >= MAX_COUNTERS) {
            LOGGER.warn("Too many perf counters, ignoring: {}", name);
            return -1;
        }
        int idx = counterCount++;
        counterNames[idx] = name;
        counterPlotNames[idx] = "Java.Counter." + name;
        counterMin[idx] = Long.MAX_VALUE;
        return idx;
    }

    public void beginFrame() {
        frameStartNanos = System.nanoTime();
        for (int i = 0; i < counterCount; i++) {
            counterFrameValue[i] = 0;
        }
        for (int i = 0; i < sectionCount; i++) {
            sectionLastNanos[i] = 0;
        }
    }

    public void endFrame() {
        long elapsed = System.nanoTime() - frameStartNanos;
        frameTotalNanos += elapsed;
        frameMinNanos = Math.min(frameMinNanos, elapsed);
        frameMaxNanos = Math.max(frameMaxNanos, elapsed);

        for (int i = 0; i < counterCount; i++) {
            long v = counterFrameValue[i];
            counterLastValue[i] = v;
            counterTotal[i] += v;
            counterMin[i] = Math.min(counterMin[i], v);
            counterMax[i] = Math.max(counterMax[i], v);
        }

        emitProfilePlots(elapsed);

        frameCount++;
        if (!benchmarkMode && frameCount >= REPORT_INTERVAL) {
            logReport();
            reset();
        }
    }

    public void begin(int sectionIdx) {
        if (sectionIdx < 0) return;
        sectionStartNanos[sectionIdx] = System.nanoTime();
    }

    public void end(int sectionIdx) {
        if (sectionIdx < 0) return;
        long elapsed = System.nanoTime() - sectionStartNanos[sectionIdx];
        sectionLastNanos[sectionIdx] = elapsed;
        sectionTotalNanos[sectionIdx] += elapsed;
        sectionMinNanos[sectionIdx] = Math.min(sectionMinNanos[sectionIdx], elapsed);
        sectionMaxNanos[sectionIdx] = Math.max(sectionMaxNanos[sectionIdx], elapsed);
    }

    /**
     * Directly injects a pre-measured duration into a section's accumulators.
     * Use when timing is collected outside begin/end (e.g. accumulated inside
     * lambdas). Called once per frame with the total nanos for that frame.
     */
    public void injectNanos(int sectionIdx, long nanos) {
        if (sectionIdx < 0) return;
        sectionLastNanos[sectionIdx] = nanos;
        sectionTotalNanos[sectionIdx] += nanos;
        sectionMinNanos[sectionIdx] = Math.min(sectionMinNanos[sectionIdx], nanos);
        sectionMaxNanos[sectionIdx] = Math.max(sectionMaxNanos[sectionIdx], nanos);
    }

    /** Adds {@code delta} to a per-frame counter (resets each frame). */
    public void count(int counterIdx, long delta) {
        if (counterIdx < 0) return;
        counterFrameValue[counterIdx] += delta;
    }

    /**
     * Records one world frame's wall-clock period, from the end of the
     * previous Minecraft frame to the end of this one.
     */
    public void recordRealFrame(long periodNanos) {
        realFrames.add(periodNanos);
    }

    /** Adds one sample to an outer row ({@link #OUTER_REMIX_TO_MINECRAFT}, {@link #OUTER_MINECRAFT_TO_REMIX}, {@link #OUTER_PRESENT}). */
    public void recordOuter(int row, long nanos) {
        if (row < 0 || row >= OUTER_NAMES.length || nanos < 0) return;
        outerTotalNanos[row] += nanos;
        outerMaxNanos[row] = Math.max(outerMaxNanos[row], nanos);
        outerCount[row]++;
    }

    /** Appends extra lines to each report; runs only when a report is written. */
    public void setReportAppendix(Consumer<StringBuilder> appendix) {
        this.reportAppendix = appendix;
    }

    private void emitProfilePlots(long frameNanos) {
        if (!ProfileScope.isDeepProfilingEnabled()) return;

        // Plot names are built once at registration: this runs every frame and the
        // bridge interns each distinct name for Tracy's lifetime.
        ProfileScope.plot(FRAME_PLOT_NAME, frameNanos / 1_000_000.0);
        for (int i = 0; i < sectionCount; i++) {
            ProfileScope.plot(sectionPlotNames[i], sectionLastNanos[i] / 1_000_000.0);
        }
        for (int i = 0; i < counterCount; i++) {
            ProfileScope.plot(counterPlotNames[i], counterLastValue[i]);
        }
    }

    private void logReport() {
        if (frameCount == 0) return;

        double avgFrameMs = (frameTotalNanos / (double) frameCount) / 1_000_000.0;
        double minFrameMs = frameMinNanos / 1_000_000.0;
        double maxFrameMs = frameMaxNanos / 1_000_000.0;
        double fps = 1000.0 / avgFrameMs;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n=== Performance Report (%d frames) ===\n", frameCount));
        sb.append(String.format("  Frame total:  avg=%.2fms  min=%.2fms  max=%.2fms  (~%.0f fps)\n",
            avgFrameMs, minFrameMs, maxFrameMs, fps));
        if (realFrames.count() > 0) {
            sb.append(String.format(
                "  Real frame:   avg=%.2fms  p50=%.2fms  p95=%.2fms  p99=%.2fms  max=%.2fms"
                    + "  (%.1f fps real, 1%% low %.1f fps, %d frames)\n",
                realFrames.meanMillis(), realFrames.percentileMillis(0.50),
                realFrames.percentileMillis(0.95), realFrames.percentileMillis(0.99),
                realFrames.maxMillis(), realFrames.fps(), realFrames.onePercentLowFps(),
                realFrames.count()));
        }

        for (int i = 0; i < sectionCount; i++) {
            double avgMs = (sectionTotalNanos[i] / (double) frameCount) / 1_000_000.0;
            double minMs = sectionMinNanos[i] / 1_000_000.0;
            double maxMs = sectionMaxNanos[i] / 1_000_000.0;
            double pct = frameTotalNanos > 0 ? (sectionTotalNanos[i] * 100.0 / frameTotalNanos) : 0;

            sb.append(String.format("  %-20s avg=%.2fms  min=%.2fms  max=%.2fms  (%.1f%%)\n",
                sectionNames[i] + ":", avgMs, minMs, maxMs, pct));
        }

        boolean outerHeader = false;
        for (int i = 0; i < OUTER_NAMES.length; i++) {
            if (outerCount[i] == 0) continue;
            if (!outerHeader) {
                sb.append("  --- Outside renderAndSubmitFrame (per call) ---\n");
                outerHeader = true;
            }
            sb.append(String.format("  %-20s avg=%.2fms  max=%.2fms  (%d calls)\n",
                OUTER_NAMES[i] + ":", outerTotalNanos[i] / (double) outerCount[i] / 1_000_000.0,
                outerMaxNanos[i] / 1_000_000.0, outerCount[i]));
        }

        if (counterCount > 0) {
            sb.append("  --- Counters (per frame) ---\n");
            for (int i = 0; i < counterCount; i++) {
                double avg = counterTotal[i] / (double) frameCount;
                sb.append(String.format("  %-20s avg=%.1f  min=%d  max=%d  total=%d\n",
                    counterNames[i] + ":", avg, counterMin[i], counterMax[i], counterTotal[i]));
            }
        }

        if (reportAppendix != null) {
            reportAppendix.accept(sb);
        }
        sb.append("=====================================");
        LOGGER.info(sb.toString());
    }

    private void reset() {
        frameCount = 0;
        frameTotalNanos = 0;
        frameMinNanos = Long.MAX_VALUE;
        frameMaxNanos = 0;

        for (int i = 0; i < sectionCount; i++) {
            sectionTotalNanos[i] = 0;
            sectionMinNanos[i] = Long.MAX_VALUE;
            sectionMaxNanos[i] = 0;
        }
        for (int i = 0; i < counterCount; i++) {
            counterTotal[i] = 0;
            counterMin[i] = Long.MAX_VALUE;
            counterMax[i] = 0;
        }
        realFrames.reset();
        for (int i = 0; i < OUTER_NAMES.length; i++) {
            outerTotalNanos[i] = 0;
            outerMaxNanos[i] = 0;
            outerCount[i] = 0;
        }
    }

    /** When true, suppresses periodic log reports and accumulates all frames. */
    public void setBenchmarkMode(boolean benchmarkMode) {
        this.benchmarkMode = benchmarkMode;
        if (benchmarkMode) reset();
    }

    /** Returns the most recent frame's elapsed time for a section in nanoseconds. */
    public long getSectionTimeNanos(int sectionIdx) {
        return (sectionIdx >= 0 && sectionIdx < sectionCount) ? sectionLastNanos[sectionIdx] : 0;
    }

    /** Returns the most recent frame's value for a counter. */
    public long getCounterValue(int counterIdx) {
        return (counterIdx >= 0 && counterIdx < counterCount) ? counterLastValue[counterIdx] : 0;
    }

    public int getSectionCount() { return sectionCount; }
    public String getSectionName(int idx) { return sectionNames[idx]; }
    public int getCounterCount() { return counterCount; }
    public String getCounterName(int idx) { return counterNames[idx]; }
}
