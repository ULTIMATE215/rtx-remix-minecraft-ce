/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientDoneTimelineTest {

    @Test
    void acceptedSignalsAdvanceThePresentedValue() {
        ClientDoneTimeline timeline = new ClientDoneTimeline();
        assertEquals(0L, timeline.valueToPresent());

        assertEquals(1L, timeline.beginSignal());
        timeline.signalAccepted();
        assertEquals(1L, timeline.valueToPresent());

        assertEquals(2L, timeline.beginSignal());
        assertEquals(1L, timeline.valueToPresent(), "a pending signal is not presentable yet");
        timeline.signalAccepted();
        assertEquals(2L, timeline.valueToPresent());
    }

    @Test
    void aFailedSignalIsNeverPresentedAndItsValueIsNeverReused() {
        ClientDoneTimeline timeline = new ClientDoneTimeline();
        timeline.beginSignal();
        timeline.signalAccepted();

        long failed = timeline.beginSignal();
        timeline.signalFailed();
        assertEquals(1L, timeline.valueToPresent());

        long next = timeline.beginSignal();
        assertTrue(next > failed, "The driver may already hold the failed value");
        timeline.signalAccepted();
        assertEquals(next, timeline.valueToPresent());
        assertEquals(next, timeline.lastRequested());
    }

    @Test
    void signalsMustBeResolvedExactlyOnce() {
        ClientDoneTimeline timeline = new ClientDoneTimeline();
        assertThrows(IllegalStateException.class, timeline::signalAccepted);
        assertThrows(IllegalStateException.class, timeline::signalFailed);

        timeline.beginSignal();
        assertThrows(IllegalStateException.class, timeline::beginSignal);
        timeline.signalAccepted();
        assertThrows(IllegalStateException.class, timeline::signalAccepted);
    }
}
