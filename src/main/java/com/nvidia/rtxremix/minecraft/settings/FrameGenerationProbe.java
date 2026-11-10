/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.ToIntBiFunction;

/**
 * Follows whether Remix's DLSS frame generation is on, so {@link CameraEffectsSettings} can
 * adapt motion blur to it.
 *
 * <p>The player turns frame generation on and off in Remix's own graphics menu, so the mod
 * never writes {@code rtx.dlfg.enable} and must read it back. The read is cheap but not free,
 * and the value only changes while a Remix menu is open, so it is read once at startup, again
 * after a menu closes, and otherwise every {@link #POLL_INTERVAL_FRAMES} frames to catch a
 * change made any other way, such as a conf layer or a console write. Every read goes into one
 * buffer this probe owns, so polling allocates nothing.
 *
 * <p>A failed read keeps the last value and logs once per run of failures: the runtime rejects
 * reads only in states it recovers from, and blur that keeps its last adaptation is harmless.
 */
public final class FrameGenerationProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrameGenerationProbe.class);

    static final String FRAME_GENERATION_OPTION = "rtx.dlfg.enable";
    /** Frames between reads when nothing asked for one sooner. */
    static final int POLL_INTERVAL_FRAMES = 120;
    /**
     * Frames after a menu closes before the read. A choice made in the menu resolves at the
     * runtime's option update at the end of a frame, so the read waits one frame for it.
     */
    static final int MENU_CLOSE_POLL_DELAY_FRAMES = 1;

    /**
     * Receives each read. It fits every spelling of a boolean the runtime writes, {@code "False"}
     * being the longest, with its NUL byte; a value that does not fit is not a true value.
     */
    private final byte[] resolvedValue = new byte[8];
    private boolean enabled;
    /** Frames left until the next read; 0 reads on the next {@link #onFrame}. */
    private int framesUntilPoll;
    private boolean readFailureLogged;

    /** Whether frame generation was on at the last successful read. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Reads on the next {@link #onFrame} that comes after {@code frames} more frames at most. */
    public void requestPoll(int frames) {
        framesUntilPoll = Math.min(framesUntilPoll, Math.max(0, frames));
    }

    /** Asks for a read shortly after a Remix menu closed, where a frame-generation change is made. */
    public void onRemixMenuClosed() {
        requestPoll(MENU_CLOSE_POLL_DELAY_FRAMES);
    }

    /**
     * Counts a frame and reads the option when a read is due. Call once per frame on the render
     * thread.
     *
     * @param configReader Remix's {@code GetConfigVariableResolved} into a caller-owned buffer,
     *                     returning the value's size including its NUL byte
     * @return whether frame generation is on, as last read
     */
    public boolean onFrame(ToIntBiFunction<String, byte[]> configReader) {
        if (framesUntilPoll > 0) {
            framesUntilPoll--;
            return enabled;
        }
        return poll(configReader);
    }

    /**
     * Reads the option now, keeping the last value when the read fails, and restarts the
     * interval to the next read.
     */
    public boolean poll(ToIntBiFunction<String, byte[]> configReader) {
        framesUntilPoll = POLL_INTERVAL_FRAMES;
        int size;
        try {
            size = configReader.applyAsInt(FRAME_GENERATION_OPTION, resolvedValue);
        } catch (RuntimeException e) {
            if (!readFailureLogged) {
                LOGGER.warn("Failed to read {}, so motion blur keeps its frame-generation state ({})"
                        + " until a read succeeds", FRAME_GENERATION_OPTION, enabled, e);
                readFailureLogged = true;
            }
            return enabled;
        }
        readFailureLogged = false;
        boolean value = parseEnabled(resolvedValue, size);
        if (value != enabled) {
            LOGGER.info("Remix frame generation is {}; motion blur {}", value ? "on" : "off",
                    value ? "adapts to it" : "follows the shutter time again");
            enabled = value;
        }
        return enabled;
    }

    /**
     * Whether a resolved value spells true: {@code true} in any case, or {@code 1}. Compares
     * bytes in place, so a read allocates nothing.
     *
     * @param size the value's size including its NUL byte, as the reader returned it
     */
    static boolean parseEnabled(byte[] value, int size) {
        if (size > value.length) {
            return false;
        }
        int length = size - 1;
        if (length == 1) {
            return value[0] == '1';
        }
        if (length != 4) {
            return false;
        }
        return (value[0] | 0x20) == 't'
                && (value[1] | 0x20) == 'r'
                && (value[2] | 0x20) == 'u'
                && (value[3] | 0x20) == 'e';
    }
}
