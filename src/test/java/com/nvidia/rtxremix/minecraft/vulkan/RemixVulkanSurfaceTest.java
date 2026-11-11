/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemixVulkanSurfaceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownCompletesTheOuterSurfaceAndSubmitsBeforePresent(boolean alreadyBlitted) throws Exception {
        List<String> events = new ArrayList<>();
        try (GpuSurface surface = surface(events); GpuTextureView texture = texture()) {
            CommandEncoder encoder = new CommandEncoder(null, null, null) {
                @Override public void submit() { events.add("submit"); }
            };
            surface.acquireNextTexture();
            if (alreadyBlitted) surface.blitFromTexture(encoder, texture);
            RemixVulkanSurface.completeAcquiredFrame(surface, encoder, texture, alreadyBlitted);
            assertEquals(List.of("blit", "submit", "present"), events);
            assertFalse(surface.isAcquired());
            // A second shutdown pass must not copy, signal or present again.
            RemixVulkanSurface.completeAcquiredFrame(surface, encoder, texture, true);
            assertEquals(3, events.size());
        }
    }

    @Test
    void aFailedSubmissionNeverPresentsAnUnsignaledFrame() throws Exception {
        List<String> events = new ArrayList<>();
        GpuSurface surface = surface(events);
        try (GpuTextureView texture = texture()) {
            CommandEncoder encoder = new CommandEncoder(null, null, null) {
                @Override public void submit() { throw new IllegalStateException("device lost"); }
            };
            surface.acquireNextTexture();
            assertThrows(IllegalStateException.class,
                    () -> RemixVulkanSurface.completeAcquiredFrame(surface, encoder, texture, false));
            assertEquals(List.of("blit"), events);
            assertTrue(surface.isAcquired());
        }
    }

    private static GpuSurface surface(List<String> events) throws SurfaceException {
        GpuSurface surface = new GpuSurface(new GpuSurfaceBackend() {
            @Override public void configure(GpuSurface.Configuration configuration) {}
            @Override public boolean isSuboptimal() { return false; }
            @Override public void acquireNextTexture() {}
            @Override public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView texture) { events.add("blit"); }
            @Override public void present() { events.add("present"); }
            @Override public Collection<GpuSurface.PresentMode> supportedPresentModes() { return List.of(GpuSurface.PresentMode.FIFO); }
            @Override public void close() {}
        });
        surface.configure(new GpuSurface.Configuration(16, 16, GpuSurface.PresentMode.FIFO));
        return surface;
    }

    private static GpuTextureView texture() {
        GpuTexture texture = new GpuTexture(GpuTexture.USAGE_COPY_SRC, "frame", GpuFormat.RGBA8_UNORM, 16, 16, 1, 1) {
            @Override public void close() {}
            @Override public boolean isClosed() { return false; }
        };
        return new GpuTextureView(texture, 0, 1) {
            @Override public void close() { texture.close(); }
            @Override public boolean isClosed() { return false; }
        };
    }
}
