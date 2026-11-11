/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */

// A process-local Vulkan layer supplies the allocation description required to
// import the runtime's opaque Win32 handles. It observes public Vulkan calls;
// it never changes a create info, command, submission, or runtime C API layout.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_WIN32_KHR
#include <windows.h>
#include <jni.h>
#include "include/vulkan/vk_layer.h"
#include "include/vulkan/vulkan_win32.h"
#include <array>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

template<class T> void* dispatchKey(T handle) {
    return *reinterpret_cast<void**>(handle);
}

struct ImageDescription {
    uint64_t generation = 0;
    VkImageCreateInfo create {};
    VkExternalMemoryHandleTypeFlags externalTypes = 0;
    std::vector<uint32_t> families;
    std::vector<VkFormat> formats;
    bool hasFormatList = false;
    VkImageUsageFlags stencilUsage = 0;
    bool hasStencilUsage = false;
    VkStructureType unsupported = VK_STRUCTURE_TYPE_MAX_ENUM;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize offset = 0;
    uint32_t externalFamily = VK_QUEUE_FAMILY_IGNORED;
    VkImageLayout externalLayout = VK_IMAGE_LAYOUT_UNDEFINED;
};

struct AllocationDescription {
    VkDeviceSize size = 0;
    uint32_t type = 0;
    VkMemoryAllocateFlags flags = 0;
    uint32_t deviceMask = 0;
    VkImage dedicatedImage = VK_NULL_HANDLE;
    VkStructureType unsupported = VK_STRUCTURE_TYPE_MAX_ENUM;
};

struct InstanceDispatch {
    VkInstance instance;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_GetPhysicalDeviceProcAddr gpdpa;
};

struct DeviceDispatch {
    std::atomic<bool> alive { true };
    VkDevice device = VK_NULL_HANDLE;
    PFN_vkGetDeviceProcAddr gdpa = nullptr;
    std::array<uint8_t, VK_UUID_SIZE> deviceUuid {};
    std::array<uint8_t, VK_UUID_SIZE> driverUuid {};
    bool hasIdentity = false;
    std::unordered_map<VkImage, ImageDescription> images;
    std::unordered_map<VkDeviceMemory, AllocationDescription> allocations;
    std::unordered_map<VkSemaphore, VkSemaphoreType> semaphores;
#define DEVICE_FUNCTION(name) PFN_##name name = nullptr;
    DEVICE_FUNCTION(vkDestroyDevice)
    DEVICE_FUNCTION(vkCreateImage)
    DEVICE_FUNCTION(vkDestroyImage)
    DEVICE_FUNCTION(vkAllocateMemory)
    DEVICE_FUNCTION(vkFreeMemory)
    DEVICE_FUNCTION(vkBindImageMemory)
    DEVICE_FUNCTION(vkBindImageMemory2)
    DEVICE_FUNCTION(vkGetMemoryWin32HandleKHR)
    DEVICE_FUNCTION(vkCreateSemaphore)
    DEVICE_FUNCTION(vkDestroySemaphore)
    DEVICE_FUNCTION(vkGetSemaphoreWin32HandleKHR)
    DEVICE_FUNCTION(vkCmdPipelineBarrier)
    DEVICE_FUNCTION(vkCmdPipelineBarrier2)
#undef DEVICE_FUNCTION
};

struct MemoryExport {
    std::shared_ptr<DeviceDispatch> device;
    VkDeviceMemory memory;
};

struct SemaphoreExport {
    std::shared_ptr<DeviceDispatch> device;
    VkSemaphore semaphore;
};

std::mutex registryMutex;
std::unordered_map<void*, InstanceDispatch> instances;
std::unordered_map<void*, std::shared_ptr<DeviceDispatch>> devices;
std::unordered_map<HANDLE, MemoryExport> memoryExports;
std::unordered_map<HANDLE, SemaphoreExport> semaphoreExports;
std::atomic<bool> trackingFailed { false };
uint64_t nextImageGeneration = 1;

