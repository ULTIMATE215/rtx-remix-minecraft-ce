/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.clouds;

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

/** Volumetric-cloud compute assets; ownership extends through their final encoder submission. */
final class CloudSkyResources implements Destroyable {
    private final VulkanWork work;
    private final VulkanImage[] outputs = new VulkanImage[4];
    private final VulkanImage[] noiseImages = new VulkanImage[3];
    final VulkanBuffer[] readbacks = new VulkanBuffer[CloudSkyCompositor.READBACK_RING_SIZE];
    private VulkanComputeProgram program;
    private VulkanBuffer parameters;
    private VulkanBuffer occupancyUpload;
    private VulkanImage occupancy;
    private boolean noiseReady;

    CloudSkyResources(VulkanWork work, String shader, int width, int height) {
        this.work = work;
        try {
            outputs[0] = output(width, height, VK_FORMAT_R16G16B16A16_SFLOAT);
            outputs[1] = output(width, height + 1, VK_FORMAT_R16G16B16A16_SFLOAT);
            outputs[2] = output((width + 7) / 8, (height / 2 + 7) / 8, VK_FORMAT_R32G32B32A32_SFLOAT);
            outputs[3] = output(width, height, VK_FORMAT_R32_SFLOAT);
            occupancy = new VulkanImage(work, VolumetricClouds.OCCUPANCY_SIZE, VolumetricClouds.OCCUPANCY_SIZE, 1,
                    VK_FORMAT_R8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            occupancyUpload = new VulkanBuffer(work, VolumetricClouds.OCCUPANCY_SIZE * VolumetricClouds.OCCUPANCY_SIZE,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            parameters = new VulkanBuffer(work, 192, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            for (int i = 0; i < readbacks.length; i++) {
                readbacks[i] = new VulkanBuffer(work, width * (height + 1) * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            }
            program = new VulkanComputeProgram(work.device(), "cloud_sky.comp", shader,
                    new int[] {0, 1, 2, 3, 6, 7, 8, 9, 10, 11, 12}, new int[] {
                        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER});
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    private VulkanImage output(int width, int height, int format) {
        return new VulkanImage(work, width, height, 1, format, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
    }

    void prepare(VkCommandBuffer commands, CloudNoise noise, VulkanImage atmosphere, VulkanImage transmittance, ByteBuffer values) {
        for (VulkanImage image : outputs) image.initialize(commands);
        occupancy.initialize(commands);
        if (!noiseReady) {
            noiseImages[0] = noise(commands, CloudNoise.BASE_SIZE, CloudNoise.BASE_SIZE, CloudNoise.BASE_SIZE, noise.base);
            noiseImages[1] = noise(commands, CloudNoise.DETAIL_SIZE, CloudNoise.DETAIL_SIZE, CloudNoise.DETAIL_SIZE, noise.detail);
            noiseImages[2] = noise(commands, CloudNoise.WEATHER_SIZE, CloudNoise.WEATHER_SIZE, 1, noise.weather);
            noiseReady = true;
        }
        parameters.update(commands, values);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(11, stack);
            for (int i = 0; i < outputs.length; i++) VulkanComputeProgram.image(writes.get(i), i, outputs[i], false, stack);
            VulkanComputeProgram.image(writes.get(4), 6, occupancy, true, stack);
            VulkanComputeProgram.uniform(writes.get(5), 7, parameters, stack);
            VulkanComputeProgram.image(writes.get(6), 8, atmosphere, true, stack);
            VulkanComputeProgram.image(writes.get(7), 9, transmittance, true, stack);
            for (int i = 0; i < noiseImages.length; i++) VulkanComputeProgram.image(writes.get(8 + i), 10 + i, noiseImages[i], true, stack);
            program.bind(commands, writes);
        }
    }

    private VulkanImage noise(VkCommandBuffer commands, int width, int height, int depth, byte[] pixels) {
        VulkanImage image = new VulkanImage(work, width, height, depth, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT);
        try {
            VulkanBuffer upload = new VulkanBuffer(work, pixels.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
            try {
                upload.upload(pixels);
                image.upload(commands, upload);
            } finally {
                work.retire(upload);
            }
            return image;
        } catch (RuntimeException | Error failure) {
            work.retire(image);
            throw failure;
        }
    }

    void uploadOccupancy(VkCommandBuffer commands, ByteBuffer pixels) {
        occupancyUpload.update(commands, pixels);
        occupancy.upload(commands, occupancyUpload);
    }

    void dispatch(VkCommandBuffer commands, int pass, int groupsX, int groupsY) {
        program.dispatch(commands, pass, groupsX, groupsY);
    }

    VulkanImage finalSky() { return outputs[1]; }

    @Override
    public void destroy() {
        if (program != null) program.destroy();
        if (parameters != null) parameters.destroy();
        if (occupancyUpload != null) occupancyUpload.destroy();
        if (occupancy != null) occupancy.destroy();
        for (VulkanImage image : outputs) if (image != null) image.destroy();
        for (VulkanImage image : noiseImages) if (image != null) image.destroy();
        for (VulkanBuffer buffer : readbacks) if (buffer != null) buffer.destroy();
    }
}
