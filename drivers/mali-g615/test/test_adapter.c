/*
 * Drives the DroidDeck adapter the way a Vulkan loader would, against the mock backend beside it.
 *
 * This is the only way to know what the adapter does to a session without a phone: it is a
 * pass-through, so "pass-through" has to be proved, and the two things it adds - the DRM facade and
 * the trace - have to be seen happening. The adapter under test is built with DD_MALI_NODE pointing
 * at /dev/null, which is a char device, so the node lookup has a real answer to find.
 *
 * Built and run by run_adapter_tests.sh. Not part of the app or the driver package.
 */
#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>

static int fails;

#define CHECK(cond, what) do {                                             \
    if (cond) printf("ok   %s\n", (what));                                  \
    else { printf("FAIL %s\n", (what)); ++fails; }                          \
} while (0)

typedef PFN_vkVoidFunction (*gipa_t)(VkInstance, const char *);
typedef VkResult (*negotiate_t)(uint32_t *);
typedef VkResult (*enum_ext_t)(VkPhysicalDevice, const char *, uint32_t *, VkExtensionProperties *);
typedef void (*get_props2_t)(VkPhysicalDevice, VkPhysicalDeviceProperties2 *);
typedef VkResult (*create_device_t)(VkPhysicalDevice, const VkDeviceCreateInfo *,
                                    const VkAllocationCallbacks *, VkDevice *);
typedef VkResult (*queue_submit_t)(VkQueue, uint32_t, const VkSubmitInfo *, VkFence);
typedef VkResult (*present_t)(VkQueue, const VkPresentInfoKHR *);
typedef VkResult (*wait_fences_t)(VkDevice, uint32_t, const VkFence *, VkBool32, uint64_t);