// Command recording hits the same device repeatedly on each render/worker
// thread. Only device changes need a registry lookup or shared_ptr update.
template<class T> std::shared_ptr<DeviceDispatch> deviceFor(T handle) {
    thread_local void* cachedKey = nullptr;
    thread_local std::shared_ptr<DeviceDispatch> cached;
    void* key = dispatchKey(handle);
    if (cachedKey != key || !cached || !cached->alive.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lock(registryMutex);
        cached = devices.at(key);
        cachedKey = key;
    }
    return cached;
}

template<class F> void observe(F&& operation) noexcept {
    try {
        std::lock_guard<std::mutex> lock(registryMutex);
        operation();
    } catch (...) {
        // Do not unwind through a driver's Vulkan entry point. A later import
        // reports the lost description instead of using incomplete metadata.
        trackingFailed.store(true, std::memory_order_release);
    }
}

template<class T> T* chainInfo(const void* next, VkStructureType type, VkLayerFunction function) {
    for (auto info = reinterpret_cast<const VkBaseInStructure*>(next); info; info = info->pNext) {
        if (info->sType == type) {
            auto layer = reinterpret_cast<const T*>(info);
            if (layer->function == function) return const_cast<T*>(layer);
        }
    }
    return nullptr;
}

void recordImage(DeviceDispatch& device, VkImage image, const VkImageCreateInfo& info) {
    ImageDescription description;
    description.generation = nextImageGeneration++;
    description.create = info;
    description.create.pNext = nullptr;
    description.create.pQueueFamilyIndices = nullptr;
    if (info.sharingMode == VK_SHARING_MODE_CONCURRENT) {
        description.families.assign(info.pQueueFamilyIndices, info.pQueueFamilyIndices + info.queueFamilyIndexCount);
    }
    for (auto base = reinterpret_cast<const VkBaseInStructure*>(info.pNext); base; base = base->pNext) {
        switch (base->sType) {
        case VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO:
            description.externalTypes = reinterpret_cast<const VkExternalMemoryImageCreateInfo*>(base)->handleTypes;
            break;
        case VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO: {
            auto formats = reinterpret_cast<const VkImageFormatListCreateInfo*>(base);
            description.hasFormatList = true;
            if (formats->viewFormatCount) {
                description.formats.assign(formats->pViewFormats, formats->pViewFormats + formats->viewFormatCount);
            }
            break;
        }
        case VK_STRUCTURE_TYPE_IMAGE_STENCIL_USAGE_CREATE_INFO:
            description.hasStencilUsage = true;
            description.stencilUsage = reinterpret_cast<const VkImageStencilUsageCreateInfo*>(base)->stencilUsage;
            break;
        default:
            description.unsupported = base->sType;
            break;
        }
    }
    if (description.externalTypes & VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT) {
        device.images.emplace(image, std::move(description));
    }
}

void recordAllocation(DeviceDispatch& device, VkDeviceMemory memory, const VkMemoryAllocateInfo& info) {
    bool exported = false;
    AllocationDescription description;
    description.size = info.allocationSize;
    description.type = info.memoryTypeIndex;
    for (auto base = reinterpret_cast<const VkBaseInStructure*>(info.pNext); base; base = base->pNext) {
        switch (base->sType) {
        case VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO:
            exported = (reinterpret_cast<const VkExportMemoryAllocateInfo*>(base)->handleTypes
                    & VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT) != 0;
            break;
        case VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO:
            description.dedicatedImage = reinterpret_cast<const VkMemoryDedicatedAllocateInfo*>(base)->image;
            break;
        case VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO: {
            auto flags = reinterpret_cast<const VkMemoryAllocateFlagsInfo*>(base);
            description.flags = flags->flags;
            description.deviceMask = flags->deviceMask;
            break;
        }
        // Export access rights and allocation priority do not change the
        // allocation/image compatibility requirements of an import.
        case VK_STRUCTURE_TYPE_EXPORT_MEMORY_WIN32_HANDLE_INFO_KHR:
        case VK_STRUCTURE_TYPE_MEMORY_PRIORITY_ALLOCATE_INFO_EXT:
            break;
        default:
            description.unsupported = base->sType;
            break;
        }
    }
    if (exported) device.allocations.emplace(memory, description);
}

