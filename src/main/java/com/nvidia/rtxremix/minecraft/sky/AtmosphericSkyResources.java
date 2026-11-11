/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanBuffer;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanComputeProgram;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanImage;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanWork;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** Five images and four compute passes, shared by the physical and legacy atmosphere models. */
final class AtmosphericSkyResources implements Destroyable {
    private final VulkanWork work;
    private final int width, height;
    private final VulkanImage[] images = new VulkanImage[5];
    final VulkanBuffer[] readbacks = new VulkanBuffer[3];
    private VulkanBuffer parameters;
    private VulkanComputeProgram program;

    AtmosphericSkyResources(VulkanWork work, String shader, int width, int height) {
        this.work = work;
        this.width = width;
        this.height = height;
        try {
            images[0] = image(AtmosphericSkyGenerator.LUT_WIDTH, AtmosphericSkyGenerator.LUT_HEIGHT, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FILTER_LINEAR);
            images[1] = image(width, height / 2, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FILTER_NEAREST);
            images[2] = image(width, height / 2, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FILTER_NEAREST);
            images[3] = image(width, height + 1, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FILTER_NEAREST);
            images[4] = image(AtmosphericSkyGenerator.MULTISCATTERING_LUT_SIZE, AtmosphericSkyGenerator.MULTISCATTERING_LUT_SIZE,
                    VK_FORMAT_R32G32B32A32_SFLOAT, VK_FILTER_LINEAR);
            parameters = new VulkanBuffer(work, 80, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            for (int i = 0; i < readbacks.length; i++) {
                readbacks[i] = new VulkanBuffer(work, width * (height + 1) * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            }
            program = new VulkanComputeProgram(work.device(), "sky_atmosphere.comp", shader,
                    new int[] {0, 1, 2, 3, 4, 5, 6, 7}, new int[] {
                        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER});
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    private VulkanImage image(int width, int height, int format, int filter) {
        return new VulkanImage(work, width, height, 1, format,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                filter, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
    }

    void record(VkCommandBuffer commands, ByteBuffer values, int model, boolean buildLuts) {
        for (VulkanImage image : images) image.initialize(commands);
        parameters.update(commands, values);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(8, stack);
            for (int i = 0; i < 5; i++) VulkanComputeProgram.image(writes.get(i), i, images[i], false, stack);
            VulkanComputeProgram.image(writes.get(5), 5, images[0], true, stack);
            VulkanComputeProgram.image(writes.get(6), 6, images[4], true, stack);
            VulkanComputeProgram.uniform(writes.get(7), 7, parameters, stack);
            program.bind(commands, writes);
        }
        if (buildLuts) {
            program.dispatch(commands, 0, AtmosphericSkyGenerator.LUT_WIDTH / 8, AtmosphericSkyGenerator.LUT_HEIGHT / 8);
            if (model == 1) {
                int groups = AtmosphericSkyGenerator.MULTISCATTERING_LUT_SIZE / 8;
                program.dispatch(commands, 3, groups, groups);
            }
        }
        program.dispatch(commands, 1, (width + 7) / 8, (height / 2 + 7) / 8);
        program.dispatch(commands, 2, 1, 1);
    }

    VulkanImage transmittance() { return images[0]; }
    VulkanImage finalSky() { return images[3]; }

    @Override
    public void destroy() {
        if (program != null) program.destroy();
        if (parameters != null) parameters.destroy();
        for (VulkanImage image : images) if (image != null) image.destroy();
        for (VulkanBuffer buffer : readbacks) if (buffer != null) buffer.destroy();
    }
}
