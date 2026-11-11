/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.lwjgl.vulkan.VK10.*;

/** Reads actual transfer results, including sRGB encoding, channel order and both vertical flips. */
@EnabledIfSystemProperty(named = "rtxremix.test.cloudGpu", matches = "true")
class VulkanFrameTransferGpuTest {
    @ParameterizedTest
    @ValueSource(ints = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB})
    void incomingPixelsAndCompositedHudKeepTheirEncoding(int format) {
        try (HeadlessVulkan work = new HeadlessVulkan(); MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage shared = image(work, format);
            VulkanImage snapshot = image(work, VK_FORMAT_R8G8B8A8_UNORM);
            VulkanBuffer upload = buffer(work, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            VulkanBuffer readback = buffer(work, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            VulkanFrameTransfer transfer = new VulkanFrameTransfer(work, 2, 2, format);
            work.retire(transfer);
            byte[] pixels = {16, 31, 63, -1, 45, 89, 127, -1,
                    (byte) 192, 64, 32, -1, 15, (byte) 220, 90, -1};
            upload.upload(channelOrder(pixels, format));
            var commands = work.begin();
            shared.upload(commands, upload);
            snapshot.initialize(commands);
            VulkanSync.barrier(commands);
            transfer.fromShared(commands, shared.image(), snapshot.image(), 0, stack);
            snapshot.copyTo(commands, readback);
            work.execute(commands);
            work.submit();
            byte[] actual = new byte[16];
            readback.read().get(actual);
            assertArrayEquals(flipRows(pixels), actual, "Minecraft must receive display-encoded RGBA pixels");

            // A HUD pixel is already in Minecraft's UNORM encoding. An sRGB
            // blit on the way back would incorrectly gamma-encode it again.
            byte[] composited = actual.clone();
            composited[0] = (byte) 128;
            composited[1] = 64;
            composited[2] = (byte) 192;
            composited[3] = (byte) 200;
            upload.upload(composited);
            commands = work.begin();
            snapshot.upload(commands, upload);
            transfer.toShared(commands, snapshot.image(), 0, 2, 2, shared.image(), stack);
            shared.copyTo(commands, readback);
            work.execute(commands);
            work.submit();
            readback.read().get(actual);
            assertArrayEquals(channelOrder(flipRows(composited), format), actual,
                    "Remix must receive the composed pixels in its original row/channel order and encoding");
        }
    }

    private static VulkanImage image(HeadlessVulkan work, int format) {
        VulkanImage image = new VulkanImage(work, 2, 2, 1, format,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        work.retire(image);
        return image;
    }

    private static VulkanBuffer buffer(HeadlessVulkan work, int usage) {
        VulkanBuffer buffer = new VulkanBuffer(work, 16, usage, true);
        work.retire(buffer);
        return buffer;
    }

    private static byte[] flipRows(byte[] source) {
        byte[] result = new byte[16];
        System.arraycopy(source, 8, result, 0, 8);
        System.arraycopy(source, 0, result, 8, 8);
        return result;
    }

    private static byte[] channelOrder(byte[] rgba, int format) {
        byte[] result = rgba.clone();
        if (format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB) {
            for (int pixel = 0; pixel < 4; pixel++) {
                result[pixel * 4] = rgba[pixel * 4 + 2];
                result[pixel * 4 + 2] = rgba[pixel * 4];
            }
        }
        return result;
    }
}
