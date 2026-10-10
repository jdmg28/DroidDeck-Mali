/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Load a Vulkan ICD through a runtime's own Vulkan loader and say what it found. Built for the
 * host (aarch64 cross-compiler in CI) and run through qemu-user, on the libraries of the rootfs it
 * will end up inside: the same question a session asks at start, answered where it can be seen.
 *
 *   probe <path to libvulkan.so.1> <path to the ICD manifest>
 *
 * with VK_DRIVER_FILES (and VK_ICD_FILENAMES, for older loaders) naming the manifest. Exits 0 only
 * when an instance is created and at least one physical device is there to name.
 */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VK_API_VERSION_1_1 0x00401000u
#define VK_API_VERSION_1_3 0x00403000u

struct app_info {
    uint32_t sType; const void *pNext;
    const char *applicationName; uint32_t applicationVersion;
    const char *engineName; uint32_t engineVersion;
    uint32_t apiVersion;
};

struct instance_info {
    uint32_t sType; const void *pNext; uint32_t flags;
    const void *pApplicationInfo;
    uint32_t enabledLayerCount; const char *const *ppEnabledLayerNames;
    uint32_t enabledExtensionCount; const char *const *ppEnabledExtensionNames;
};

/* VkPhysicalDeviceProperties up to the name, then room for the rest: the driver fills the whole
 * structure and would otherwise write past a short one. */
struct device_properties {
    uint32_t apiVersion;
    uint32_t driverVersion;
    uint32_t vendorId;
    uint32_t deviceId;
    uint32_t deviceType;
    char deviceName[256];
    uint8_t tail[8192];
};

typedef void *(*gipa_fn)(void *instance, const char *name);
typedef int32_t (*create_instance_fn)(const struct instance_info *, const void *, void **);
typedef int32_t (*count_devices_fn)(void *, uint32_t *, void *);
typedef int32_t (*list_devices_fn)(void *, uint32_t *, void **);
typedef void (*properties_fn)(void *, struct device_properties *);
typedef void (*destroy_instance_fn)(void *, const void *);

static const char *version(uint32_t v) {
    static char text[32];
    snprintf(text, sizeof text, "%u.%u.%u", v >> 22, (v >> 12) & 0x3ffu, v & 0xfffu);
    return text;
}

static void *entry(gipa_fn gipa, void *instance, const char *name, int required) {
    void *sym = gipa(instance, name);
    if (!sym && required) { fprintf(stderr, "probe: the driver has no %s\n", name); exit(3); }
    return sym;
}

int main(int argc, char **argv) {
    const char *loader = argc > 1 ? argv[1] : "/usr/lib/libvulkan.so.1";
    const char *manifest = argc > 2 ? argv[2] : getenv("VK_DRIVER_FILES");
    printf("probe: loader %s\nprobe: manifest %s\n", loader, manifest ? manifest : "(none)");

    void *handle = dlopen(loader, RTLD_NOW | RTLD_LOCAL);
    if (!handle) { fprintf(stderr, "probe: cannot dlopen %s: %s\n", loader, dlerror()); return 2; }
    gipa_fn gipa = (gipa_fn) dlsym(handle, "vkGetInstanceProcAddr");
    if (!gipa) { fprintf(stderr, "probe: %s has no vkGetInstanceProcAddr\n", loader); return 2; }

    create_instance_fn create = (create_instance_fn) entry(gipa, NULL, "vkCreateInstance", 1);
    /* Twice: at 1.3, which is what gamescope asks for, then at 1.1, so a driver that refuses 1.3
     * still says what it is instead of just failing. */
    uint32_t wanted[] = { VK_API_VERSION_1_3, VK_API_VERSION_1_1 };
    void *instance = NULL;
    for (unsigned i = 0; i < sizeof wanted / sizeof *wanted && !instance; i++) {
        struct app_info app = { 1, NULL, "droiddeck-probe", 1, "droiddeck-probe", 1, wanted[i] };
        struct instance_info info = { 1, NULL, 0, &app, 0, NULL, 0, NULL };
        int32_t result = create(&info, NULL, &instance);
        printf("probe: vkCreateInstance for Vulkan %s -> %d\n", version(wanted[i]), result);
        if (result != 0) instance = NULL;
    }
    if (!instance) { fprintf(stderr, "probe: no instance: the driver did not accept 1.3 or 1.1\n"); return 4; }

    count_devices_fn count = (count_devices_fn) entry(gipa, instance, "vkEnumeratePhysicalDevices", 1);
    list_devices_fn list = (list_devices_fn) entry(gipa, instance, "vkEnumeratePhysicalDevices", 1);
    properties_fn properties = (properties_fn) entry(gipa, instance, "vkGetPhysicalDeviceProperties", 1);

    uint32_t devices = 0;
    int32_t result = count(instance, &devices, NULL);
    printf("probe: vkEnumeratePhysicalDevices -> %d, %u device%s\n", result, devices, devices == 1 ? "" : "s");
    if (result != 0 || devices == 0) { fprintf(stderr, "probe: no physical device\n"); return 5; }

    void **handles = calloc(devices, sizeof *handles);
    result = list(instance, &devices, handles);
    if (result != 0) { fprintf(stderr, "probe: listing the devices failed (%d)\n", result); return 6; }
    for (uint32_t i = 0; i < devices; i++) {
        struct device_properties props;
        memset(&props, 0, sizeof props);
        properties(handles[i], &props);
        printf("probe: device %u: %s (Vulkan %s, driver %s, vendor 0x%04x, device 0x%04x, type %u)\n",
               i, props.deviceName, version(props.apiVersion), version(props.driverVersion),
               props.vendorId, props.deviceId, props.deviceType);
    }
    destroy_instance_fn destroy = (destroy_instance_fn) entry(gipa, instance, "vkDestroyInstance", 0);
    if (destroy) destroy(instance, NULL);
    return 0;
}
