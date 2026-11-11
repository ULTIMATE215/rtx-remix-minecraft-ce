/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */

// Tests the real layer entry points against a downstream dispatch table. No
// graphics driver is needed; the linked DLL is also checked as a loader client.
#include "vulkan_exports.cpp"
#include <iostream>

namespace {

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

template<class F> void rejects(F&& operation) {
    bool rejected = false;
    try { operation(); } catch (const std::runtime_error&) { rejected = true; }
    require(rejected, "Incomplete export metadata was accepted");
}

struct Dispatchable { void* key; };
int instanceKey, deviceKey;
Dispatchable instanceObject { &instanceKey }, physicalObject { &instanceKey };
Dispatchable deviceObject { &deviceKey }, commandsObject { &deviceKey };
VkInstance mockInstance = reinterpret_cast<VkInstance>(&instanceObject);
VkPhysicalDevice mockPhysical = reinterpret_cast<VkPhysicalDevice>(&physicalObject);
VkDevice mockDevice = reinterpret_cast<VkDevice>(&deviceObject);
VkCommandBuffer mockCommands = reinterpret_cast<VkCommandBuffer>(&commandsObject);
VkImage mockImage = reinterpret_cast<VkImage>(0x1100);
VkDeviceMemory mockMemory = reinterpret_cast<VkDeviceMemory>(0x2200);
VkSemaphore mockSemaphore = reinterpret_cast<VkSemaphore>(0x3300);
HANDLE mockHandle = reinterpret_cast<HANDLE>(0x4400);
VkResult driverResult = VK_SUCCESS;
const VkImageCreateInfo* receivedImage = nullptr;
const VkImageMemoryBarrier* receivedBarrier = nullptr;
const VkDependencyInfo* receivedDependency = nullptr;

VKAPI_ATTR VkResult VKAPI_CALL createInstance(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance* out) {
    *out = mockInstance;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroyInstance(VkInstance, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL createDevice(VkPhysicalDevice, const VkDeviceCreateInfo*, const VkAllocationCallbacks*, VkDevice* out) {
    *out = mockDevice;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroyDevice(VkDevice, const VkAllocationCallbacks*) {}
VKAPI_ATTR void VKAPI_CALL properties(VkPhysicalDevice, VkPhysicalDeviceProperties2* out) {
    auto ids = static_cast<VkPhysicalDeviceIDProperties*>(out->pNext);
    for (uint8_t i = 0; i < VK_UUID_SIZE; i++) { ids->deviceUUID[i] = i; ids->driverUUID[i] = i + 16; }
}
VKAPI_ATTR VkResult VKAPI_CALL createImage(VkDevice, const VkImageCreateInfo* info, const VkAllocationCallbacks*, VkImage* out) {
    receivedImage = info;
    *out = mockImage;
    return driverResult;
}
VKAPI_ATTR void VKAPI_CALL destroyImage(VkDevice, VkImage, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL allocateMemory(VkDevice, const VkMemoryAllocateInfo*, const VkAllocationCallbacks*, VkDeviceMemory* out) {
    *out = mockMemory;
    return driverResult;
}
VKAPI_ATTR void VKAPI_CALL freeMemory(VkDevice, VkDeviceMemory, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL bind(VkDevice, VkImage, VkDeviceMemory, VkDeviceSize) { return driverResult; }
VKAPI_ATTR VkResult VKAPI_CALL bind2(VkDevice, uint32_t, const VkBindImageMemoryInfo*) { return driverResult; }
VKAPI_ATTR VkResult VKAPI_CALL getMemoryHandle(VkDevice, const VkMemoryGetWin32HandleInfoKHR*, HANDLE* out) {
    *out = mockHandle;
    return driverResult;
}
VKAPI_ATTR VkResult VKAPI_CALL createSemaphore(VkDevice, const VkSemaphoreCreateInfo*, const VkAllocationCallbacks*, VkSemaphore* out) {
    *out = mockSemaphore;
    return driverResult;
}
VKAPI_ATTR void VKAPI_CALL destroySemaphore(VkDevice, VkSemaphore, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL getSemaphoreHandle(VkDevice, const VkSemaphoreGetWin32HandleInfoKHR*, HANDLE* out) {
    *out = mockHandle;
    return driverResult;
}
VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags, VkDependencyFlags,
        uint32_t, const VkMemoryBarrier*, uint32_t, const VkBufferMemoryBarrier*, uint32_t, const VkImageMemoryBarrier* images) {
    receivedBarrier = images;
}
VKAPI_ATTR void VKAPI_CALL barrier2(VkCommandBuffer, const VkDependencyInfo* info) { receivedDependency = info; }
VKAPI_ATTR void VKAPI_CALL unobservedFunction() {}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice, const char* name) {
#define NEXT(api, function) if (!std::strcmp(name, #api)) return reinterpret_cast<PFN_vkVoidFunction>(function);
    NEXT(vkDestroyDevice, destroyDevice)
    NEXT(vkCreateImage, createImage)
    NEXT(vkDestroyImage, destroyImage)
    NEXT(vkAllocateMemory, allocateMemory)
    NEXT(vkFreeMemory, freeMemory)
    NEXT(vkBindImageMemory, bind)
    NEXT(vkBindImageMemory2, bind2)
    NEXT(vkBindImageMemory2KHR, bind2)
    NEXT(vkGetMemoryWin32HandleKHR, getMemoryHandle)
    NEXT(vkCreateSemaphore, createSemaphore)
    NEXT(vkDestroySemaphore, destroySemaphore)
    NEXT(vkGetSemaphoreWin32HandleKHR, getSemaphoreHandle)
    NEXT(vkCmdPipelineBarrier, barrier)
    NEXT(vkCmdPipelineBarrier2, barrier2)
    NEXT(vkCmdPipelineBarrier2KHR, barrier2)
    NEXT(vkDeviceWaitIdle, unobservedFunction)
#undef NEXT
    return nullptr;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gipa(VkInstance, const char* name) {
    if (!std::strcmp(name, "vkCreateInstance")) return reinterpret_cast<PFN_vkVoidFunction>(createInstance);
    if (!std::strcmp(name, "vkDestroyInstance")) return reinterpret_cast<PFN_vkVoidFunction>(destroyInstance);
    if (!std::strcmp(name, "vkCreateDevice")) return reinterpret_cast<PFN_vkVoidFunction>(createDevice);
    if (!std::strcmp(name, "vkGetPhysicalDeviceProperties2")) return reinterpret_cast<PFN_vkVoidFunction>(properties);
    return gdpa(VK_NULL_HANDLE, name);
}

struct Fixture {
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    HANDLE handle = nullptr;

    Fixture() {
        driverResult = VK_SUCCESS;
        trackingFailed = false;
        VkLayerInstanceLink link { nullptr, gipa, nullptr };
        VkLayerInstanceCreateInfo layer {};
        layer.sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO;
        layer.function = VK_LAYER_LINK_INFO;
        layer.u.pLayerInfo = &link;
        VkInstanceCreateInfo info { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, &layer };
        require(remixCreateInstance(&info, nullptr, &instance) == VK_SUCCESS, "Instance chain failed");
        require(layer.u.pLayerInfo == nullptr, "Instance chain did not advance");
        VkLayerDeviceLink deviceLink { nullptr, gipa, gdpa };
        VkLayerDeviceCreateInfo deviceLayer {};
        deviceLayer.sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO;
        deviceLayer.function = VK_LAYER_LINK_INFO;
        deviceLayer.u.pLayerInfo = &deviceLink;
        VkDeviceCreateInfo deviceInfo { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &deviceLayer };
        require(remixCreateDevice(mockPhysical, &deviceInfo, nullptr, &device) == VK_SUCCESS, "Device chain failed");
        require(deviceLayer.u.pLayerInfo == nullptr, "Device chain did not advance");
    }

    ~Fixture() {
        remixDestroyDevice(device, nullptr);
        remixDestroyInstance(instance, nullptr);
    }

    void exportImage() {
        VkFormat formats[] { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB };
        VkImageFormatListCreateInfo list { VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO, nullptr, 2, formats };
        VkExternalMemoryImageCreateInfo external { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
                &list, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT };
        VkImageCreateInfo info { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &external };
        info.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
        info.imageType = VK_IMAGE_TYPE_2D;
        info.format = VK_FORMAT_R8G8B8A8_UNORM;
        info.extent = {16, 16, 1};
        info.mipLevels = info.arrayLayers = 1;
        info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        require(remixCreateImage(device, &info, nullptr, &image) == VK_SUCCESS, "Image creation failed");
        require(receivedImage == &info, "Layer changed the image creation parameters");
        VkMemoryDedicatedAllocateInfo dedicated { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, nullptr, image, VK_NULL_HANDLE };
        VkMemoryAllocateFlagsInfo flags { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO, &dedicated,
                VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT, 0 };
        VkExportMemoryAllocateInfo exported { VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, &flags,
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT };
        VkMemoryAllocateInfo allocation { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &exported, 4096, 4 };
        require(remixAllocateMemory(device, &allocation, nullptr, &memory) == VK_SUCCESS, "Allocation failed");
        require(remixBindImageMemory(device, image, memory, 0) == VK_SUCCESS, "Bind failed");
        VkMemoryGetWin32HandleInfoKHR get { VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR, nullptr, memory,
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT };
        require(remixGetMemoryWin32Handle(device, &get, &handle) == VK_SUCCESS, "Export failed");
    }

    void release(bool complete = true) {
        VkImageMemoryBarrier transfer { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        transfer.image = image;
        transfer.oldLayout = transfer.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        transfer.srcQueueFamilyIndex = 0;
        transfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
        transfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, complete ? 1u : 0u, 0, 1};
        remixCmdPipelineBarrier(mockCommands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0, 0, nullptr, 0, nullptr, 1, &transfer);
        require(receivedBarrier == &transfer, "Layer changed a barrier");
    }

    ImageDescription& description() { return findImage(findMemory(handle), 16, 16, VK_FORMAT_R8G8B8A8_UNORM); }
};

void metadataAndReuse() {
    Fixture fixture;
    fixture.exportImage();
    rejects([&] { fixture.description(); });
    fixture.release(false);
    rejects([&] { fixture.description(); });
    fixture.release();
    auto& description = fixture.description();
    uint64_t generation = description.generation;
    require(description.formats == std::vector<VkFormat>({VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB}),
            "Layer did not copy the view formats");
    auto device = deviceFor(fixture.device);
    const auto& memory = device->allocations.at(fixture.memory);
    require(memory.size == 4096 && memory.type == 4 && memory.dedicatedImage == fixture.image
            && memory.flags == VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT, "Allocation metadata differs");
    require(device->deviceUuid[15] == 15 && device->driverUuid[15] == 31, "Device identity differs");
    device->images.at(fixture.image).unsupported = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_LIST_CREATE_INFO_EXT;
    rejects([&] { fixture.description(); });
    device->images.at(fixture.image).unsupported = VK_STRUCTURE_TYPE_MAX_ENUM;
    device->allocations.at(fixture.memory).unsupported = VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR;
    rejects([&] { fixture.description(); });
    remixDestroyImage(fixture.device, fixture.image, nullptr);
    rejects([&] { fixture.description(); });
    remixFreeMemory(fixture.device, fixture.memory, nullptr);
    rejects([&] { findMemory(fixture.handle); });
    fixture.exportImage();
    fixture.release();
    require(fixture.description().generation != generation, "Recycled handles reused the old generation");
    trackingFailed = true;
    rejects([&] { fixture.description(); });
    trackingFailed = false;
}

void forwardingAndSynchronization2() {
    Fixture fixture;
    fixture.exportImage();
    require(vkGetDeviceProcAddr(fixture.device, "vkDeviceWaitIdle") == gdpa(fixture.device, "vkDeviceWaitIdle"),
            "An unobserved entry point was not forwarded");
    require(vkGetDeviceProcAddr(fixture.device, "vkMissing") == nullptr, "Missing downstream function was invented");
    require(vkGetDeviceProcAddr(fixture.device, "vkCmdPipelineBarrier2KHR")
            == reinterpret_cast<PFN_vkVoidFunction>(remixCmdPipelineBarrier2), "KHR barrier alias was not observed");
    VkImageMemoryBarrier2 transfer { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2 };
    transfer.image = fixture.image;
    transfer.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    transfer.srcQueueFamilyIndex = 0;
    transfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    transfer.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, VK_REMAINING_MIP_LEVELS, 0, VK_REMAINING_ARRAY_LAYERS };
    VkDependencyInfo dependency { VK_STRUCTURE_TYPE_DEPENDENCY_INFO };
    dependency.imageMemoryBarrierCount = 1;
    dependency.pImageMemoryBarriers = &transfer;
    remixCmdPipelineBarrier2(mockCommands, &dependency);
    require(receivedDependency == &dependency, "Layer changed synchronization2 dependency");
    require(fixture.description().externalLayout == VK_IMAGE_LAYOUT_GENERAL, "Synchronization2 release was missed");
    driverResult = VK_ERROR_OUT_OF_DEVICE_MEMORY;
    remixDestroyImage(fixture.device, fixture.image, nullptr);
    VkImageCreateInfo info { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    require(remixCreateImage(fixture.device, &info, nullptr, &fixture.image) == driverResult,
            "Layer changed the driver's error result");
    require(deviceFor(fixture.device)->images.empty(), "Failed creation was recorded as live");
}

void semaphoreLifetime() {
    Fixture fixture;
    VkSemaphoreTypeCreateInfo timeline { VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO, nullptr,
            VK_SEMAPHORE_TYPE_TIMELINE, 0 };
    VkSemaphoreCreateInfo info { VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &timeline };
    VkSemaphore semaphore;
    require(remixCreateSemaphore(fixture.device, &info, nullptr, &semaphore) == VK_SUCCESS, "Semaphore creation failed");
    VkSemaphoreGetWin32HandleInfoKHR get { VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR, nullptr, semaphore,
            VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT };
    HANDLE handle;
    require(remixGetSemaphoreWin32Handle(fixture.device, &get, &handle) == VK_SUCCESS, "Semaphore export failed");
    auto& exported = semaphoreExports.at(handle);
    require(exported.device->semaphores.at(semaphore) == VK_SEMAPHORE_TYPE_TIMELINE, "Timeline type was lost");
    remixDestroySemaphore(fixture.device, semaphore, nullptr);
    require(semaphoreExports.empty(), "Destroyed semaphore export remained live");
}

void linkedDll(const char* path) {
    HMODULE library = LoadLibraryA(path);
    require(library != nullptr, "Built remixbridge.dll could not be loaded");
    auto negotiate = reinterpret_cast<PFN_vkNegotiateLoaderLayerInterfaceVersion>(
            GetProcAddress(library, "vkNegotiateLoaderLayerInterfaceVersion"));
    require(negotiate != nullptr, "Missing layer negotiation export");
    VkNegotiateLayerInterface info {};
    info.sType = LAYER_NEGOTIATE_INTERFACE_STRUCT;
    info.loaderLayerInterfaceVersion = 3;
    require(negotiate(&info) == VK_SUCCESS && info.loaderLayerInterfaceVersion == 2
            && info.pfnGetInstanceProcAddr && info.pfnGetDeviceProcAddr && info.pfnGetPhysicalDeviceProcAddr,
            "Layer negotiation failed");
    const char* methods[] {"vulkanImageDescription", "vulkanImageGeneration", "vulkanSemaphoreDescription"};
    for (const char* method : methods) {
        std::string name = std::string("Java_com_nvidia_rtxremix_RemixBridge_") + method;
        require(GetProcAddress(library, name.c_str()) != nullptr, "Missing Vulkan JNI export");
    }
    FreeLibrary(library);
}

} // namespace

int main(int argc, char** argv) {
    try {
        metadataAndReuse();
        forwardingAndSynchronization2();
        semaphoreLifetime();
        require(devices.empty() && instances.empty() && memoryExports.empty() && semaphoreExports.empty(),
                "Device teardown retained exports");
        remixDestroyInstance(VK_NULL_HANDLE, nullptr);
        remixDestroyDevice(VK_NULL_HANDLE, nullptr);
        if (argc == 2) linkedDll(argv[1]);
        std::cout << "Vulkan export layer: metadata, reuse, ownership, forwarding, lifetime and DLL exports passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
