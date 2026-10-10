/*
 * A fake PanVK backend, for testing the DroidDeck adapter without a GPU.
 *
 * It speaks the ICD side of the contract the adapter expects (vk_icd* entry points), reports a
 * Mali-G615 MC6, and prints what it was asked so a test can assert what the adapter did to it:
 * which extensions reached vkCreateDevice, and whether the traced calls arrived at all.
 *
 * MOCK_HANG makes vkQueuePresentKHR kill the process from the middle of the call, which is the one
 * shape the adapter's trace exists to catch.
 *
 * Not part of the app or the driver package. Built by run_adapter_tests.sh.
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <vulkan/vk_icd.h>

#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void die_in_call(void) {
    if (getenv("MOCK_HANG")) {
        fflush(stdout);
        fflush(stderr);
        kill(getpid(), SIGKILL);
    }
}

static VKAPI_ATTR void VKAPI_CALL mb_get_physical_device_properties(
    VkPhysicalDevice device, VkPhysicalDeviceProperties *properties) {
    (void)device;
    memset(properties, 0, sizeof(*properties));
    /* No sType here, unlike VkPhysicalDeviceProperties2: a mock that assumes one agrees with a
       wrong assumption instead of testing it. tools/test/vulkan_offsets.c is the authority. */
    properties->apiVersion = VK_MAKE_VERSION(1, 3, 270);
    properties->driverVersion = 1;
    properties->vendorID = 0x13b5u;
    properties->deviceType = VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
    snprintf(properties->deviceName, sizeof(properties->deviceName), "%s", "Mali-G615 MC6");
}

static VKAPI_ATTR void VKAPI_CALL mb_get_physical_device_properties2(
    VkPhysicalDevice device, VkPhysicalDeviceProperties2 *properties) {
    mb_get_physical_device_properties(device, &properties->properties);
}

