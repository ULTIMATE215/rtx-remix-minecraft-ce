/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK10.*;
import static com.nvidia.rtxremix.minecraft.vulkan.VulkanSync.check;

/** Compute pipeline with command-local descriptors and a four-byte pass selector. */
public final class VulkanComputeProgram implements Destroyable {
    private final VkDevice device;
    private long descriptorLayout, pipelineLayout, pipeline;

    public VulkanComputeProgram(VkDevice device, String name, String source, int[] bindings, int[] types) {
        this.device = device;
        if (bindings.length != types.length) throw new IllegalArgumentException("Mismatched compute bindings");
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        long result = 0, module = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (compiler == 0 || options == 0) throw new IllegalStateException("Cannot initialize Vulkan shader compiler");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, name, "main", options);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException("Cannot compile " + name + ": "
                        + (result == 0 ? "no compiler result" : shaderc_result_get_error_message(result)));
            }
            ByteBuffer binary = shaderc_result_get_bytes(result);
            if (binary == null) throw new IllegalStateException("Empty compute shader: " + name);
            var handle = stack.mallocLong(1);
            var layout = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
            for (int i = 0; i < bindings.length; i++) {
                layout.get(i).binding(bindings[i]).descriptorCount(1).descriptorType(types[i])
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(layout), null, handle),
                    "create compute descriptor layout");
            descriptorLayout = handle.get(0);
            var constants = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(constants), null, handle),
                    "create compute pipeline layout");
            pipelineLayout = handle.get(0);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(binary),
                    null, handle), "create compute shader module");
            module = handle.get(0);
            var create = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
            create.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, 0, create, null, handle), "create compute pipeline");
            pipeline = handle.get(0);
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        } finally {
            if (module != 0) vkDestroyShaderModule(device, module, null);
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            if (compiler != 0) shaderc_compiler_release(compiler);
        }
    }

    public void bind(VkCommandBuffer commands, VkWriteDescriptorSet.Buffer descriptors) {
        vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdPushDescriptorSetKHR(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, descriptors);
    }

    public void dispatch(VkCommandBuffer commands, int pass, int groupsX, int groupsY) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(pass));
            vkCmdDispatch(commands, groupsX, groupsY, 1);
        }
        VulkanSync.barrier(commands);
    }

    public static void image(VkWriteDescriptorSet write, int binding, VulkanImage image, boolean sampled, MemoryStack stack) {
        var info = VkDescriptorImageInfo.calloc(1, stack).imageView(image.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                .sampler(sampled ? image.sampler() : 0);
        write.sType$Default().dstBinding(binding).descriptorCount(1)
                .descriptorType(sampled ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(info);
    }

    public static void uniform(VkWriteDescriptorSet write, int binding, VulkanBuffer buffer, MemoryStack stack) {
        var info = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.buffer()).range(buffer.bytes());
        write.sType$Default().dstBinding(binding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .pBufferInfo(info);
    }

    @Override
    public void destroy() {
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
        pipeline = pipelineLayout = descriptorLayout = 0;
    }
}
