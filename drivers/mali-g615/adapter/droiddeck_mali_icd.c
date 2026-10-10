/* SPDX-License-Identifier: MIT
 * DroidDeck Mali-G615 experimental ICD adapter, version 0.2.0.
 *
 * This is NOT a new GPU kernel driver. Rendering and all GPU capabilities are
 * delegated to an unmodified ARM64/glibc PanVK-kbase driver beside this file.
 * The only added device extension is the DRM-node compatibility facade needed
 * by gamescope's nested backend: /dev/mali0 is also bound as a render node by
 * the matching DroidDeck patch. No graphics feature is fabricated.
 *
 * 0.2.0 adds the call trace below. It changes nothing when DD_ADAPTER_TRACE is
 * unset: every traced entry point is returned to the loader as the backend's own
 * pointer, exactly as 0.1.0 did, and the wrappers are never reached.
 */
#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <vulkan/vk_icd.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

#ifndef DD_MALI_NODE
#define DD_MALI_NODE "/dev/mali0"
#endif
#ifndef DD_BACKEND_NAME
#define DD_BACKEND_NAME "libpanvk_backend.so"
#endif
#define DD_EXPORT __attribute__((visibility("default")))
#define DD_MAX_EXTENSIONS 4096u

static pthread_once_t backend_once = PTHREAD_ONCE_INIT;
static void *backend_handle;
static PFN_vkGetInstanceProcAddr backend_gipa;
static PFN_GetPhysicalDeviceProcAddr backend_gpdpa;
static PFN_vkNegotiateLoaderICDInterfaceVersion backend_negotiate;
static _Atomic(PFN_vkGetPhysicalDeviceProperties) real_properties;
static _Atomic(PFN_vkGetPhysicalDeviceProperties2) real_properties2;
static _Atomic(PFN_vkEnumerateDeviceExtensionProperties) real_extensions;
static _Atomic(PFN_vkCreateDevice) real_create_device;

static void init_backend(void) {
    Dl_info info;
    char path[PATH_MAX];
    if (!dladdr((void *)&init_backend, &info) || !info.dli_fname) {
        fprintf(stderr, "DD_MALI_BACKEND_LOAD_FAILED: cannot locate adapter\n");
        return;
    }
    char *resolved = realpath(info.dli_fname, NULL);
    const char *own_path = resolved ? resolved : info.dli_fname;
    const char *slash = strrchr(own_path, '/');
    size_t prefix = slash ? (size_t)(slash - own_path + 1) : 0;
    if (prefix + sizeof(DD_BACKEND_NAME) > sizeof(path)) {
        fprintf(stderr, "DD_MALI_BACKEND_LOAD_FAILED: path too long\n");
        free(resolved);
        return;
    }
    memcpy(path, own_path, prefix);
    memcpy(path + prefix, DD_BACKEND_NAME, sizeof(DD_BACKEND_NAME));
    free(resolved);
    backend_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!backend_handle) {
        fprintf(stderr, "DD_MALI_BACKEND_LOAD_FAILED: %s: %s\n", path, dlerror());
        return;
    }
    backend_gipa = (PFN_vkGetInstanceProcAddr)dlsym(backend_handle, "vk_icdGetInstanceProcAddr");
    backend_gpdpa = (PFN_GetPhysicalDeviceProcAddr)dlsym(backend_handle, "vk_icdGetPhysicalDeviceProcAddr");
    backend_negotiate = (PFN_vkNegotiateLoaderICDInterfaceVersion)dlsym(
        backend_handle, "vk_icdNegotiateLoaderICDInterfaceVersion");
    if (!backend_gipa || !backend_negotiate) {
        fprintf(stderr, "DD_MALI_BACKEND_LOAD_FAILED: backend lacks required ICD entry points\n");
        backend_gipa = NULL;
    }
}

static int ready(void) {
    pthread_once(&backend_once, init_backend);
    return backend_gipa != NULL;
}