/** Two extensions, and deliberately NOT VK_EXT_physical_device_drm: adding it is the adapter's job. */
static VKAPI_ATTR VkResult VKAPI_CALL mb_enumerate_device_extension_properties(
    VkPhysicalDevice device, const char *layer_name, uint32_t *count,
    VkExtensionProperties *properties) {
    static const char *const names[] = {"VK_KHR_swapchain", "VK_EXT_xlib_surface"};
    const uint32_t n = sizeof(names) / sizeof(names[0]);
    (void)device;
    if (layer_name) { *count = 0; return VK_SUCCESS; }
    if (!properties) { *count = n; return VK_SUCCESS; }
    uint32_t copied = *count < n ? *count : n;
    for (uint32_t i = 0; i < copied; ++i) {
        snprintf(properties[i].extensionName, VK_MAX_EXTENSION_NAME_SIZE, "%s", names[i]);
        properties[i].specVersion = 1;
    }
    *count = copied;
    return copied < n ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL mb_create_device(VkPhysicalDevice physical_device,
                                                        const VkDeviceCreateInfo *create_info,
                                                        const VkAllocationCallbacks *allocator,
                                                        VkDevice *device) {
    (void)physical_device;
    (void)allocator;
    printf("backend: vkCreateDevice enabled %u extensions:",
           create_info ? create_info->enabledExtensionCount : 0u);
    for (uint32_t i = 0; create_info && i < create_info->enabledExtensionCount; ++i)
        printf(" %s", create_info->ppEnabledExtensionNames[i]);
    printf("\n");
    *device = (VkDevice)(uintptr_t)0xd1ce;
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL mb_queue_submit(VkQueue queue, uint32_t submit_count,
                                                      const VkSubmitInfo *submits, VkFence fence) {
    (void)queue; (void)fence;
    printf("backend: vkQueueSubmit %u\n", submit_count);
    (void)submits;
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL mb_queue_submit2(VkQueue queue, uint32_t submit_count,
                                                        const VkSubmitInfo2 *submits,
                                                        VkFence fence) {
    (void)queue; (void)fence; (void)submits;
    printf("backend: vkQueueSubmit2 %u\n", submit_count);
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL mb_queue_present(VkQueue queue,
                                                        const VkPresentInfoKHR *present_info) {
    (void)queue;
    printf("backend: vkQueuePresentKHR %u swapchains\n",
           present_info ? present_info->swapchainCount : 0u);
    die_in_call();
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL mb_acquire_next_image(VkDevice device,
                                                             VkSwapchainKHR swapchain,
                                                             uint64_t timeout,
                                                             VkSemaphore semaphore, VkFence fence,
                                                             uint32_t *image_index) {
    (void)device; (void)swapchain; (void)timeout; (void)semaphore; (void)fence;
    printf("backend: vkAcquireNextImageKHR\n");
    if (image_index) *image_index = 1;
    return VK_SUCCESS;
}

/** VK_TIMEOUT, once: the answer the guard's own probe treats as a hang, so the trace has a result to show. */
static VKAPI_ATTR VkResult VKAPI_CALL mb_wait_for_fences(VkDevice device, uint32_t count,
                                                          const VkFence *fences, VkBool32 wait_all,
                                                          uint64_t timeout) {
    (void)device; (void)fences; (void)wait_all; (void)timeout;
    printf("backend: vkWaitForFences %u\n", count);
    return VK_TIMEOUT;
}

static VKAPI_ATTR VkResult VKAPI_CALL mb_create_swapchain(VkDevice device,
                                                           const VkSwapchainCreateInfoKHR *info,
                                                           const VkAllocationCallbacks *allocator,
                                                           VkSwapchainKHR *swapchain) {
    (void)device; (void)allocator;
    printf("backend: vkCreateSwapchainKHR %ux%u mode=%d\n", info ? info->imageExtent.width : 0u,
           info ? info->imageExtent.height : 0u, info ? (int)info->presentMode : -1);
    *swapchain = (VkSwapchainKHR)(uintptr_t)0x5c00;
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL mb_queue_wait_idle(VkQueue queue) {
    (void)queue;
    printf("backend: vkQueueWaitIdle\n");
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL mb_destroy_device(VkDevice device,
                                                    const VkAllocationCallbacks *allocator) {
    (void)device; (void)allocator;
}

struct entry { const char *name; void *fn; };

static const struct entry TABLE[] = {
    {"vkGetPhysicalDeviceProperties", (void *)mb_get_physical_device_properties},
    {"vkGetPhysicalDeviceProperties2", (void *)mb_get_physical_device_properties2},
    {"vkEnumerateDeviceExtensionProperties", (void *)mb_enumerate_device_extension_properties},
    {"vkCreateDevice", (void *)mb_create_device},
    {"vkDestroyDevice", (void *)mb_destroy_device},
    {"vkQueueSubmit", (void *)mb_queue_submit},
    {"vkQueueSubmit2", (void *)mb_queue_submit2},
    {"vkQueuePresentKHR", (void *)mb_queue_present},
    {"vkAcquireNextImageKHR", (void *)mb_acquire_next_image},
    {"vkWaitForFences", (void *)mb_wait_for_fences},
    {"vkCreateSwapchainKHR", (void *)mb_create_swapchain},
    {"vkQueueWaitIdle", (void *)mb_queue_wait_idle},
};

static void *lookup(const char *name) {
    for (unsigned i = 0; i < sizeof(TABLE) / sizeof(TABLE[0]); ++i)
        if (!strcmp(TABLE[i].name, name)) return TABLE[i].fn;
    return NULL;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance instance,
                                                                    const char *name) {
    (void)instance;
    if (!name) return NULL;
    return (PFN_vkVoidFunction)lookup(name);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(VkInstance instance,
                                                                         const char *name) {
    return vk_icdGetInstanceProcAddr(instance, name);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance,
                                                                const char *name) {
    return vk_icdGetInstanceProcAddr(instance, name);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *name) {
    (void)device;
    return (PFN_vkVoidFunction)lookup(name);
}

VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t *version) {
    if (!version) return VK_ERROR_INITIALIZATION_FAILED;
    if (*version > 7) *version = 7;
    printf("backend: negotiated ICD interface %u\n", *version);
    return VK_SUCCESS;
}