void bindImage(DeviceDispatch& device, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
    auto found = device.images.find(image);
    if (found != device.images.end()) {
        found->second.memory = memory;
        found->second.offset = offset;
    }
}

bool externalFamily(uint32_t family) {
    // Both logical devices use the same physical device and driver UUID.
    // FOREIGN has a different ownership contract and requires another extension.
    return family == VK_QUEUE_FAMILY_EXTERNAL;
}

template<class Barrier> void observeRelease(DeviceDispatch& device, const Barrier& barrier) {
    if (!externalFamily(barrier.dstQueueFamilyIndex)) return;
    observe([&] {
        auto image = device.images.find(barrier.image);
        if (image != device.images.end()) {
            auto& description = image->second;
            const auto& range = barrier.subresourceRange;
            bool wholeImage = range.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT
                    && range.baseMipLevel == 0 && range.baseArrayLayer == 0
                    && (range.levelCount == description.create.mipLevels || range.levelCount == VK_REMAINING_MIP_LEVELS)
                    && (range.layerCount == description.create.arrayLayers || range.layerCount == VK_REMAINING_ARRAY_LAYERS)
                    && barrier.srcQueueFamilyIndex != VK_QUEUE_FAMILY_IGNORED;
            description.externalFamily = wholeImage ? barrier.dstQueueFamilyIndex : VK_QUEUE_FAMILY_IGNORED;
            description.externalLayout = barrier.newLayout;
        }
    });
}

MemoryExport& findMemory(HANDLE handle) {
    if (trackingFailed.load(std::memory_order_acquire)) {
        throw std::runtime_error("The Vulkan export layer lost allocation metadata");
    }
    auto found = memoryExports.find(handle);
    if (found == memoryExports.end() || !found->second.device->alive.load()) {
        throw std::runtime_error("The Vulkan export layer did not observe this Remix memory handle; "
                "VK_LAYER_RTXREMIX_export_info must be enabled before creating the runtime device");
    }
    return found->second;
}

ImageDescription& findImage(MemoryExport& exported, uint32_t width, uint32_t height, VkFormat format) {
    auto allocation = exported.device->allocations.find(exported.memory);
    if (allocation == exported.device->allocations.end()) {
        throw std::runtime_error("The Remix image allocation is no longer live");
    }
    ImageDescription* result = nullptr;
    for (auto& entry : exported.device->images) {
        auto& image = entry.second;
        if (image.memory != exported.memory || image.offset != 0
                || image.create.extent.width != width || image.create.extent.height != height
                || image.create.format != format) continue;
        if (allocation->second.dedicatedImage != VK_NULL_HANDLE
                && allocation->second.dedicatedImage != entry.first) continue;
        if (result) throw std::runtime_error("The Remix export has ambiguous image bindings");
        result = &image;
    }
    if (!result) throw std::runtime_error("No live Vulkan image matches the Remix export description");
    if (!exported.device->hasIdentity) throw std::runtime_error("The exporting Vulkan device has no UUID");
    if (allocation->second.unsupported != VK_STRUCTURE_TYPE_MAX_ENUM) {
        throw std::runtime_error("Unsupported Vulkan export allocation pNext type "
                + std::to_string(allocation->second.unsupported));
    }
    if (result->unsupported != VK_STRUCTURE_TYPE_MAX_ENUM) {
        throw std::runtime_error("Unsupported Vulkan export image pNext type " + std::to_string(result->unsupported));
    }
    if (!externalFamily(result->externalFamily) || result->externalLayout != VK_IMAGE_LAYOUT_GENERAL) {
        throw std::runtime_error("Remix did not release the shared Vulkan image in GENERAL layout to an external queue");
    }
    return *result;
}

void throwJava(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

jlong uuidWord(const uint8_t* bytes) {
    jlong result;
    std::memcpy(&result, bytes, sizeof(result));
    return result;
}

} // namespace

