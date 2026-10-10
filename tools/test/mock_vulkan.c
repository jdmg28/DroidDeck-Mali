/*
 * A fake Vulkan loader, for testing droiddeck-gpu-probe on a machine with no GPU.
 *
 * It behaves like the real loader where the probe has been getting it wrong: with no instance it
 * answers NULL for anything that is not a global command, and it returns real function pointers
 * once there is one. It also fills in a device called Mali-G615 MC6, honours vkCmdFillBuffer by
 * actually writing the pattern, and lets vkWaitForFences be told what to answer, so both the
 * pass and the "fence never signalled" paths can be exercised.
 *
 * Not part of the app. Build:  gcc -shared -fPIC -o libvulkan.so.1 mock_vulkan.c
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define VK_SUCCESS 0
#define VK_TIMEOUT 2

typedef uint64_t VkInstance;
typedef uint64_t VkPhysicalDevice;
typedef uint64_t VkDevice;
typedef uint64_t VkQueue;
typedef uint64_t VkCommandBuffer;
typedef uint64_t VkBuffer;
typedef uint64_t VkDeviceMemory;
typedef uint64_t VkFence;
typedef uint64_t VkCommandPool;

typedef struct {
    uint32_t sType;
    void *pNext;
    uint32_t apiVersion, driverVersion, vendorID, deviceID, deviceType;
    char deviceName[256];
    uint8_t tail[2048];
} VkPhysicalDeviceProperties;

typedef struct { uint32_t propertyFlags; uint32_t heapIndex; } VkMemoryType;
typedef struct { uint64_t size; uint32_t flags; } VkMemoryHeap;
typedef struct {
    uint32_t memoryTypeCount;
    VkMemoryType memoryTypes[32];
    uint32_t memoryHeapCount;
    VkMemoryHeap memoryHeaps[16];
} VkPhysicalDeviceMemoryProperties;

typedef struct { uint32_t width, height, depth; } VkExtent3D;
typedef struct {
    uint32_t queueFlags, queueCount, timestampValidBits;
    VkExtent3D minImageTransferGranularity;
} VkQueueFamilyProperties;

typedef struct { uint64_t size; uint64_t alignment; uint32_t memoryTypeBits; } VkMemoryRequirements;

static void *g_mapped = NULL;
static size_t g_mapped_size = 0;

static int fence_result(void) {
    const char *want = getenv("MOCK_FENCE_RESULT");
    return want ? atoi(want) : VK_SUCCESS;
}

/* ── global commands ─────────────────────────────────────────────────────────────────────── */
static int mock_CreateInstance(const void *info, const void *alloc, VkInstance *out) {
    (void)info; (void)alloc;
    *out = (VkInstance)0x1111;
    return VK_SUCCESS;
}
static void *mock_EnumerateInstanceVersion(void) { return NULL; }
static int mock_EnumerateInstanceLayerProperties(uint32_t *c, void *p) { if (c) *c = 0; (void)p; return VK_SUCCESS; }
static int mock_EnumerateInstanceExtensionProperties(const char *n, uint32_t *c, void *p) {
    (void)n; if (c) *c = 0; (void)p; return VK_SUCCESS;
}

/* ── instance commands ───────────────────────────────────────────────────────────────────── */
static void mock_DestroyInstance(VkInstance i, const void *a) { (void)i; (void)a; }

static int mock_EnumeratePhysicalDevices(VkInstance i, uint32_t *count, VkPhysicalDevice *devs) {
    (void)i;
    if (!count) return VK_SUCCESS;
    if (!devs) { *count = 1; return VK_SUCCESS; }
    if (*count >= 1) devs[0] = (VkPhysicalDevice)0x2222;
    return VK_SUCCESS;
}

static void mock_GetPhysicalDeviceProperties(VkPhysicalDevice d, VkPhysicalDeviceProperties *p) {
    (void)d;
    memset(p, 0, sizeof(*p));
    p->apiVersion = (1u << 22) | (1u << 12);
    p->driverVersion = 0x00010000;
    p->vendorID = 0x13B5;              /* ARM */
    p->deviceID = 0x0000;
    p->deviceType = 2;                 /* VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU */
    strncpy(p->deviceName, "Mali-G615 MC6", sizeof(p->deviceName) - 1);
}

static void mock_GetPhysicalDeviceMemoryProperties(VkPhysicalDevice d, VkPhysicalDeviceMemoryProperties *m) {
    (void)d;
    memset(m, 0, sizeof(*m));
    m->memoryTypeCount = 1;
    m->memoryTypes[0].propertyFlags = 0x6;   /* HOST_VISIBLE | HOST_COHERENT */
    m->memoryTypes[0].heapIndex = 0;
    m->memoryHeapCount = 1;
    m->memoryHeaps[0].size = 1u << 30;
    m->memoryHeaps[0].flags = 1;
}

