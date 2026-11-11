/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.minecraft.mixin.VulkanCommandEncoderAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Shares the pinned Remix runtime's color, primary depth and timeline semaphores
 * with Minecraft's own Vulkan device. All methods run on the render thread.
 *
 * <p>The runtime keeps ownership between frames. Each frame acquires both images
 * after its timeline wait and releases them before signaling client completion.
 * Minecraft's encoder owns all command buffers, submissions and deferred frees.
 */
public final class VulkanInterop implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanInterop.class);
    private final VulkanDevice device;
    private final Semaphores semaphores;
    private final long remixSemaphoreHandle;
    private final long clientSemaphoreHandle;
    private Image color;
    private Image depth;
    private long acquiredValue;
    private final ClientDoneTimeline clientDone = new ClientDoneTimeline();
    private long signalSubmission = -1;
    private boolean acquired;
    private boolean copiedOutput;
    private boolean closed;

    public VulkanInterop(VulkanDevice device, InteropInfo info) {
        RenderSystem.assertOnRenderThread();
        requireSupport(device);
        this.device = device;
        remixSemaphoreHandle = info.dxvkDoneSemaphore_Handle;
        clientSemaphoreHandle = info.clientDoneSemaphore_Handle;
        semaphores = new Semaphores(device.vkDevice(), remixSemaphoreHandle, clientSemaphoreHandle);
        LOGGER.info("Remix interop uses Minecraft's Vulkan device: {}", device.getDeviceInfo().name());
    }

    public static void requireSupport(VulkanDevice device) {
        var capabilities = device.vkDevice().getCapabilities();
        if (!capabilities.VK_KHR_external_memory_win32 || !capabilities.VK_KHR_external_semaphore_win32) {
            throw new IllegalStateException("RTX Remix requires Vulkan Win32 external memory and semaphore support");
        }
    }

    public VulkanCommandEncoder encoder() { return device.createCommandEncoder(); }

    /** Call after Remix Submit, including on menu frames and while RTX is off. */
    public boolean acquire(InteropInfo info) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (info.dxvkDoneSemaphore_Handle != remixSemaphoreHandle
                || info.clientDoneSemaphore_Handle != clientSemaphoreHandle) {
            throw new IllegalStateException("Remix changed the interop semaphores inside a live session");
        }
        if (acquired) {
            if (acquiredValue != info.dxvkDoneSemaphore_ValueToWait) {
                throw new IllegalStateException("Remix submitted another frame before the shared images were released");
            }
            return false;
        }

        clientDoneValue();
        boolean changed = recreateImages(info);
        VulkanCommandEncoder encoder = encoder();
        encoder.waitSemaphore(semaphores.remixDone, info.dxvkDoneSemaphore_ValueToWait,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        recordOwnership(encoder, true);
        acquiredValue = info.dxvkDoneSemaphore_ValueToWait;
        acquired = true;
        copiedOutput = false;
        return changed;
    }

    /**
     * Publishes ordinary Minecraft textures for compositing and depth-aware
     * weather. Minecraft's render targets are vertically inverted relative to
     * the runtime; this is the one conversion on the way into Minecraft.
     */
    public GpuTextureView copyOutput() {
        requireAcquired();
        if (!copiedOutput) {
            VulkanCommandEncoder encoder = encoder();
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(commands, stack);
                copyOutputImage(commands, color, stack);
                copyOutputImage(commands, depth, stack);
                VulkanCommandEncoder.memoryBarrier(commands, stack);
            }
            check(vkEndCommandBuffer(commands), "finish Remix output copy");
            encoder.execute(commands);
            copiedOutput = true;
        }
        return color == null ? null : color.view;
    }

    public GpuTextureView depthTexture() {
        return copiedOutput && depth != null ? depth.view : null;
    }

    /** Copies the fully composited Minecraft target back before Remix draws its menus. */
    public void copyFromMinecraft(VulkanCommandEncoder encoder, GpuTextureView source) {
        requireAcquired();
        if (encoder != encoder()) {
            throw new IllegalArgumentException("Interop must use Minecraft's Vulkan command encoder");
        }
        try {
            if (color != null) {
                VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(commands, stack);
                    VulkanInteropImage shared = color.shared;
                    color.transfer.toShared(commands,
                            VulkanTextureBlit.image(source), source.baseMipLevel(), source.getWidth(0), source.getHeight(0),
                            shared.image(), stack);
                    VulkanCommandEncoder.memoryBarrier(commands, stack);
                }
                check(vkEndCommandBuffer(commands), "finish Minecraft frame copy");
                encoder.execute(commands);
            }
        } finally {
            // Present still waits for client completion when a frame copy fails.
            // Hand ownership back so that a recoverable copy error cannot stall it.
            release();
        }
    }

    /** The signal is submitted by Minecraft before its GpuSurface.present call. */
    private void release() {
        if (!acquired) return;
        VulkanCommandEncoder encoder = encoder();
        recordOwnership(encoder, false);
        long value = clientDone.beginSignal();
        try {
            encoder.signalSemaphore(semaphores.clientDone, value, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            signalSubmission = ((VulkanCommandEncoderAccessor) encoder).rtxremix$currentSubmitIndex();
        } catch (RuntimeException | Error failure) {
            clientDone.signalFailed();
            throw failure;
        }
        acquired = false;
    }

    /** Present runs after Minecraft submits its encoder; recording a signal alone is insufficient. */
    public long clientDoneValue() {
        if (signalSubmission != -1) {
            if (((VulkanCommandEncoderAccessor) encoder()).rtxremix$currentSubmitIndex() <= signalSubmission) {
                throw new IllegalStateException("Remix Present preceded the Vulkan client-done submission");
            }
            clientDone.signalAccepted();
            signalSubmission = -1;
        }
        return clientDone.valueToPresent();
    }

    /** Completes recorded client work before an import is replaced or the runtime is destroyed. */
    public void finishGpuWork() {
        encoder().submit();
        clientDoneValue();
        device.graphicsQueue().waitIdle();
    }

    private boolean recreateImages(InteropInfo info) {
        boolean sameColor = matches(color, info.imageMemory_Handle, info.allocationSize,
                info.width, info.height, info.interopImageFormat);
        boolean sameDepth = matches(depth, info.depthImageMemory_Handle, info.depthAllocationSize,
                info.depthWidth, info.depthHeight, info.interopDepthImageFormat);
        if (sameColor && sameDepth) return false;
        // No previously submitted command may retain an import of the replaced allocation.
        finishGpuWork();
        Image nextColor = color;
        Image nextDepth = depth;
        try {
            nextColor = imageFor(color, info.imageMemory_Handle, info.allocationSize,
                    info.width, info.height, info.interopImageFormat, true);
            nextDepth = imageFor(depth, info.depthImageMemory_Handle, info.depthAllocationSize,
                    info.depthWidth, info.depthHeight, info.interopDepthImageFormat, false);
        } catch (RuntimeException | Error failure) {
            if (nextColor != color && nextColor != null) nextColor.close();
            if (nextDepth != depth && nextDepth != null) nextDepth.close();
            throw failure;
        }
        if (nextColor != color && color != null) color.close();
        if (nextDepth != depth && depth != null) depth.close();
        color = nextColor;
        depth = nextDepth;
        return true;
    }

    private static boolean matches(Image image, long handle, long bytes, int width, int height, int format) {
        if (handle == 0 || handle == -1L) return image == null;
        return image != null && image.shared.matches(handle, bytes, width, height, format);
    }

    private Image imageFor(Image current, long handle, long bytes, int width, int height, int format, boolean color) {
        if (handle == 0 || handle == -1L) return null;
        if (current != null && current.shared.matches(handle, bytes, width, height, format)) return current;
        return new Image(handle, bytes, width, height, format, color);
    }

    private static void copyOutputImage(VkCommandBuffer commands, Image image, MemoryStack stack) {
        if (image == null) return;
        VulkanInteropImage shared = image.shared;
        image.transfer.fromShared(commands, shared.image(), VulkanTextureBlit.image(image.view), 0, stack);
    }

    private void recordOwnership(VulkanCommandEncoder encoder, boolean acquire) {
        int count = (color == null ? 0 : 1) + (depth == null ? 0 : 1);
        if (count == 0) return;
        VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barriers = VkImageMemoryBarrier.calloc(count, stack);
            int index = 0;
            if (color != null) ownershipBarrier(barriers.get(index++), color.shared, acquire);
            if (depth != null) ownershipBarrier(barriers.get(index), depth.shared, acquire);
            vkCmdPipelineBarrier(commands,
                    acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    acquire ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0, null, null, barriers);
        }
        check(vkEndCommandBuffer(commands), "finish shared image ownership transfer");
        encoder.execute(commands);
    }

    private void ownershipBarrier(VkImageMemoryBarrier barrier, VulkanInteropImage image, boolean acquire) {
        int family = device.graphicsQueue().queueFamilyIndex();
        barrier.sType$Default().image(image.image())
                // Never use UNDEFINED here: that would discard the runtime's output.
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(acquire ? image.externalFamily : family)
                .dstQueueFamilyIndex(acquire ? family : image.externalFamily)
                .srcAccessMask(acquire ? 0 : VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(acquire ? VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT : 0);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Remix Vulkan interop is closed");
    }

    private void requireAcquired() {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (!acquired) throw new IllegalStateException("The Remix image has not been acquired for this frame");
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        release();
        finishGpuWork();
        if (color != null) color.close();
        if (depth != null) depth.close();
        color = null;
        depth = null;
        encoder().queueForDestroy(semaphores);
        // Drain recorded work before Minecraft tears down its surface/device.
        // Destruction remains deferred until the encoder's completion timeline.
        finishGpuWork();
        closed = true;
    }

    static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Vulkan failed to " + operation + " (result " + result + ")");
        }
    }

    private final class Image implements AutoCloseable {
        final VulkanInteropImage shared;
        private VulkanFrameTransfer transfer;
        private GpuTexture texture;
        private GpuTextureView view;

        Image(long handle, long bytes, int width, int height, int format, boolean color) {
            shared = new VulkanInteropImage(device.vkDevice(), handle, bytes, width, height, format, color);
            try {
                transfer = new VulkanFrameTransfer(VulkanWork.minecraft(), width, height, VulkanInteropImage.vkFormat(format));
                GpuFormat snapshotFormat = switch (format) {
                    case TextureFormat.RGBA16_SFLOAT -> GpuFormat.RGBA16_FLOAT;
                    case TextureFormat.RGBA32_SFLOAT -> GpuFormat.RGBA32_FLOAT;
                    case TextureFormat.R32_SFLOAT -> GpuFormat.R32_FLOAT;
                    case TextureFormat.RGBA8_UNORM, TextureFormat.BGRA8_UNORM -> GpuFormat.RGBA8_UNORM;
                    case TextureFormat.RGBA8_SRGB, TextureFormat.BGRA8_SRGB -> GpuFormat.RGBA8_UNORM;
                    default -> throw new IllegalArgumentException("Unsupported Remix image format: " + format);
                };
                texture = device.createTexture(color ? "Remix color" : "Remix primary depth",
                        GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        snapshotFormat, width, height, 1, 1);
                view = device.createTextureView(texture);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
            LOGGER.info("Imported Remix {} image: {}x{}, format {}", color ? "color" : "depth", width, height, format);
        }

        @Override
        public void close() {
            if (view != null) view.close();
            if (texture != null) texture.close();
            if (transfer != null) encoder().queueForDestroy(transfer);
            encoder().queueForDestroy(shared);
        }
    }

    private static final class Semaphores implements Destroyable {
        private final VkDevice device;
        private long remixDone;
        private long clientDone;

        Semaphores(VkDevice device, long remixHandle, long clientHandle) {
            this.device = device;
            try {
                remixDone = importTimeline(remixHandle);
                clientDone = importTimeline(clientHandle);
            } catch (RuntimeException | Error failure) {
                destroy();
                throw failure;
            }
        }

        private long importTimeline(long sharedHandle) {
            if (sharedHandle == 0 || sharedHandle == -1L) {
                throw new IllegalArgumentException("Remix shared semaphore handles must be valid");
            }
            var description = RemixAPI.describeVulkanSemaphore(sharedHandle);
            if (description.type != VK_SEMAPHORE_TYPE_TIMELINE) {
                throw new IllegalStateException("Remix exported a non-timeline semaphore");
            }
            VulkanDeviceIdentity.requireMatch(device, description.deviceUuidLow, description.deviceUuidHigh,
                    description.driverUuidLow, description.driverUuidHigh);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var timeline = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                        .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
                var create = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(timeline);
                var handle = stack.mallocLong(1);
                check(vkCreateSemaphore(device, create, null, handle), "create interop timeline semaphore");
                long semaphore = handle.get(0);
                try {
                    check(VulkanWin32SemaphoreImport.importTimeline(device.address(), semaphore, sharedHandle,
                                    device.getCapabilities().vkImportSemaphoreWin32HandleKHR),
                            "import interop timeline semaphore");
                    return semaphore;
                } catch (RuntimeException | Error failure) {
                    vkDestroySemaphore(device, semaphore, null);
                    throw failure;
                }
            }
        }

        @Override
        public void destroy() {
            if (remixDone != 0) vkDestroySemaphore(device, remixDone, null);
            if (clientDone != 0) vkDestroySemaphore(device, clientDone, null);
            remixDone = 0;
            clientDone = 0;
        }
    }
}