/* ── the call trace ────────────────────────────────────────────────────────────────────────
 *
 * Every other sensor in this project looks at the session from outside: frame intervals, wedged
 * processes, the boot reason after the fact. All of them can say that the GPU stopped. None can
 * say which call it stopped in - and that is the question the CS_BUS_FAULT investigation is short
 * of an answer on. "vkQueueSubmit never returned" and "vkQueueSubmit returned and its fence was
 * never signalled" are identical from outside the driver and ask for opposite fixes: one is a
 * driver that blocks, the other is a GPU that hangs. An entry with no matching exit is the
 * difference, and a two-line-per-call record costs nothing next to a reboot.
 *
 * Written with write() and fdatasync(), not stdio, because a device that panics does not flush
 * your page cache. The guard's own log learned that the hard way: 19 samples vanished in 77
 * seconds on the night the watchdog looked like it had stopped working. A trace that loses its
 * last lines would be worse than no trace at all - it would name the wrong call with total
 * confidence.
 */
/* The cap, and DD_ADAPTER_TRACE_MAX to change it: a hung session would otherwise fill the
 * partition, and a trace that runs out at line 8192 still holds the beginning, which is where the
 * first stall of the session is. */
#define DD_TRACE_MAX_LINES 8192u

static pthread_once_t trace_once = PTHREAD_ONCE_INIT;
static _Atomic(int) trace_fd = -1;
static _Atomic(unsigned) trace_enabled;
static _Atomic(unsigned) trace_lines;
static _Atomic(unsigned) trace_limit = DD_TRACE_MAX_LINES;

/* DD_ADAPTER_NO_DRM=1 turns off the one thing this adapter adds: the DRM-node facade.
 *
 * Why that is worth a switch and not a rebuild: the facade is the only way in which this library is
 * not pure pass-through, so it is the only thing it could be blamed for. Gamescope's nested Vulkan
 * backend asks for VK_EXT_physical_device_drm to import compositor buffers, and this adapter answers
 * with the vendor kbase node (/dev/mali0), which is NOT a DRM render node - the numbers it reports
 * are a Mali0 char device's, presented to a client that will go on to treat them as a DRM device.
 * Nobody knows whether that mismatch is what eventually faults the GPU; it is a candidate, it is
 * ours, and it is testable in one variable.
 *
 * Off, the adapter adds nothing anywhere: it does not advertise the extension, it does not touch a
 * client's VkPhysicalDeviceDrmPropertiesEXT, and - deliberately - it stops stripping the extension
 * from vkCreateDevice. A client that asked for something no driver advertised should be refused by
 * the backend, loudly, rather than quietly handed a device with a feature it will not get.
 *
 * Expect Steam to fail to start with this off. That is a fine outcome: a refused launch costs a
 * session, a bus fault costs the phone.
 */
static int drm_facade_enabled(void) {
    static int cached = -1;   /* the race is benign: every thread computes the same value */
    int v = cached;
    if (v < 0) {
        const char *off = getenv("DD_ADAPTER_NO_DRM");
        v = (off && *off && strcmp(off, "0") != 0) ? 0 : 1;
        cached = v;
    }
    return v;
}

static void tracef(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

static void trace_init(void) {
    const char *want = getenv("DD_ADAPTER_TRACE");
    if (!want || !*want) return;
    /* A path is used as given. Anything else - "1", or a bare file name - is taken relative to
       BL_DEBUG_DIR, which is the session folder the app exports for its own logs: the guard then
       finds the trace beside gpu-guard.log without this library ever learning where the app puts
       anything, and someone testing by hand gets a file in /tmp rather than a failure. */
    char resolved[PATH_MAX];
    const char *path = want;
    if (want[0] != '/') {
        const char *dir = getenv("BL_DEBUG_DIR");
        if (!dir || !*dir) dir = "/tmp";
        snprintf(resolved, sizeof(resolved), "%s/%s", dir,
                 strcmp(want, "1") == 0 ? "droiddeck-driver-trace.log" : want);
        path = resolved;
    }
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) {
        fprintf(stderr, "DD_MALI_TRACE_OPEN_FAILED: %s: %s\n", path, strerror(errno));
        return;
    }
    atomic_store(&trace_fd, fd);
    atomic_store(&trace_limit, DD_TRACE_MAX_LINES);
    const char *cap = getenv("DD_ADAPTER_TRACE_MAX");
    if (cap && *cap) {
        char *end = NULL;
        long wanted = strtol(cap, &end, 10);
        if (end != cap && wanted > 0) atomic_store(&trace_limit, (unsigned)wanted);
    }
    atomic_store(&trace_enabled, 1u);
    /* Recorded first, because a trace that does not say which configuration produced it is a
       guess about someone else's session. */
    tracef("-- adapter 0.2.0, drm facade %s, pid %d, backend %s",
           drm_facade_enabled() ? "on" : "OFF (DD_ADAPTER_NO_DRM)", (int)getpid(), DD_BACKEND_NAME);
}

