/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.clouds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloudReadbackRingTest {
    private static final int SIZE = CloudSkyCompositor.READBACK_RING_SIZE;

    @Test
    void withoutAHoldEverySlotCanBeInFlight() {
        CloudSkyCompositor.ReadbackRing ring = new CloudSkyCompositor.ReadbackRing(SIZE);
        for (int i = 0; i < SIZE; i++) {
            assertTrue(ring.canEnqueue());
            assertEquals(i, ring.writeIndex());
            ring.enqueued();
        }
        assertFalse(ring.canEnqueue());
        assertThrows(IllegalStateException.class, ring::enqueued);
        assertEquals(0, ring.consumeOldest(false));
        assertFalse(ring.holds());
        assertTrue(ring.canEnqueue(), "a slot dropped unread is free at once");
    }

    @Test
    void theHeldSlotIsNeverTheNextWriteTarget() {
        CloudSkyCompositor.ReadbackRing ring = new CloudSkyCompositor.ReadbackRing(SIZE);
        ring.enqueued();
        int held = ring.consumeOldest(true);
        assertTrue(ring.holds());
        assertEquals(held, ring.heldIndex());
        // Fill the ring while the held slot is being uploaded from, over many rotations.
        for (int frame = 0; frame < 50; frame++) {
            while (ring.canEnqueue()) {
                assertNotEquals(ring.heldIndex(), ring.writeIndex());
                ring.enqueued();
            }
            assertEquals(SIZE, ring.pending() + 1, "pending + held fills the ring exactly");
            // A newer readback lands: the hold moves to it and the old slot rejoins the ring.
            held = ring.consumeOldest(true);
            assertEquals(held, ring.heldIndex());
        }
    }

    @Test
    void releasingTheHoldReturnsItsSlot() {
        CloudSkyCompositor.ReadbackRing ring = new CloudSkyCompositor.ReadbackRing(SIZE);
        ring.enqueued();
        int held = ring.consumeOldest(true);
        for (int i = 0; i < SIZE - 1; i++) {
            ring.enqueued();
        }
        assertFalse(ring.canEnqueue(), "the held slot is the only one not in flight");
        ring.release();
        assertFalse(ring.holds());
        assertEquals(-1, ring.heldIndex());
        assertTrue(ring.canEnqueue());
        assertEquals(held, ring.writeIndex(), "the released slot is the next one written");
        ring.enqueued();
        assertFalse(ring.canEnqueue(), "every slot is now in flight");
    }

    @Test
    void droppingStaleSlotsBehindAHoldNeverExposesIt() {
        // The compositor releases the hold whenever it discards the scene, so this cannot
        // happen there; the ring must still refuse to overwrite a hold if it ever did.
        CloudSkyCompositor.ReadbackRing ring = new CloudSkyCompositor.ReadbackRing(SIZE);
        ring.enqueued();
        int held = ring.consumeOldest(true);
        ring.enqueued();
        ring.consumeOldest(false);
        for (int frame = 0; frame < 10; frame++) {
            while (ring.canEnqueue()) {
                assertNotEquals(held, ring.writeIndex());
                ring.enqueued();
            }
            if (ring.pending() > 0) {
                ring.consumeOldest(false);
            }
        }
        assertEquals(held, ring.heldIndex());
    }

    @Test
    void resetForgetsEverything() {
        CloudSkyCompositor.ReadbackRing ring = new CloudSkyCompositor.ReadbackRing(SIZE);
        ring.enqueued();
        ring.enqueued();
        ring.consumeOldest(true);
        ring.reset();
        assertEquals(0, ring.pending());
        assertFalse(ring.holds());
        assertEquals(0, ring.writeIndex());
        assertThrows(IllegalStateException.class, () -> ring.consumeOldest(false));
    }
}
