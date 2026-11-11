/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.nvidia.rtxremix.minecraft.mixin.GpuSurfaceAccessor;

import java.util.Collection;
import java.util.List;

/**
 * Keeps Minecraft's acquire/blit/present lifecycle while Remix owns the HWND's
 * swapchain. Before takeover, the same surface delegates to vanilla Vulkan.
 */
public final class RemixVulkanSurface implements GpuSurfaceBackend {
    public interface FrameSink {
        void copyMinecraftFrame(VulkanCommandEncoder encoder, GpuTextureView texture);
        void presentFrameVk();
    }

    private final VulkanDevice device;
    private final List<GpuSurface.PresentMode> presentModes;
    private GpuSurfaceBackend vanilla;
    private FrameSink remix;
    private boolean acquired;
    private boolean closed;

    public RemixVulkanSurface(VulkanDevice device, GpuSurfaceBackend vanilla) {
        this.device = device;
        this.vanilla = vanilla;
        presentModes = List.copyOf(vanilla.supportedPresentModes());
    }

    public static boolean isSupported(GpuSurface surface) {
        return surface != null && ((GpuSurfaceAccessor) surface).rtxremix$backend() instanceof RemixVulkanSurface;
    }

    public static RemixVulkanSurface from(GpuSurface surface) {
        if (((GpuSurfaceAccessor) surface).rtxremix$backend() instanceof RemixVulkanSurface remix) {
            return remix;
        }
        throw new IllegalStateException("RTX Remix requires Minecraft's Vulkan graphics backend");
    }

    public VulkanDevice device() { return device; }

    /** Must precede Remix Startup, so the two backends never own live swapchains together. */
    public void takeOwnership(FrameSink sink) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (acquired || remix != null) {
            throw new IllegalStateException("Cannot transfer an acquired or already transferred surface");
        }
        device.createCommandEncoder().submit();
        vanilla.close();
        vanilla = null;
        remix = sink;
    }

    public boolean isOwnedBy(FrameSink sink) { return remix == sink; }

    /** Finish the outer surface's frame before its source texture or interop is destroyed. */
    public void finishPendingFrame(GpuSurface surface, GpuTextureView texture) {
        requireOpen();
        if (from(surface) != this || remix == null) {
            throw new IllegalArgumentException("The surface is not owned by this Remix presenter");
        }
        if (!surface.isAcquired()) return;
        completeAcquiredFrame(surface, RenderSystem.getDevice().createCommandEncoder(), texture,
                ((GpuSurfaceAccessor) surface).rtxremix$hasBlittedTexture());
    }

    static void completeAcquiredFrame(GpuSurface surface, CommandEncoder encoder,
                                      GpuTextureView texture, boolean hasBlittedTexture) {
        if (!surface.isAcquired()) return;
        if (!hasBlittedTexture) {
            surface.blitFromTexture(encoder, texture);
        }
        // The interop release and timeline signal recorded by blit must reach
        // the queue before Remix waits on them. Present also releases GpuSurface's
        // acquisition; clearing only this backend's flag cannot do that.
        encoder.submit();
        surface.present();
    }

    @Override
    public void configure(GpuSurface.Configuration configuration) throws SurfaceException {
        requireOpen();
        if (acquired) throw new IllegalStateException("Cannot resize an acquired surface");
        if (vanilla != null) vanilla.configure(configuration);
        // Remix reads the HWND's current client extent at Submit. The next
        // GetInteropInfo then supplies the matching images for this size.
    }

    @Override
    public boolean isSuboptimal() {
        return vanilla != null && vanilla.isSuboptimal();
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        requireOpen();
        if (acquired) throw new IllegalStateException("Surface already acquired");
        if (vanilla != null) vanilla.acquireNextTexture();
        acquired = true;
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView texture) {
        requireOpen();
        if (!acquired) throw new IllegalStateException("Surface has not been acquired");
        if (vanilla != null) {
            vanilla.blitFromTexture(encoder, texture);
        } else {
            if (encoder != device.createCommandEncoder()) {
                throw new IllegalArgumentException("The surface belongs to a different Vulkan device");
            }
            remix.copyMinecraftFrame((VulkanCommandEncoder) encoder, texture);
        }
    }

    @Override
    public void present() {
        requireOpen();
        if (!acquired) throw new IllegalStateException("Surface has not been acquired");
        try {
            if (vanilla != null) vanilla.present();
            else remix.presentFrameVk();
        } finally {
            acquired = false;
        }
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() { return presentModes; }

    @Override
    public void close() {
        if (closed) return;
        if (vanilla != null) vanilla.close();
        vanilla = null;
        remix = null;
        closed = true;
    }

    private void requireOpen() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Minecraft's window surface is closed");
    }
}