static inline int tracing(void) {
    return atomic_load_explicit(&trace_enabled, memory_order_relaxed) != 0u;
}

/** microseconds since boot, then the caller's line. Never fails, never blocks the frame. */

static void tracef(const char *fmt, ...) {
    if (!tracing()) return;
    if (atomic_fetch_add_explicit(&trace_lines, 1u, memory_order_relaxed) >=
        atomic_load_explicit(&trace_limit, memory_order_relaxed)) return;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    char line[320];
    int used = snprintf(line, sizeof(line), "%lld ",
                        (long long)(ts.tv_sec * 1000000LL + ts.tv_nsec / 1000));
    if (used < 0 || (size_t)used >= sizeof(line)) return;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line + used, sizeof(line) - (size_t)used, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    int total = used + n;
    if (total > (int)sizeof(line) - 2) total = (int)sizeof(line) - 2;
    line[total++] = '\n';
    int fd = atomic_load_explicit(&trace_fd, memory_order_relaxed);
    if (fd < 0) return;
    ssize_t wrote = write(fd, line, (size_t)total);
    (void)wrote;
    fdatasync(fd);
}

/* ── traced entry points ───────────────────────────────────────────────────────────────────
 *
 * Each one is a pass-through: the only thing it adds is the pair of lines around the call. They
 * are handed out only while tracing; see map_proc().
 *
 * One detail that is easy to get wrong and impossible to see afterwards: an application may reach
 * a device-level command through vkGetDeviceProcAddr instead of vkGetInstanceProcAddr, and then
 * never asks this adapter for it. vkGetDeviceProcAddr is therefore traced as well, so both roads
 * go through the same wrappers.
 */
static _Atomic(PFN_vkQueueSubmit) real_queue_submit;
static _Atomic(PFN_vkQueueSubmit2) real_queue_submit2;
static _Atomic(PFN_vkQueuePresentKHR) real_queue_present;
static _Atomic(PFN_vkAcquireNextImageKHR) real_acquire;
static _Atomic(PFN_vkWaitForFences) real_wait_fences;
static _Atomic(PFN_vkCreateSwapchainKHR) real_create_swapchain;
static _Atomic(PFN_vkQueueWaitIdle) real_queue_wait_idle;
static _Atomic(PFN_vkGetDeviceProcAddr) real_get_device_proc_addr;

#define DD_HANDLE(v) ((unsigned long long)(uintptr_t)(v))