static void mock_GetPhysicalDeviceQueueFamilyProperties(VkPhysicalDevice d, uint32_t *count, VkQueueFamilyProperties *f) {
    (void)d;
    if (!f) { if (count) *count = 1; return; }
    memset(f, 0, sizeof(*f));
    f->queueFlags = 0x7;               /* GRAPHICS | COMPUTE | TRANSFER */
    f->queueCount = 1;
    f->timestampValidBits = 64;
}

static int mock_CreateDevice(VkPhysicalDevice pd, const void *info, const void *alloc, VkDevice *out) {
    (void)pd; (void)info; (void)alloc;
    *out = (VkDevice)0x3333;
    return VK_SUCCESS;
}

/* ── device commands ─────────────────────────────────────────────────────────────────────── */
static void mock_DestroyDevice(VkDevice d, const void *a) { (void)d; (void)a; }
static int mock_GetDeviceQueue(VkDevice d, uint32_t family, uint32_t index, VkQueue *q) {
    (void)d; (void)family; (void)index; *q = (VkQueue)0x4444; return VK_SUCCESS;
}
static int mock_CreateBuffer(VkDevice d, const void *info, const void *alloc, VkBuffer *b) {
    (void)d; (void)info; (void)alloc; *b = (VkBuffer)0x5555; return VK_SUCCESS;
}
static void mock_DestroyBuffer(VkDevice d, VkBuffer b, const void *a) { (void)d; (void)b; (void)a; }
static void mock_GetBufferMemoryRequirements(VkDevice d, VkBuffer b, VkMemoryRequirements *r) {
    (void)d; (void)b;
    r->size = 4096; r->alignment = 256; r->memoryTypeBits = 1;
}
static int mock_AllocateMemory(VkDevice d, const void *info, const void *alloc, VkDeviceMemory *m) {
    (void)d; (void)info; (void)alloc; *m = (VkDeviceMemory)0x6666; return VK_SUCCESS;
}
static void mock_FreeMemory(VkDevice d, VkDeviceMemory m, const void *a) { (void)d; (void)m; (void)a; }
static int mock_BindBufferMemory(VkDevice d, VkBuffer b, VkDeviceMemory m, uint64_t off) {
    (void)d; (void)b; (void)m; (void)off; return VK_SUCCESS;
}
static int mock_MapMemory(VkDevice d, VkDeviceMemory m, uint64_t off, uint64_t size, uint32_t flags, void **data) {
    (void)d; (void)m; (void)off; (void)flags;
    g_mapped = calloc(1, size ? size : 4096);
    g_mapped_size = size ? size : 4096;
    *data = g_mapped;
    return VK_SUCCESS;
}
static void mock_UnmapMemory(VkDevice d, VkDeviceMemory m) {
    (void)d; (void)m;
    free(g_mapped); g_mapped = NULL; g_mapped_size = 0;
}
static int mock_CreateCommandPool(VkDevice d, const void *info, const void *alloc, VkCommandPool *p) {
    (void)d; (void)info; (void)alloc; *p = (VkCommandPool)0x7777; return VK_SUCCESS;
}
static void mock_DestroyCommandPool(VkDevice d, VkCommandPool p, const void *a) { (void)d; (void)p; (void)a; }
static int mock_AllocateCommandBuffers(VkDevice d, const void *info, VkCommandBuffer *cb) {
    (void)d; (void)info; *cb = (VkCommandBuffer)0x8888; return VK_SUCCESS;
}
static void mock_FreeCommandBuffers(VkDevice d, VkCommandPool p, uint32_t n, const VkCommandBuffer *cb) {
    (void)d; (void)p; (void)n; (void)cb;
}
static int mock_BeginCommandBuffer(VkCommandBuffer cb, const void *info) { (void)cb; (void)info; return VK_SUCCESS; }
static int mock_EndCommandBuffer(VkCommandBuffer cb) { (void)cb; return VK_SUCCESS; }

static void mock_CmdFillBuffer(VkCommandBuffer cb, VkBuffer b, uint64_t off, uint64_t size, uint32_t data) {
    (void)cb; (void)b;
    /* The whole point of the readback: a driver that signals the fence without doing the work
       must not pass, so the pattern is only written when this is actually called. */
    if (!g_mapped) return;
    if (getenv("MOCK_SKIP_FILL")) return;
    size_t n = size < g_mapped_size ? size : g_mapped_size;
    for (size_t i = 0; i + 4 <= n; i += 4) memcpy((char *)g_mapped + i, &data, 4);
}