extern "C" {

__declspec(dllexport) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance, const char*);
__declspec(dllexport) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice, const char*);

VKAPI_ATTR VkResult VKAPI_CALL remixCreateInstance(const VkInstanceCreateInfo* info,
        const VkAllocationCallbacks* allocator, VkInstance* instance) {
    auto layer = chainInfo<VkLayerInstanceCreateInfo>(info->pNext,
            VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO, VK_LAYER_LINK_INFO);
    if (!layer) return VK_ERROR_INITIALIZATION_FAILED;
    auto link = layer->u.pLayerInfo;
    auto nextGipa = link->pfnNextGetInstanceProcAddr;
    auto nextGpdpa = link->pfnNextGetPhysicalDeviceProcAddr;
    auto create = reinterpret_cast<PFN_vkCreateInstance>(nextGipa(VK_NULL_HANDLE, "vkCreateInstance"));
    layer->u.pLayerInfo = link->pNext;
    VkResult result = create(info, allocator, instance);
    if (result == VK_SUCCESS) {
        try {
            std::lock_guard<std::mutex> lock(registryMutex);
            instances.emplace(dispatchKey(*instance), InstanceDispatch { *instance, nextGipa, nextGpdpa });
        } catch (...) {
            reinterpret_cast<PFN_vkDestroyInstance>(nextGipa(*instance, "vkDestroyInstance"))(*instance, allocator);
            *instance = VK_NULL_HANDLE;
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }
    }
    return result;
}

VKAPI_ATTR void VKAPI_CALL remixDestroyInstance(VkInstance instance, const VkAllocationCallbacks* allocator) {
    if (instance == VK_NULL_HANDLE) return;
    InstanceDispatch dispatch;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        dispatch = instances.at(dispatchKey(instance));
        instances.erase(dispatchKey(instance));
    }
    reinterpret_cast<PFN_vkDestroyInstance>(dispatch.gipa(instance, "vkDestroyInstance"))(instance, allocator);
}

VKAPI_ATTR VkResult VKAPI_CALL remixCreateDevice(VkPhysicalDevice physical, const VkDeviceCreateInfo* info,
        const VkAllocationCallbacks* allocator, VkDevice* device) {
    auto layer = chainInfo<VkLayerDeviceCreateInfo>(info->pNext,
            VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO, VK_LAYER_LINK_INFO);
    if (!layer) return VK_ERROR_INITIALIZATION_FAILED;
    auto link = layer->u.pLayerInfo;
    auto nextGipa = link->pfnNextGetInstanceProcAddr;
    auto nextGdpa = link->pfnNextGetDeviceProcAddr;
    InstanceDispatch instance;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        instance = instances.at(dispatchKey(physical));
    }
    auto create = reinterpret_cast<PFN_vkCreateDevice>(nextGipa(instance.instance, "vkCreateDevice"));
    layer->u.pLayerInfo = link->pNext;
    VkResult result = create(physical, info, allocator, device);
    if (result != VK_SUCCESS) return result;
    try {
        auto dispatch = std::make_shared<DeviceDispatch>();
        dispatch->device = *device;
        dispatch->gdpa = nextGdpa;
#define LOAD(name) dispatch->name = reinterpret_cast<PFN_##name>(nextGdpa(*device, #name));
        LOAD(vkDestroyDevice)
        LOAD(vkCreateImage)
        LOAD(vkDestroyImage)
        LOAD(vkAllocateMemory)
        LOAD(vkFreeMemory)
        LOAD(vkBindImageMemory)
        LOAD(vkBindImageMemory2)
        LOAD(vkGetMemoryWin32HandleKHR)
        LOAD(vkCreateSemaphore)
        LOAD(vkDestroySemaphore)
        LOAD(vkGetSemaphoreWin32HandleKHR)
        LOAD(vkCmdPipelineBarrier)
        LOAD(vkCmdPipelineBarrier2)
#undef LOAD
        if (!dispatch->vkBindImageMemory2) dispatch->vkBindImageMemory2 =
                reinterpret_cast<PFN_vkBindImageMemory2>(nextGdpa(*device, "vkBindImageMemory2KHR"));
        if (!dispatch->vkCmdPipelineBarrier2) dispatch->vkCmdPipelineBarrier2 =
                reinterpret_cast<PFN_vkCmdPipelineBarrier2>(nextGdpa(*device, "vkCmdPipelineBarrier2KHR"));
        auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
                nextGipa(instance.instance, "vkGetPhysicalDeviceProperties2"));
        if (!properties) properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
                nextGipa(instance.instance, "vkGetPhysicalDeviceProperties2KHR"));
        if (properties) {
            VkPhysicalDeviceIDProperties ids { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES };
            VkPhysicalDeviceProperties2 values { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &ids };
            properties(physical, &values);
            std::memcpy(dispatch->deviceUuid.data(), ids.deviceUUID, VK_UUID_SIZE);
            std::memcpy(dispatch->driverUuid.data(), ids.driverUUID, VK_UUID_SIZE);
            dispatch->hasIdentity = true;
        }
        std::lock_guard<std::mutex> lock(registryMutex);
        devices.emplace(dispatchKey(*device), dispatch);
    } catch (...) {
        reinterpret_cast<PFN_vkDestroyDevice>(nextGdpa(*device, "vkDestroyDevice"))(*device, allocator);
        *device = VK_NULL_HANDLE;
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    return result;
}

