/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static com.nvidia.rtxremix.minecraft.vulkan.VulkanSync.check;

/** Owned compute buffer; callers retire it only after its last queued command. */
public final class VulkanBuffer implements Destroyable {
    private final VulkanWork work;
    private final int bytes;
    private long buffer;
    private long allocation;
    private ByteBuffer mapped;

    public VulkanBuffer(VulkanWork work, int bytes, int usage, boolean hostVisible) {
        this.work = work;
        this.bytes = bytes;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var create = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var memoryInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (hostVisible) {
                memoryInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST).flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT)
                        .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)
                        .preferredFlags(VK_MEMORY_PROPERTY_HOST_CACHED_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            }
            var handle = stack.mallocLong(1);
            var memory = stack.mallocPointer(1);
            check(vmaCreateBuffer(work.allocator(), create, memoryInfo, handle, memory, null), "allocate compute buffer");
            buffer = handle.get(0);
            allocation = memory.get(0);
            if (hostVisible) {
                check(vmaMapMemory(work.allocator(), allocation, memory), "map compute buffer");
                mapped = MemoryUtil.memByteBuffer(memory.get(0), bytes);
            }
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    public long buffer() { return buffer; }
    public int bytes() { return bytes; }

    /** Copies small parameter blocks into the command buffer, avoiding writes to in-flight memory. */
    public void update(VkCommandBuffer commands, ByteBuffer source) {
        if (source.remaining() > bytes || source.remaining() > 65536 || (source.remaining() & 3) != 0) {
            throw new IllegalArgumentException("Inline Vulkan buffer updates must fit and be at most 64 KiB, aligned to 4 bytes");
        }
        VulkanSync.barrier(commands);
        vkCmdUpdateBuffer(commands, buffer, 0, source);
        VulkanSync.barrier(commands);
    }

    /** One-time upload, before submitting any GPU reads of this buffer. */
    public void upload(ByteBuffer source) {
        if (mapped == null || source.remaining() != bytes) throw new IllegalArgumentException("Invalid compute upload");
        mapped.clear();
        MemoryUtil.memCopy(MemoryUtil.memAddress(source), MemoryUtil.memAddress(mapped), bytes);
        vmaFlushAllocation(work.allocator(), allocation, 0, bytes);
    }

    /** One-time upload from the CPU-generated noise volumes. */
    public void upload(byte[] source) {
        if (mapped == null || source.length != bytes) throw new IllegalArgumentException("Invalid compute upload");
        mapped.clear().put(source).clear();
        vmaFlushAllocation(work.allocator(), allocation, 0, bytes);
    }

    /** Call only after the copy's fence completes; handles non-coherent host memory as well. */
    public ByteBuffer read() {
        if (mapped == null) throw new IllegalStateException("Compute buffer is not mapped");
        vmaInvalidateAllocation(work.allocator(), allocation, 0, bytes);
        return mapped.clear();
    }

    @Override
    public void destroy() {
        if (mapped != null) vmaUnmapMemory(work.allocator(), allocation);
        if (buffer != 0) vmaDestroyBuffer(work.allocator(), buffer, allocation);
        mapped = null;
        buffer = allocation = 0;
    }
}