static int mock_CreateFence(VkDevice d, const void *info, const void *alloc, VkFence *f) {
    (void)d; (void)info; (void)alloc; *f = (VkFence)0x9999; return VK_SUCCESS;
}
static void mock_DestroyFence(VkDevice d, VkFence f, const void *a) { (void)d; (void)f; (void)a; }
static int mock_QueueSubmit(VkQueue q, uint32_t n, const void *submits, VkFence f) {
    (void)q; (void)n; (void)submits; (void)f; return VK_SUCCESS;
}
static int mock_WaitForFences(VkDevice d, uint32_t count, const VkFence *f, uint32_t waitAll, uint64_t ns) {
    (void)d; (void)count; (void)f; (void)waitAll; (void)ns;
    return fence_result();
}

/* ── dispatch ────────────────────────────────────────────────────────────────────────────── */
struct entry { const char *name; void *fn; int global; int device; };

static struct entry TABLE[] = {
    /* global */
    {"vkCreateInstance", (void *)mock_CreateInstance, 1, 0},
    {"vkEnumerateInstanceVersion", (void *)mock_EnumerateInstanceVersion, 1, 0},
    {"vkEnumerateInstanceLayerProperties", (void *)mock_EnumerateInstanceLayerProperties, 1, 0},
    {"vkEnumerateInstanceExtensionProperties", (void *)mock_EnumerateInstanceExtensionProperties, 1, 0},
    {"vkGetInstanceProcAddr", NULL, 1, 0},
    /* instance */
    {"vkDestroyInstance", (void *)mock_DestroyInstance, 0, 0},
    {"vkEnumeratePhysicalDevices", (void *)mock_EnumeratePhysicalDevices, 0, 0},
    {"vkGetPhysicalDeviceProperties", (void *)mock_GetPhysicalDeviceProperties, 0, 0},
    {"vkGetPhysicalDeviceMemoryProperties", (void *)mock_GetPhysicalDeviceMemoryProperties, 0, 0},
    {"vkGetPhysicalDeviceQueueFamilyProperties", (void *)mock_GetPhysicalDeviceQueueFamilyProperties, 0, 0},
    {"vkCreateDevice", (void *)mock_CreateDevice, 0, 0},
    {"vkGetDeviceProcAddr", NULL, 0, 0},
    /* device */
    {"vkDestroyDevice", (void *)mock_DestroyDevice, 0, 1},
    {"vkGetDeviceQueue", (void *)mock_GetDeviceQueue, 0, 1},
    {"vkCreateBuffer", (void *)mock_CreateBuffer, 0, 1},
    {"vkDestroyBuffer", (void *)mock_DestroyBuffer, 0, 1},
    {"vkGetBufferMemoryRequirements", (void *)mock_GetBufferMemoryRequirements, 0, 1},
    {"vkAllocateMemory", (void *)mock_AllocateMemory, 0, 1},
    {"vkFreeMemory", (void *)mock_FreeMemory, 0, 1},
    {"vkBindBufferMemory", (void *)mock_BindBufferMemory, 0, 1},
    {"vkMapMemory", (void *)mock_MapMemory, 0, 1},
    {"vkUnmapMemory", (void *)mock_UnmapMemory, 0, 1},
    {"vkCreateCommandPool", (void *)mock_CreateCommandPool, 0, 1},
    {"vkDestroyCommandPool", (void *)mock_DestroyCommandPool, 0, 1},
    {"vkAllocateCommandBuffers", (void *)mock_AllocateCommandBuffers, 0, 1},
    {"vkFreeCommandBuffers", (void *)mock_FreeCommandBuffers, 0, 1},
    {"vkBeginCommandBuffer", (void *)mock_BeginCommandBuffer, 0, 1},
    {"vkEndCommandBuffer", (void *)mock_EndCommandBuffer, 0, 1},
    {"vkCmdFillBuffer", (void *)mock_CmdFillBuffer, 0, 1},
    {"vkCreateFence", (void *)mock_CreateFence, 0, 1},
    {"vkDestroyFence", (void *)mock_DestroyFence, 0, 1},
    {"vkQueueSubmit", (void *)mock_QueueSubmit, 0, 1},
    {"vkWaitForFences", (void *)mock_WaitForFences, 0, 1},
};

void *vkGetDeviceProcAddr(VkDevice device, const char *name) {
    (void)device;
    if (!name) return NULL;
    for (size_t i = 0; i < sizeof(TABLE) / sizeof(TABLE[0]); i++)
        if (TABLE[i].device && strcmp(TABLE[i].name, name) == 0) return TABLE[i].fn;
    return NULL;
}

void *vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!name) return NULL;
    if (strcmp(name, "vkGetInstanceProcAddr") == 0) return (void *)vkGetInstanceProcAddr;
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) return (void *)vkGetDeviceProcAddr;
    for (size_t i = 0; i < sizeof(TABLE) / sizeof(TABLE[0]); i++) {
        if (strcmp(TABLE[i].name, name) != 0) continue;
        /* The behaviour the probe got wrong: without an instance only the global commands
           exist, and everything else answers NULL. */
        if (instance == 0 && !TABLE[i].global) return NULL;
        return TABLE[i].fn;
    }
    return NULL;
}