VKAPI_ATTR void VKAPI_CALL remixDestroyDevice(VkDevice device, const VkAllocationCallbacks* allocator) {
    if (device == VK_NULL_HANDLE) return;
    auto dispatch = deviceFor(device);
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        dispatch->alive.store(false, std::memory_order_release);
        devices.erase(dispatchKey(device));
        for (auto it = memoryExports.begin(); it != memoryExports.end();) {
            if (it->second.device == dispatch) it = memoryExports.erase(it); else ++it;
        }
        for (auto it = semaphoreExports.begin(); it != semaphoreExports.end();) {
            if (it->second.device == dispatch) it = semaphoreExports.erase(it); else ++it;
        }
        dispatch->images.clear();
        dispatch->allocations.clear();
        dispatch->semaphores.clear();
    }
    dispatch->vkDestroyDevice(device, allocator);
}

VKAPI_ATTR VkResult VKAPI_CALL remixCreateImage(VkDevice device, const VkImageCreateInfo* info,
        const VkAllocationCallbacks* allocator, VkImage* image) {
    auto dispatch = deviceFor(device);
    VkResult result = dispatch->vkCreateImage(device, info, allocator, image);
    if (result == VK_SUCCESS) observe([&] { recordImage(*dispatch, *image, *info); });
    return result;
}

VKAPI_ATTR void VKAPI_CALL remixDestroyImage(VkDevice device, VkImage image, const VkAllocationCallbacks* allocator) {
    auto dispatch = deviceFor(device);
    observe([&] { dispatch->images.erase(image); });
    dispatch->vkDestroyImage(device, image, allocator);
}

VKAPI_ATTR VkResult VKAPI_CALL remixAllocateMemory(VkDevice device, const VkMemoryAllocateInfo* info,
        const VkAllocationCallbacks* allocator, VkDeviceMemory* memory) {
    auto dispatch = deviceFor(device);
    VkResult result = dispatch->vkAllocateMemory(device, info, allocator, memory);
    if (result == VK_SUCCESS) observe([&] { recordAllocation(*dispatch, *memory, *info); });
    return result;
}

VKAPI_ATTR void VKAPI_CALL remixFreeMemory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks* allocator) {
    auto dispatch = deviceFor(device);
    observe([&] {
        dispatch->allocations.erase(memory);
        for (auto it = memoryExports.begin(); it != memoryExports.end();) {
            if (it->second.device == dispatch && it->second.memory == memory) it = memoryExports.erase(it); else ++it;
        }
    });
    dispatch->vkFreeMemory(device, memory, allocator);
}