int main(int argc, char **argv) {
    if (argc < 2) {
        printf("FAIL usage: %s <adapter.so> [backend.so]\n", argv[0]);
        return 2;
    }
    const char *adapter_path = argv[1];
    const char *backend_path = argc > 2 ? argv[2] : NULL;
    int want_trace = getenv("DD_TEST_TRACE") != NULL;

    void *adapter = dlopen(adapter_path, RTLD_NOW | RTLD_LOCAL);
    if (!adapter) {
        printf("FAIL the adapter did not load: %s\n", dlerror());
        return 1;
    }
    negotiate_t negotiate = (negotiate_t)dlsym(adapter, "vk_icdNegotiateLoaderICDInterfaceVersion");
    gipa_t gipa = (gipa_t)dlsym(adapter, "vk_icdGetInstanceProcAddr");
    CHECK(negotiate && gipa, "the adapter exports the ICD entry points a loader needs");
    if (!negotiate || !gipa) return 1;

    uint32_t version = 8;
    CHECK(negotiate(&version) == VK_SUCCESS, "interface negotiation succeeds");
    CHECK(version == 7, "and stops at interface 7, the newest the adapter implements");

    /* The instance is a token here: the mock ignores it, and passing one is what a loader does. */
    VkInstance instance = (VkInstance)(uintptr_t)0x1111;
    VkPhysicalDevice physical = (VkPhysicalDevice)(uintptr_t)0x2222;

    enum_ext_t enum_ext = (enum_ext_t)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    get_props2_t get_props2 = (get_props2_t)gipa(instance, "vkGetPhysicalDeviceProperties2");
    CHECK(enum_ext && get_props2, "device-level queries are reachable through the adapter");
    if (!enum_ext || !get_props2) return 1;

    uint32_t count = 0;
    CHECK(enum_ext(physical, NULL, &count, NULL) == VK_SUCCESS, "the extension count comes back");
    CHECK(count == 3, "the two the backend has, plus VK_EXT_physical_device_drm the adapter adds");
    VkExtensionProperties listed[8];
    memset(listed, 0, sizeof(listed));
    uint32_t capacity = count;
    enum_ext(physical, NULL, &capacity, listed);
    int drm_found = 0;
    for (uint32_t i = 0; i < capacity; ++i)
        if (!strcmp(listed[i].extensionName, VK_EXT_PHYSICAL_DEVICE_DRM_EXTENSION_NAME)) ++drm_found;
    CHECK(drm_found == 1, "and it is named exactly once");

    /* The DRM node identity gamescope reads. */
    VkPhysicalDeviceDrmPropertiesEXT drm;
    memset(&drm, 0, sizeof(drm));
    drm.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRM_PROPERTIES_EXT;
    VkPhysicalDeviceProperties2 props;
    memset(&props, 0, sizeof(props));
    props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    props.pNext = &drm;
    get_props2(physical, &props);
    CHECK(!strcmp(props.properties.deviceName, "Mali-G615 MC6"),
          "the backend's own properties arrive untouched");
    struct stat node;
    int node_ok = stat(DD_MALI_NODE, &node) == 0 && S_ISCHR(node.st_mode);
    CHECK(node_ok, "the render node this build was told to look for is there");
    if (node_ok) {
        CHECK(drm.hasPrimary == VK_TRUE && drm.hasRender == VK_TRUE,
              "the adapter claims primary and render for it");
        CHECK(drm.primaryMajor == (int64_t)major(node.st_rdev) &&
              drm.primaryMinor == (int64_t)minor(node.st_rdev),
              "and the major/minor it reports are the node's real ones");
    }

    /* The adapter must not let an extension it invented reach a backend that never advertised it. */
    create_device_t create_device = (create_device_t)gipa(instance, "vkCreateDevice");
    CHECK(create_device != NULL, "vkCreateDevice is reachable");
    if (create_device) {
        const char *wanted[] = {VK_EXT_PHYSICAL_DEVICE_DRM_EXTENSION_NAME, "VK_KHR_swapchain"};
        VkDeviceCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        info.enabledExtensionCount = 2;
        info.ppEnabledExtensionNames = wanted;
        VkDevice device = NULL;
        CHECK(create_device(physical, &info, NULL, &device) == VK_SUCCESS,
              "vkCreateDevice succeeds with the facade extension requested");
        printf("note: the backend printed the extensions it received\n");
    }

    /* Tracing: the calls in the trace must be the calls that reach the GPU. */
    queue_submit_t submit = (queue_submit_t)gipa(instance, "vkQueueSubmit");
    present_t present = (present_t)gipa(instance, "vkQueuePresentKHR");
    wait_fences_t wait_fences = (wait_fences_t)gipa(instance, "vkWaitForFences");
    CHECK(submit && present && wait_fences, "submit, present and fence waits resolve");
    if (submit && present && wait_fences) {
        CHECK(submit((VkQueue)(uintptr_t)0x4444, 0, NULL, (VkFence)(uintptr_t)0x7777) == VK_SUCCESS,
              "a submit through the adapter reaches the backend");
        VkSwapchainKHR swapchain = (VkSwapchainKHR)(uintptr_t)0x5c00;
        VkPresentInfoKHR present_info;
        memset(&present_info, 0, sizeof(present_info));
        present_info.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present_info.swapchainCount = 1;
        present_info.pSwapchains = &swapchain;
        /* Always called. With MOCK_HANG the process dies inside it and this assertion never
           happens - that is the case being tested, and the trace file is what gets read after. */
        CHECK(present((VkQueue)(uintptr_t)0x4444, &present_info) == VK_SUCCESS,
              "a present through the adapter reaches the backend");
        /* The backend answers VK_TIMEOUT once, on purpose: an unfired fence has to be traceable too. */
        VkFence fence = (VkFence)(uintptr_t)0x7777;
        CHECK(wait_fences((VkDevice)(uintptr_t)0xd1ce, 1, &fence, VK_TRUE, 0) == VK_TIMEOUT,
              "a fence that does not signal comes back as the timeout it is");

        if (backend_path) {
            void *bare = dlopen(backend_path, RTLD_NOW | RTLD_LOCAL);
            gipa_t bare_gipa = bare ? (gipa_t)dlsym(bare, "vk_icdGetInstanceProcAddr") : NULL;
            void *direct = bare_gipa ? (void *)bare_gipa(NULL, "vkQueueSubmit") : NULL;
            char message[256];
            snprintf(message, sizeof(message),
                     "the adapter %s the backend's own pointer for vkQueueSubmit",
                     want_trace ? "interposes itself in front of" : "hands on untouched:");
            CHECK(direct && (want_trace ? direct != (void *)submit : direct == (void *)submit),
                  message);
        }
    }

    return fails ? 1 : 0;
}
