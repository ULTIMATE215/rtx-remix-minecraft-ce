/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTValidationFeatures.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;
import static com.nvidia.rtxremix.minecraft.vulkan.VulkanSync.check;

/** Runs the production compute commands on a real Vulkan queue, including software Vulkan. */
public final class HeadlessVulkan implements VulkanWork, AutoCloseable {
    private VkInstance instance;
    private VkDevice device;
    private VkQueue queue;
    private long allocator, commandPool, messenger;
    private VkDebugUtilsMessengerCallbackEXT debug;
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<Destroyable> retired = new ArrayList<>();
    private final List<Long> fences = new ArrayList<>();

    public HeadlessVulkan() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean validation = hasValidationLayer(stack);
            var application = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("Remix Vulkan compute tests")).apiVersion(VK_API_VERSION_1_2);
            var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application);
            if (validation) {
                create.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")))
                        .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME),
                                stack.UTF8(VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME)))
                        .pNext(VkValidationFeaturesEXT.calloc(stack).sType$Default().pEnabledValidationFeatures(
                                stack.ints(VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT)));
            }
            var pointer = stack.mallocPointer(1);
            check(vkCreateInstance(create, null, pointer), "create test Vulkan instance");
            instance = new VkInstance(pointer.get(0), create);
            if (validation) {
                debug = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
                    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                        errors.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                    }
                    return VK_FALSE;
                });
                var callback = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT).pfnUserCallback(debug);
                var handle = stack.mallocLong(1);
                check(vkCreateDebugUtilsMessengerEXT(instance, callback, null, handle), "create validation messenger");
                messenger = handle.get(0);
            }
            var count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate Vulkan test devices");
            var physicals = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, physicals), "read Vulkan test devices");
            if (!physicals.hasRemaining()) throw new IllegalStateException("No Vulkan test device");
            VkPhysicalDevice physical = new VkPhysicalDevice(physicals.get(0), instance);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            int family = -1;
            for (int i = 0; i < families.capacity(); i++) {
                int required = VK_QUEUE_COMPUTE_BIT | VK_QUEUE_GRAPHICS_BIT;
                if ((families.get(i).queueFlags() & required) == required) { family = i; break; }
            }
            if (family < 0) throw new IllegalStateException("No graphics/compute queue on test device");
            var queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default().queueFamilyIndex(family)
                    .pQueuePriorities(stack.floats(1));
            var features = VkPhysicalDeviceFeatures.calloc(stack).shaderStorageImageExtendedFormats(true);
            var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo)
                    .pEnabledFeatures(features).ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)));
            check(vkCreateDevice(physical, deviceInfo, null, pointer), "create compute test device");
            device = new VkDevice(pointer.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT), null, handle), "create test command pool");
            commandPool = handle.get(0);
            var allocatorInfo = VmaAllocatorCreateInfo.calloc(stack).physicalDevice(physical).device(device)
                    .instance(instance).vulkanApiVersion(VK_API_VERSION_1_2)
                    .pVulkanFunctions(VmaVulkanFunctions.calloc(stack).set(instance, device));
            check(vmaCreateAllocator(allocatorInfo, pointer), "create test allocator");
            allocator = pointer.get(0);
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            System.out.println("Vulkan compute tests: " + properties.deviceNameString() + ", synchronization validation=" + validation);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static boolean hasValidationLayer(MemoryStack stack) {
        var count = stack.mallocInt(1);
        check(vkEnumerateInstanceLayerProperties(count, null), "enumerate Vulkan layers");
        var layers = VkLayerProperties.calloc(count.get(0), stack);
        check(vkEnumerateInstanceLayerProperties(count, layers), "read Vulkan layers");
        for (int i = 0; i < layers.capacity(); i++) {
            if (layers.get(i).layerNameString().equals("VK_LAYER_KHRONOS_validation")) return true;
        }
        return false;
    }

    @Override public VkDevice device() { return device; }
    @Override public long allocator() { return allocator; }

    @Override
    public VkCommandBuffer begin() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).commandBufferCount(1).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY), pointer),
                    "allocate test command buffer");
            VkCommandBuffer commands = new VkCommandBuffer(pointer.get(0), device);
            check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin test commands");
            return commands;
        }
    }

    @Override
    public void execute(VkCommandBuffer commands) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkEndCommandBuffer(commands), "end test commands");
            var handle = stack.mallocLong(1);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "create test fence");
            long fence = handle.get(0);
            fences.add(fence);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(commands)), fence), "submit compute commands");
        }
    }

    @Override
    public GpuFence fence() {
        long fence = fences.getLast();
        return new GpuFence() {
            // The harness owns the native fence until close, including when a readback is discarded in flight.
            @Override public void close() {}
            @Override public boolean awaitCompletion(long timeout) {
                int result = vkWaitForFences(device, fence, true, timeout);
                if (result == VK_TIMEOUT) return false;
                check(result, "wait for compute test fence");
                return true;
            }
        };
    }

    @Override public void retire(Destroyable resource) { retired.add(resource); }
    @Override public void submit() { check(vkQueueWaitIdle(queue), "complete test queue"); }

    @Override
    public void close() {
        if (device != null) {
            check(vkDeviceWaitIdle(device), "finish test device");
            for (Destroyable resource : retired) resource.destroy();
            retired.clear();
            for (long fence : fences) vkDestroyFence(device, fence, null);
            fences.clear();
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            if (allocator != 0) vmaDestroyAllocator(allocator);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (messenger != 0) vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        if (instance != null) vkDestroyInstance(instance, null);
        if (debug != null) debug.free();
        instance = null;
        messenger = 0;
        debug = null;
        if (!errors.isEmpty()) throw new AssertionError("Vulkan validation errors:\n" + String.join("\n", errors));
    }
}
