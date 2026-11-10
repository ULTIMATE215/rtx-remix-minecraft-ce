/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkyRendererDomeBandScanTest {
    private static final int BANDS = SkyRenderer.DomeBandScan.BANDS;
    private static final int TEXEL_BYTES = 4 * Short.BYTES;

    @Test
    void bandsTileEveryRowExactlyOnce() {
        for (int height : new int[]{512, 1024, 1536, 7, 1, 0}) {
            assertEquals(0, SkyRenderer.DomeBandScan.bandStartRow(height, 0));
            assertEquals(height, SkyRenderer.DomeBandScan.bandStartRow(height, BANDS));
            for (int band = 0; band < BANDS; band++) {
                assertTrue(SkyRenderer.DomeBandScan.bandStartRow(height, band)
                        <= SkyRenderer.DomeBandScan.bandStartRow(height, band + 1));
            }
        }
        assertEquals(256, SkyRenderer.DomeBandScan.bandStartRow(1024, 1));
    }

    @Test
    void viewsCoverTheirBandsAndAreBuiltOncePerBuffer() {
        int width = 8;
        int height = 6;
        ByteBuffer source = image(width, height);
        SkyRenderer.DomeBandScan scan = new SkyRenderer.DomeBandScan();
        // The compositor moves position and limit; views must not depend on them.
        source.position(5).limit(width * height * TEXEL_BYTES);
        scan.retarget(source, width, height);
        ByteBuffer[] first = new ByteBuffer[BANDS];
        for (int band = 0; band < BANDS; band++) {
            ByteBuffer view = scan.bandView(band);
            first[band] = view;
            int startRow = SkyRenderer.DomeBandScan.bandStartRow(height, band);
            int rows = SkyRenderer.DomeBandScan.bandStartRow(height, band + 1) - startRow;
            assertEquals(rows * width * TEXEL_BYTES, view.capacity());
            assertEquals(0, view.position());
            if (rows > 0) {
                assertEquals(startRow, view.get(0), "a view starts at its band's first row");
            }
        }
        scan.stop();
        scan.retarget(source, width, height);
        for (int band = 0; band < BANDS; band++) {
            assertSame(first[band], scan.bandView(band), "the same buffer reuses its views");
        }
        scan.forgetViews();
        scan.retarget(source, width, height);
        assertNotSame(first[0], scan.bandView(0), "forgotten views are rebuilt");
    }

    @Test
    void aScanUploadsEachBandOnceAndThenEnds() {
        SkyRenderer.DomeBandScan scan = new SkyRenderer.DomeBandScan();
        assertFalse(scan.active());
        assertThrows(IllegalStateException.class, scan::nextBand);
        scan.retarget(image(4, 8), 4, 8);
        Set<Integer> uploaded = new HashSet<>();
        for (int frame = 0; frame < BANDS; frame++) {
            assertTrue(scan.active());
            assertTrue(uploaded.add(scan.nextBand()));
        }
        assertFalse(scan.active());
        assertEquals(BANDS, uploaded.size());
    }

    @Test
    void aNewImageMidScanContinuesFromTheCurrentBandAndOwesEveryBand() {
        SkyRenderer.DomeBandScan scan = new SkyRenderer.DomeBandScan();
        ByteBuffer older = image(4, 8);
        ByteBuffer newer = image(4, 8, 100);
        scan.retarget(older, 4, 8);
        assertEquals(0, scan.nextBand());
        assertEquals(1, scan.nextBand());
        scan.retarget(newer, 4, 8);
        int[] order = new int[BANDS];
        for (int frame = 0; frame < BANDS; frame++) {
            order[frame] = scan.nextBand();
            assertEquals(100 + SkyRenderer.DomeBandScan.bandStartRow(8, order[frame]),
                    scan.bandView(order[frame]).get(0), "bands now read the newer image");
        }
        assertArrayEquals(new int[]{2, 3, 0, 1}, order,
                "the rolling scan resumes where it was, so the stalest bands go first");
        assertFalse(scan.active());
    }

    @Test
    void stopEndsTheScanButKeepsTheCursor() {
        SkyRenderer.DomeBandScan scan = new SkyRenderer.DomeBandScan();
        ByteBuffer source = image(4, 8);
        scan.retarget(source, 4, 8);
        scan.nextBand();
        scan.stop();
        assertFalse(scan.active());
        scan.retarget(source, 4, 8);
        assertEquals(1, scan.nextBand());
    }

    @Test
    void aNewImageSizeRebuildsTheViews() {
        SkyRenderer.DomeBandScan scan = new SkyRenderer.DomeBandScan();
        ByteBuffer source = image(8, 8);
        scan.retarget(source, 4, 8);
        ByteBuffer small = scan.bandView(0);
        scan.retarget(source, 8, 8);
        assertNotSame(small, scan.bandView(0));
        assertEquals(2 * 8 * TEXEL_BYTES, scan.bandView(0).capacity());
        assertEquals(8, scan.width());
        assertEquals(8, scan.height());
    }

    private static ByteBuffer image(int width, int height) {
        return image(width, height, 0);
    }

    /** An RGBA16F image whose first byte in each row is the row index plus a tag. */
    private static ByteBuffer image(int width, int height, int tag) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * TEXEL_BYTES);
        for (int row = 0; row < height; row++) {
            pixels.put(row * width * TEXEL_BYTES, (byte) (row + tag));
        }
        return pixels;
    }
}
