/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.ToIntBiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameGenerationProbeTest {

    /** A reader that answers with {@code value[0]} and counts its calls in {@code reads[0]}. */
    private static ToIntBiFunction<String, byte[]> reader(String[] value, int[] reads) {
        return (key, buffer) -> {
            assertEquals(FrameGenerationProbe.FRAME_GENERATION_OPTION, key);
            reads[0]++;
            byte[] bytes = (value[0] + '\0').getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= buffer.length) {
                System.arraycopy(bytes, 0, buffer, 0, bytes.length);
            }
            return bytes.length;
        };
    }

    private static boolean parse(String value) {
        byte[] buffer = new byte[8];
        byte[] bytes = (value + '\0').getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= buffer.length) {
            System.arraycopy(bytes, 0, buffer, 0, bytes.length);
        }
        return FrameGenerationProbe.parseEnabled(buffer, bytes.length);
    }

    @Test
    void everySpellingOfTrueIsOnAndAnythingElseIsOff() {
        assertTrue(parse("True"));
        assertTrue(parse("true"));
        assertTrue(parse("TRUE"));
        assertTrue(parse("1"));
        assertFalse(parse("False"));
        assertFalse(parse("false"));
        assertFalse(parse("0"));
        assertFalse(parse(""));
        assertFalse(parse("truer"));
        assertFalse(parse("a value far too long for the buffer"));
    }

    @Test
    void readsOnTheFirstFrameThenEveryIntervalAndSoonAfterAMenuCloses() {
        String[] value = {"False"};
        int[] reads = {0};
        ToIntBiFunction<String, byte[]> reader = reader(value, reads);
        FrameGenerationProbe probe = new FrameGenerationProbe();

        assertFalse(probe.onFrame(reader));
        assertEquals(1, reads[0]);

        value[0] = "True";
        for (int frame = 0; frame < FrameGenerationProbe.POLL_INTERVAL_FRAMES; frame++) {
            assertFalse(probe.onFrame(reader), "no read before the interval");
        }
        assertEquals(1, reads[0]);
        assertTrue(probe.onFrame(reader));
        assertEquals(2, reads[0]);

        value[0] = "False";
        probe.onRemixMenuClosed();
        assertTrue(probe.onFrame(reader), "waits a frame for the menu's change to resolve");
        assertFalse(probe.onFrame(reader));
        assertEquals(3, reads[0]);
        assertFalse(probe.isEnabled());
    }

    @Test
    void aFailedReadKeepsTheLastValue() {
        String[] value = {"True"};
        int[] reads = {0};
        FrameGenerationProbe probe = new FrameGenerationProbe();
        assertTrue(probe.poll(reader(value, reads)));

        ToIntBiFunction<String, byte[]> failing = (key, buffer) -> {
            throw new IllegalStateException("not ready");
        };
        assertTrue(probe.poll(failing));
        assertTrue(probe.poll(failing));
        assertTrue(probe.isEnabled());

        value[0] = "False";
        assertFalse(probe.poll(reader(value, reads)));
    }
}