VKAPI_ATTR VkResult VKAPI_CALL remixBindImageMemory(VkDevice device, VkImage image,
        VkDeviceMemory memory, VkDeviceSize offset) {
    auto dispatch = deviceFor(device);
    VkResult result = dispatch->vkBindImageMemory(device, image, memory, offset);
    if (result == VK_SUCCESS) observe([&] { bindImage(*dispatch, image, memory, offset); });
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL remixBindImageMemory2(VkDevice device, uint32_t count, const VkBindImageMemoryInfo* bindings) {
    auto dispatch = deviceFor(device);
    VkResult result = dispatch->vkBindImageMemory2(device, count, bindings);
    if (result == VK_SUCCESS) observe([&] {
        for (uint32_t i = 0; i < count; ++i) bindImage(*dispatch, bindings[i].image, bindings[i].memory, bindings[i].memoryOffset);
    });
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL remixGetMemoryWin32Handle(VkDevice device,
        const VkMemoryGetWin32HandleInfoKHR* info, HANDLE* handle) {
    auto dispatch = deviceFor(device);
    VkResult result = dispatch->vkGetMemoryWin32HandleKHR(device, info, handle);
    if (result == VK_SUCCESS && info->handleType == VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT) {
        observe([&] { memoryExports.insert_or_assign(*handle, MemoryExport { dispatch, info->memory }); });
    }
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL remixCreateSemaphore(VkDevice device, const VkSemaphoreCreateInfo* info,
        const VkAllocationCallbacks* allocator, VkSemaphore* semaphore) {
    auto dispatch = deviceFor(device);
    VkResult result = dispatch->vkCreateSemaphore(device, info, allocator, semaphore);
    if (result == VK_SUCCESS) observe([&] {
        VkSemaphoreType type = VK_SEMAPHORE_TYPE_BINARY;
        for (auto base = reinterpret_cast<const VkBaseInStructure*>(info->pNext); base; base = base->pNext) {
            if (base->sType == VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO) {
                type = reinterpret_cast<const VkSemaphoreTypeCreateInfo*>(base)->semaphoreType;
            }
        }
        dispatch->semaphores.emplace(*semaphore, type);
    });
    return result;
}

VKAPI_ATTR void VKAPI_CALL remixDestroySemaphore(VkDevice device, VkSemaphore semaphore, const VkAllocationCallbacks* allocator) {
    auto dispatch = deviceFor(device);
    observe([&] {
        dispatch->semaphores.erase(semaphore);
        for (auto it = semaphoreExports.begin(); it != semaphoreExports.end();) {
            if (it->second.device == dispatch && it->second.semaphore == semaphore) it = semaphoreExports.erase(it); else ++it;
        }
    });
    dispatch->vkDestroySemaphore(device, semaphore, allocator);
}

VKAPI_ATTR VkResult VKAPI_CALL remixGetSemaphoreWin32Handle(VkDevice device,
        const VkSemaphoreGetWin32HandleInfoKHR* info, HANDLE* handle) {
    auto dispatch = deviceFor(device);
    VkResult result = dispatch->vkGetSemaphoreWin32HandleKHR(device, info, handle);
    if (result == VK_SUCCESS && info->handleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT) {
        observe([&] { semaphoreExports.insert_or_assign(*handle, SemaphoreExport { dispatch, info->semaphore }); });
    }
    return result;
}

VKAPI_ATTR void VKAPI_CALL remixCmdPipelineBarrier(VkCommandBuffer commandBuffer,
        VkPipelineStageFlags sourceStage, VkPipelineStageFlags destinationStage, VkDependencyFlags flags,
        uint32_t memoryCount, const VkMemoryBarrier* memories,
        uint32_t bufferCount, const VkBufferMemoryBarrier* buffers,
        uint32_t imageCount, const VkImageMemoryBarrier* images) {
    auto dispatch = deviceFor(commandBuffer);
    dispatch->vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, flags,
            memoryCount, memories, bufferCount, buffers, imageCount, images);
    for (uint32_t i = 0; i < imageCount; ++i) observeRelease(*dispatch, images[i]);
}

VKAPI_ATTR void VKAPI_CALL remixCmdPipelineBarrier2(VkCommandBuffer commandBuffer, const VkDependencyInfo* info) {
    auto dispatch = deviceFor(commandBuffer);
    dispatch->vkCmdPipelineBarrier2(commandBuffer, info);
    for (uint32_t i = 0; i < info->imageMemoryBarrierCount; ++i) observeRelease(*dispatch, info->pImageMemoryBarriers[i]);
}

static PFN_vkVoidFunction deviceHook(const char* name) {
#define HOOK(api, hook) if (std::strcmp(name, #api) == 0) return reinterpret_cast<PFN_vkVoidFunction>(hook);
    HOOK(vkDestroyDevice, remixDestroyDevice)
    HOOK(vkCreateImage, remixCreateImage)
    HOOK(vkDestroyImage, remixDestroyImage)
    HOOK(vkAllocateMemory, remixAllocateMemory)
    HOOK(vkFreeMemory, remixFreeMemory)
    HOOK(vkBindImageMemory, remixBindImageMemory)
    HOOK(vkBindImageMemory2, remixBindImageMemory2)
    HOOK(vkBindImageMemory2KHR, remixBindImageMemory2)
    HOOK(vkGetMemoryWin32HandleKHR, remixGetMemoryWin32Handle)
    HOOK(vkCreateSemaphore, remixCreateSemaphore)
    HOOK(vkDestroySemaphore, remixDestroySemaphore)
    HOOK(vkGetSemaphoreWin32HandleKHR, remixGetSemaphoreWin32Handle)
    HOOK(vkCmdPipelineBarrier, remixCmdPipelineBarrier)
    HOOK(vkCmdPipelineBarrier2, remixCmdPipelineBarrier2)
    HOOK(vkCmdPipelineBarrier2KHR, remixCmdPipelineBarrier2)
#undef HOOK
    return nullptr;
}

__declspec(dllexport) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name) {
    if (!std::strcmp(name, "vkGetDeviceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetDeviceProcAddr);
    if (!device) return nullptr;
    auto next = deviceFor(device)->gdpa(device, name);
    if (!next) return nullptr;
    auto hook = deviceHook(name);
    return hook ? hook : next;
}

__declspec(dllexport) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name) {
    if (!std::strcmp(name, "vkGetInstanceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetInstanceProcAddr);
    if (!std::strcmp(name, "vkGetDeviceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetDeviceProcAddr);
    if (!std::strcmp(name, "vkCreateInstance")) return reinterpret_cast<PFN_vkVoidFunction>(remixCreateInstance);
    if (!instance) return nullptr;
    InstanceDispatch dispatch;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        dispatch = instances.at(dispatchKey(instance));
    }
    auto next = dispatch.gipa(instance, name);
    if (!next) return nullptr;
    if (!std::strcmp(name, "vkDestroyInstance")) return reinterpret_cast<PFN_vkVoidFunction>(remixDestroyInstance);
    if (!std::strcmp(name, "vkCreateDevice")) return reinterpret_cast<PFN_vkVoidFunction>(remixCreateDevice);
    auto hook = deviceHook(name);
    return hook ? hook : next;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL remixGetPhysicalDeviceProcAddr(VkInstance instance, const char* name) {
    InstanceDispatch dispatch;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        dispatch = instances.at(dispatchKey(instance));
    }
    return dispatch.gpdpa ? dispatch.gpdpa(instance, name) : dispatch.gipa(instance, name);
}

__declspec(dllexport) VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface* info) {
    if (!info || info->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT || info->loaderLayerInterfaceVersion < 2) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    info->loaderLayerInterfaceVersion = 2;
    info->pfnGetInstanceProcAddr = vkGetInstanceProcAddr;
    info->pfnGetDeviceProcAddr = vkGetDeviceProcAddr;
    info->pfnGetPhysicalDeviceProcAddr = remixGetPhysicalDeviceProcAddr;
    return VK_SUCCESS;
}

JNIEXPORT jlong JNICALL Java_com_nvidia_rtxremix_RemixBridge_vulkanImageGeneration(
        JNIEnv* env, jclass, jlong handle, jint width, jint height, jint format) {
    try {
        std::lock_guard<std::mutex> lock(registryMutex);
        return static_cast<jlong>(findImage(findMemory(reinterpret_cast<HANDLE>(handle)), width, height,
                static_cast<VkFormat>(format)).generation);
    } catch (const std::exception& error) {
        throwJava(env, error.what());
        return 0;
    }
}

// The fixed prefix is versioned independently of the runtime C API. Family and
// view-format arrays follow it. No native pointers escape into Java metadata.
JNIEXPORT jlongArray JNICALL Java_com_nvidia_rtxremix_RemixBridge_vulkanImageDescription(
        JNIEnv* env, jclass, jlong handle, jint width, jint height, jint format) {
    try {
        std::vector<jlong> words;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            auto& exported = findMemory(reinterpret_cast<HANDLE>(handle));
            auto& image = findImage(exported, width, height, static_cast<VkFormat>(format));
            auto& memory = exported.device->allocations.at(exported.memory);
            auto& create = image.create;
            words = { 1, static_cast<jlong>(image.generation), static_cast<jlong>(memory.size), memory.type,
                memory.flags, memory.deviceMask, memory.dedicatedImage != VK_NULL_HANDLE,
                static_cast<jlong>(image.offset), create.flags, create.imageType, create.format,
                create.extent.width, create.extent.height, create.extent.depth, create.mipLevels,
                create.arrayLayers, create.samples, create.tiling, create.usage, create.sharingMode,
                create.initialLayout, image.externalTypes, image.hasFormatList, image.hasStencilUsage,
                image.stencilUsage, image.externalFamily, image.externalLayout,
                uuidWord(exported.device->deviceUuid.data()), uuidWord(exported.device->deviceUuid.data() + 8),
                uuidWord(exported.device->driverUuid.data()), uuidWord(exported.device->driverUuid.data() + 8),
                static_cast<jlong>(image.families.size()), static_cast<jlong>(image.formats.size()) };
            for (uint32_t family : image.families) words.push_back(family);
            for (VkFormat viewFormat : image.formats) words.push_back(viewFormat);
        }
        jlongArray result = env->NewLongArray(static_cast<jsize>(words.size()));
        if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(words.size()), words.data());
        return result;
    } catch (const std::exception& error) {
        throwJava(env, error.what());
        return nullptr;
    }
}

JNIEXPORT jlongArray JNICALL Java_com_nvidia_rtxremix_RemixBridge_vulkanSemaphoreDescription(
        JNIEnv* env, jclass, jlong handle) {
    try {
        std::array<jlong, 6> words;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            if (trackingFailed.load()) throw std::runtime_error("The Vulkan export layer lost semaphore metadata");
            auto found = semaphoreExports.find(reinterpret_cast<HANDLE>(handle));
            if (found == semaphoreExports.end() || !found->second.device->alive.load()) {
                throw std::runtime_error("The Vulkan export layer did not observe this Remix semaphore; "
                        "VK_LAYER_RTXREMIX_export_info must be enabled before creating the runtime device");
            }
            auto& exported = found->second;
            if (!exported.device->hasIdentity) throw std::runtime_error("The exporting Vulkan device has no UUID");
            words = { 1, exported.device->semaphores.at(exported.semaphore),
                uuidWord(exported.device->deviceUuid.data()), uuidWord(exported.device->deviceUuid.data() + 8),
                uuidWord(exported.device->driverUuid.data()), uuidWord(exported.device->driverUuid.data() + 8) };
        }
        jlongArray result = env->NewLongArray(static_cast<jsize>(words.size()));
        if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(words.size()), words.data());
        return result;
    } catch (const std::exception& error) {
        throwJava(env, error.what());
        return nullptr;
    }
}

} // extern "C"