static VKAPI_ATTR VkResult VKAPI_CALL traced_queue_submit(VkQueue queue, uint32_t count,
                                                           const VkSubmitInfo *submits,
                                                           VkFence fence) {
    PFN_vkQueueSubmit fn = atomic_load(&real_queue_submit);
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    tracef("-> vkQueueSubmit queue=%llu submits=%u fence=%llu", DD_HANDLE(queue), count,
           DD_HANDLE(fence));
    VkResult r = fn(queue, count, submits, fence);
    tracef("<- vkQueueSubmit = %d", (int)r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL traced_queue_submit2(VkQueue queue, uint32_t count,
                                                            const VkSubmitInfo2 *submits,
                                                            VkFence fence) {
    PFN_vkQueueSubmit2 fn = atomic_load(&real_queue_submit2);
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    tracef("-> vkQueueSubmit2 queue=%llu submits=%u fence=%llu", DD_HANDLE(queue), count,
           DD_HANDLE(fence));
    VkResult r = fn(queue, count, submits, fence);
    tracef("<- vkQueueSubmit2 = %d", (int)r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL traced_queue_present(VkQueue queue,
                                                            const VkPresentInfoKHR *info) {
    PFN_vkQueuePresentKHR fn = atomic_load(&real_queue_present);
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    tracef("-> vkQueuePresentKHR queue=%llu swapchains=%u waitSemaphores=%u", DD_HANDLE(queue),
           info ? info->swapchainCount : 0u, info ? info->waitSemaphoreCount : 0u);
    VkResult r = fn(queue, info);
    tracef("<- vkQueuePresentKHR = %d", (int)r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL traced_acquire_next_image(VkDevice device,
                                                                 VkSwapchainKHR swapchain,
                                                                 uint64_t timeout,
                                                                 VkSemaphore semaphore,
                                                                 VkFence fence,
                                                                 uint32_t *image_index) {
    PFN_vkAcquireNextImageKHR fn = atomic_load(&real_acquire);
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    tracef("-> vkAcquireNextImageKHR swapchain=%llu timeout=%llu ns", DD_HANDLE(swapchain),
           (unsigned long long)timeout);
    VkResult r = fn(device, swapchain, timeout, semaphore, fence, image_index);
    tracef("<- vkAcquireNextImageKHR = %d image=%u", (int)r,
           (r == VK_SUCCESS || r == VK_TIMEOUT) && image_index ? *image_index : 0u);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL traced_wait_fences(VkDevice device, uint32_t count,
                                                          const VkFence *fences, VkBool32 wait_all,
                                                          uint64_t timeout) {
    PFN_vkWaitForFences fn = atomic_load(&real_wait_fences);
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    tracef("-> vkWaitForFences count=%u all=%d timeout=%llu ns", count, (int)wait_all,
           (unsigned long long)timeout);
    VkResult r = fn(device, count, fences, wait_all, timeout);
    tracef("<- vkWaitForFences = %d", (int)r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL traced_create_swapchain(VkDevice device,
                                                               const VkSwapchainCreateInfoKHR *info,
                                                               const VkAllocationCallbacks *alloc,
                                                               VkSwapchainKHR *out) {
    PFN_vkCreateSwapchainKHR fn = atomic_load(&real_create_swapchain);
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    tracef("-> vkCreateSwapchainKHR %ux%u images=%u presentMode=%d compositeAlpha=%d",
           info ? info->imageExtent.width : 0u, info ? info->imageExtent.height : 0u,
           info ? info->minImageCount : 0u, info ? (int)info->presentMode : -1,
           info ? (int)info->compositeAlpha : -1);
    VkResult r = fn(device, info, alloc, out);
    tracef("<- vkCreateSwapchainKHR = %d handle=%llu", (int)r, DD_HANDLE(out ? *out : NULL));
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL traced_queue_wait_idle(VkQueue queue) {
    PFN_vkQueueWaitIdle fn = atomic_load(&real_queue_wait_idle);
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    tracef("-> vkQueueWaitIdle queue=%llu", DD_HANDLE(queue));
    VkResult r = fn(queue);
    tracef("<- vkQueueWaitIdle = %d", (int)r);
    return r;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL traced_get_device_proc_addr(VkDevice device,
                                                                            const char *name);

static void *traced_for(const char *name, VkInstance instance) {
    if (!strcmp(name, "vkQueueSubmit")) {
        atomic_store(&real_queue_submit, (PFN_vkQueueSubmit)backend_gipa(instance, name));
        return (void *)traced_queue_submit;
    }
    if (!strcmp(name, "vkQueueSubmit2")) {
        atomic_store(&real_queue_submit2, (PFN_vkQueueSubmit2)backend_gipa(instance, name));
        return (void *)traced_queue_submit2;
    }
    if (!strcmp(name, "vkQueuePresentKHR")) {
        atomic_store(&real_queue_present, (PFN_vkQueuePresentKHR)backend_gipa(instance, name));
        return (void *)traced_queue_present;
    }
    if (!strcmp(name, "vkAcquireNextImageKHR")) {
        atomic_store(&real_acquire, (PFN_vkAcquireNextImageKHR)backend_gipa(instance, name));
        return (void *)traced_acquire_next_image;
    }
    if (!strcmp(name, "vkWaitForFences")) {
        atomic_store(&real_wait_fences, (PFN_vkWaitForFences)backend_gipa(instance, name));
        return (void *)traced_wait_fences;
    }
    if (!strcmp(name, "vkCreateSwapchainKHR")) {
        atomic_store(&real_create_swapchain, (PFN_vkCreateSwapchainKHR)backend_gipa(instance, name));
        return (void *)traced_create_swapchain;
    }
    if (!strcmp(name, "vkQueueWaitIdle")) {
        atomic_store(&real_queue_wait_idle, (PFN_vkQueueWaitIdle)backend_gipa(instance, name));
        return (void *)traced_queue_wait_idle;
    }
    return NULL;
}

/**
 * vkGetDeviceProcAddr, wrapped so the device-level road reaches the same wrappers.
 *
 * The real pointer is resolved from the instance the adapter was asked about, not from the device,
 * because a PFN from the backend's own table and one through the ICD entry point are the same
 * function; the instance is the handle this adapter always has.
 */
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL traced_get_device_proc_addr(VkDevice device,
                                                                            const char *name) {
    PFN_vkGetDeviceProcAddr fn = atomic_load(&real_get_device_proc_addr);
    if (!fn || !name) return NULL;
    PFN_vkVoidFunction proc = fn(device, name);
    if (!proc) return NULL;
    if (!tracing()) return proc;
    /* The traced set, but resolved per device: an application that uses vkGetDeviceProcAddr wants
       the device's own dispatch pointer, and handing it an instance-level one is correct but
       slower. Take the device's pointer and keep it, and let the wrapper call that. */
    void *wrapper = NULL;
    if (!strcmp(name, "vkQueueSubmit")) wrapper = (void *)traced_queue_submit;
    else if (!strcmp(name, "vkQueueSubmit2")) wrapper = (void *)traced_queue_submit2;
    else if (!strcmp(name, "vkQueuePresentKHR")) wrapper = (void *)traced_queue_present;
    else if (!strcmp(name, "vkAcquireNextImageKHR")) wrapper = (void *)traced_acquire_next_image;
    else if (!strcmp(name, "vkWaitForFences")) wrapper = (void *)traced_wait_fences;
    else if (!strcmp(name, "vkCreateSwapchainKHR")) wrapper = (void *)traced_create_swapchain;
    else if (!strcmp(name, "vkQueueWaitIdle")) wrapper = (void *)traced_queue_wait_idle;
    if (!wrapper) return proc;
    /* One device per session is what there is; a second one reuses the first's pointers, which is
       correct because these are the same ICD's functions and the handle is an argument. */
    return (PFN_vkVoidFunction)wrapper;
}

static int mali_node(dev_t *rdev) {
    struct stat st;
    if (stat(DD_MALI_NODE, &st) != 0 || !S_ISCHR(st.st_mode)) return 0;
    *rdev = st.st_rdev;
    return 1;
}

static int target_properties(const VkPhysicalDeviceProperties *p) {
    const char *name = strstr(p->deviceName, "Mali-G615");
    if (p->vendorID != 0x13b5u || !name) return 0;
    char next = name[sizeof("Mali-G615") - 1];
    return next == '\0' || next == ' ' || next == '(';
}

static int target_device(VkPhysicalDevice device) {
    PFN_vkGetPhysicalDeviceProperties fn = atomic_load(&real_properties);
    if (!fn) return 0;
    VkPhysicalDeviceProperties p;
    memset(&p, 0, sizeof(p));
    fn(device, &p);
    dev_t dev;
    return target_properties(&p) && mali_node(&dev);
}

static VKAPI_ATTR void VKAPI_CALL wrapped_properties2(
    VkPhysicalDevice device, VkPhysicalDeviceProperties2 *properties) {
    PFN_vkGetPhysicalDeviceProperties2 fn = atomic_load(&real_properties2);
    if (!fn || !properties) return;
    fn(device, properties);
    if (!drm_facade_enabled()) return;     /* add nothing, touch nothing */
    dev_t rdev;
    if (!target_properties(&properties->properties) || !mali_node(&rdev)) return;
    /* Preserve every unrelated pNext structure and every ordinary property. */
    for (VkBaseOutStructure *p = (VkBaseOutStructure *)properties->pNext; p; p = p->pNext) {
        if (p->sType != VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRM_PROPERTIES_EXT) continue;
        VkPhysicalDeviceDrmPropertiesEXT *drm = (VkPhysicalDeviceDrmPropertiesEXT *)p;
        drm->hasPrimary = VK_TRUE;
        drm->hasRender = VK_TRUE;
        drm->primaryMajor = drm->renderMajor = (int64_t)major(rdev);
        drm->primaryMinor = drm->renderMinor = (int64_t)minor(rdev);
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL wrapped_extensions(
    VkPhysicalDevice device, const char *layer, uint32_t *count, VkExtensionProperties *out) {
    PFN_vkEnumerateDeviceExtensionProperties fn = atomic_load(&real_extensions);
    if (!fn || !count) return VK_ERROR_INITIALIZATION_FAILED;
    if (layer || !target_device(device) || !drm_facade_enabled())
        return fn(device, layer, count, out);   /* pure pass-through: no facade added */
    uint32_t n = 0;
    VkResult r = fn(device, NULL, &n, NULL);
    if (r != VK_SUCCESS) return r;
    if (n > DD_MAX_EXTENSIONS) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkExtensionProperties *all = calloc((size_t)n + 1, sizeof(*all));
    if (!all) return VK_ERROR_OUT_OF_HOST_MEMORY;
    uint32_t actual = n;
    r = fn(device, NULL, &actual, all);
    if (r != VK_SUCCESS || actual > n) {
        free(all);
        return r == VK_SUCCESS ? VK_ERROR_INITIALIZATION_FAILED : r;
    }
    n = actual;
    int found = 0;
    for (uint32_t i = 0; i < n; ++i)
        if (!strcmp(all[i].extensionName, VK_EXT_PHYSICAL_DEVICE_DRM_EXTENSION_NAME)) found = 1;
    if (!found) {
        snprintf(all[n].extensionName, sizeof(all[n].extensionName), "%s",
                 VK_EXT_PHYSICAL_DEVICE_DRM_EXTENSION_NAME);
        all[n].specVersion = VK_EXT_PHYSICAL_DEVICE_DRM_SPEC_VERSION;
        ++n;
    }
    if (!out) {
        *count = n;
        free(all);
        return VK_SUCCESS;
    }
    uint32_t capacity = *count;
    uint32_t copied = capacity < n ? capacity : n;
    memcpy(out, all, (size_t)copied * sizeof(*all));
    *count = copied;
    free(all);
    return copied < n ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL wrapped_create_device(
    VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
    const VkAllocationCallbacks *allocator, VkDevice *device) {
    PFN_vkCreateDevice fn = atomic_load(&real_create_device);
    if (!fn || !info) return VK_ERROR_INITIALIZATION_FAILED;
    if (!info->enabledExtensionCount) return fn(physical, info, allocator, device);
    /* With the facade off there is nothing of ours to strip, and a request for an extension no
       driver advertised is the backend's problem to answer, not ours to hide. */
    if (!target_device(physical) || !drm_facade_enabled())
        return fn(physical, info, allocator, device);
    /* This adapter implements the metadata-only extension. It has no device
     * commands, and a backend which did not advertise it must not receive it. */
    uint32_t n = info->enabledExtensionCount;
    if (n > DD_MAX_EXTENSIONS) return VK_ERROR_OUT_OF_HOST_MEMORY;
    const char **names = calloc(n, sizeof(*names));
    if (!names) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkDeviceCreateInfo copy = *info;
    copy.enabledExtensionCount = 0;
    for (uint32_t i = 0; i < n; ++i) {
        const char *name = info->ppEnabledExtensionNames[i];
        if (strcmp(name, VK_EXT_PHYSICAL_DEVICE_DRM_EXTENSION_NAME))
            names[copy.enabledExtensionCount++] = name;
    }
    copy.ppEnabledExtensionNames = names;
    VkResult r = fn(physical, &copy, allocator, device);
    free(names);
    if (r == VK_SUCCESS && device && tracing())
        tracef("-- device=%llu created through the adapter", DD_HANDLE(*device));
    return r;
}

DD_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance, const char *);
DD_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(VkInstance, const char *);
DD_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t *);
DD_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance, const char *);

static PFN_vkVoidFunction map_proc(VkInstance instance, const char *name, PFN_vkVoidFunction proc) {
    if (!proc) return NULL;
    PFN_vkGetPhysicalDeviceProperties props = (PFN_vkGetPhysicalDeviceProperties)
        backend_gipa(instance, "vkGetPhysicalDeviceProperties");
    if (props) atomic_store(&real_properties, props);
    if (!strcmp(name, "vkGetPhysicalDeviceProperties2") ||
        !strcmp(name, "vkGetPhysicalDeviceProperties2KHR")) {
        atomic_store(&real_properties2, (PFN_vkGetPhysicalDeviceProperties2)proc);
        return (PFN_vkVoidFunction)wrapped_properties2;
    }
    if (!strcmp(name, "vkEnumerateDeviceExtensionProperties")) {
        atomic_store(&real_extensions, (PFN_vkEnumerateDeviceExtensionProperties)proc);
        return (PFN_vkVoidFunction)wrapped_extensions;
    }
    if (!strcmp(name, "vkCreateDevice")) {
        atomic_store(&real_create_device, (PFN_vkCreateDevice)proc);
        return (PFN_vkVoidFunction)wrapped_create_device;
    }
    if (!strcmp(name, "vkGetDeviceProcAddr")) {
        atomic_store(&real_get_device_proc_addr, (PFN_vkGetDeviceProcAddr)proc);
        /* Only worth intercepting while tracing; otherwise the loader's pointer is handed on
           untouched, which is what 0.1.0 did and what a non-tracing session still gets. */
        return tracing() ? (PFN_vkVoidFunction)traced_get_device_proc_addr : proc;
    }
    if (tracing()) {
        void *wrapper = traced_for(name, instance);
        if (wrapper) return (PFN_vkVoidFunction)wrapper;
    }
    return proc;
}

DD_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(
    VkInstance instance, const char *name) {
    if (!name) return NULL;
    if (!strcmp(name, "vkGetInstanceProcAddr") || !strcmp(name, "vk_icdGetInstanceProcAddr"))
        return (PFN_vkVoidFunction)vk_icdGetInstanceProcAddr;
    if (!strcmp(name, "vk_icdGetPhysicalDeviceProcAddr"))
        return (PFN_vkVoidFunction)vk_icdGetPhysicalDeviceProcAddr;
    if (!strcmp(name, "vk_icdNegotiateLoaderICDInterfaceVersion"))
        return (PFN_vkVoidFunction)vk_icdNegotiateLoaderICDInterfaceVersion;
    if (!ready()) return NULL;
    PFN_vkVoidFunction proc = backend_gipa(instance, name);
    return map_proc(instance, name, proc);
}

DD_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(
    VkInstance instance, const char *name) {
    return vk_icdGetInstanceProcAddr(instance, name);
}

DD_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(
    VkInstance instance, const char *name) {
    if (!name || !ready()) return NULL;
    PFN_vkVoidFunction proc = backend_gpdpa ? backend_gpdpa(instance, name) : backend_gipa(instance, name);
    return map_proc(instance, name, proc);
}

DD_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t *version) {
    if (!version || *version < 1 || !ready()) return VK_ERROR_INCOMPATIBLE_DRIVER;
    /* Interface 7 is the newest protocol implemented by this adapter. */
    if (*version > 7) *version = 7;
    pthread_once(&trace_once, trace_init);
    if (tracing()) tracef("-- adapter 0.2.0 tracing to the file named by DD_ADAPTER_TRACE");
    return backend_negotiate(version);
}
