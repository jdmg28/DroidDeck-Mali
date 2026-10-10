/*
 * What the Vulkan ABI really says about the structs droiddeck-gpu-probe declares by hand.
 *
 * The probe cannot include vulkan_core.h - it runs in a guest that has no headers and no compiler -
 * so it lays out VkPhysicalDeviceProperties and friends with ctypes, from the specification. A
 * layout that is wrong by 16 bytes reads nonsense silently: no crash, no error, a device name that
 * is really the driver version. That happened, and no test caught it, because the fake loader used
 * to test the probe repeated the same mistake.
 *
 * So print the truth instead, and let check_probe_layout.py compare it with what the probe believes.
 * Build against the real headers:  cc -I<dir with vulkan/vulkan_core.h> -o offsets vulkan_offsets.c
 *
 * Not part of the app or the driver package.
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

#include <stddef.h>
#include <stdio.h>

#define FIELD(structure, member) \
    printf("%s.%s %zu\n", #structure, #member, offsetof(structure, member))
#define SIZE(structure) printf("%s.size %zu\n", #structure, sizeof(structure))

int main(void) {
    FIELD(VkPhysicalDeviceProperties, apiVersion);
    FIELD(VkPhysicalDeviceProperties, driverVersion);
    FIELD(VkPhysicalDeviceProperties, vendorID);
    FIELD(VkPhysicalDeviceProperties, deviceID);
    FIELD(VkPhysicalDeviceProperties, deviceType);
    FIELD(VkPhysicalDeviceProperties, deviceName);
    SIZE(VkPhysicalDeviceProperties);

    FIELD(VkPhysicalDeviceMemoryProperties, memoryTypeCount);
    FIELD(VkPhysicalDeviceMemoryProperties, memoryTypes);
    FIELD(VkPhysicalDeviceMemoryProperties, memoryHeapCount);
    FIELD(VkPhysicalDeviceMemoryProperties, memoryHeaps);
    SIZE(VkPhysicalDeviceMemoryProperties);

    FIELD(VkMemoryType, propertyFlags);
    FIELD(VkMemoryType, heapIndex);
    FIELD(VkMemoryHeap, size);
    FIELD(VkMemoryHeap, flags);
    FIELD(VkMemoryRequirements, size);
    FIELD(VkMemoryRequirements, alignment);
    FIELD(VkMemoryRequirements, memoryTypeBits);

    FIELD(VkQueueFamilyProperties, queueFlags);
    FIELD(VkQueueFamilyProperties, queueCount);
    FIELD(VkQueueFamilyProperties, timestampValidBits);
    FIELD(VkQueueFamilyProperties, minImageTransferGranularity);
    FIELD(VkExtent3D, width);
    FIELD(VkExtent3D, height);
    FIELD(VkExtent3D, depth);

    FIELD(VkApplicationInfo, sType);
    FIELD(VkApplicationInfo, pNext);
    FIELD(VkApplicationInfo, apiVersion);
    FIELD(VkInstanceCreateInfo, sType);
    FIELD(VkInstanceCreateInfo, pApplicationInfo);

    FIELD(VkDeviceQueueCreateInfo, sType);
    FIELD(VkDeviceQueueCreateInfo, queueFamilyIndex);
    FIELD(VkDeviceQueueCreateInfo, queueCount);
    FIELD(VkDeviceQueueCreateInfo, pQueuePriorities);
    FIELD(VkDeviceCreateInfo, sType);
    FIELD(VkDeviceCreateInfo, queueCreateInfoCount);
    FIELD(VkDeviceCreateInfo, pQueueCreateInfos);
    FIELD(VkDeviceCreateInfo, enabledExtensionCount);
    FIELD(VkDeviceCreateInfo, ppEnabledExtensionNames);

    FIELD(VkBufferCreateInfo, sType);
    FIELD(VkBufferCreateInfo, size);
    FIELD(VkBufferCreateInfo, usage);
    FIELD(VkMemoryAllocateInfo, sType);
    FIELD(VkMemoryAllocateInfo, allocationSize);
    FIELD(VkMemoryAllocateInfo, memoryTypeIndex);
    FIELD(VkSubmitInfo, sType);
    FIELD(VkSubmitInfo, commandBufferCount);
    FIELD(VkSubmitInfo, pCommandBuffers);
    FIELD(VkFenceCreateInfo, sType);
    FIELD(VkFenceCreateInfo, flags);

    FIELD(VkCommandPoolCreateInfo, sType);
    FIELD(VkCommandPoolCreateInfo, queueFamilyIndex);
    FIELD(VkCommandBufferAllocateInfo, sType);
    FIELD(VkCommandBufferAllocateInfo, commandPool);
    FIELD(VkCommandBufferAllocateInfo, level);
    FIELD(VkCommandBufferAllocateInfo, commandBufferCount);
    FIELD(VkCommandBufferBeginInfo, sType);
    return 0;
}
