// Library state, errors, timer, the single "monitor" (the phone display) and
// the Vulkan entry points.
#define VK_USE_PLATFORM_ANDROID_KHR
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <android/log.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "internal.h"

struct Glfw glfw;
struct Android android = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
    .cond = PTHREAD_COND_INITIALIZER,
    .display_w = 2400, .display_h = 1080, .refresh = 60, .xdpi = 395, .ydpi = 395,
    .cursor_mode = GLFW_CURSOR_NORMAL,
};
__thread Window *glfw_current;

static __thread int last_error;
static __thread char last_desc[512];

// stderr is the launch log the launcher UI shows; logcat catches what happens
// before the JVM redirects it
void glfw_log(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "GhostCraft-GLFW", "%s", buf);
    fprintf(stderr, "[GLFW] %s\n", buf);
}

void glfw_error(int code, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(last_desc, sizeof(last_desc), fmt, ap);
    va_end(ap);
    last_error = code;
    glfw_log("error 0x%x: %s", code, last_desc);
    if (glfw.error_cb) glfw.error_cb(code, last_desc);
}

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000000000u + ts.tv_nsec;
}

void glfw_update_monitor(void) {
    Monitor *m = &glfw.monitor;
    pthread_mutex_lock(&android.lock);
    m->mode = (GLFWvidmode) {android.display_w, android.display_h, 8, 8, 8, android.refresh};
    m->width_mm = (int) (android.display_w / android.xdpi * 25.4f);
    m->height_mm = (int) (android.display_h / android.ydpi * 25.4f);
    pthread_mutex_unlock(&android.lock);
}

GLFWAPI int glfwInit(void) {
    if (glfw.initialized) return GLFW_TRUE;
    if (!glfw_load_egl()) return GLFW_FALSE;
    glfwDefaultWindowHints();
    strcpy(glfw.monitor.name, "Android display");
    glfw.monitors[0] = &glfw.monitor;
    glfw_update_monitor();
    glfw.time_base = now_ns();
    glfw.initialized = 1;
    glfw_log("initialized, state %p", (void *) &android);
    return GLFW_TRUE;
}

GLFWAPI void glfwTerminate(void) {
    if (!glfw.initialized) return;
    while (glfw.windows) glfwDestroyWindow(glfw.windows);
    // The EGL display is shared with the app's own UI renderer: never terminate it
    glfw.initialized = 0;
}

// Platform choice and the rest have nothing to pick from here
GLFWAPI void glfwInitHint(int hint, int value) { (void) hint; (void) value; }
GLFWAPI void glfwInitAllocator(const GLFWallocator *allocator) { (void) allocator; }

GLFWAPI void glfwGetVersion(int *major, int *minor, int *rev) {
    if (major) *major = 3;
    if (minor) *minor = 4;
    if (rev) *rev = 0;
}

GLFWAPI const char *glfwGetVersionString(void) { return "3.4.0 GhostCraft Android EGL"; }

GLFWAPI int glfwGetError(const char **description) {
    int code = last_error;
    if (description) *description = code ? last_desc : NULL;
    last_error = 0;
    return code;
}

GLFWAPI GLFWerrorfun glfwSetErrorCallback(GLFWerrorfun cb) {
    GLFWerrorfun prev = glfw.error_cb;
    glfw.error_cb = cb;
    return prev;
}

GLFWAPI int glfwGetPlatform(void) { REQUIRE_INIT(0); return GLFW_PLATFORM_NULL; }
GLFWAPI int glfwPlatformSupported(int platform) {
    return platform == GLFW_PLATFORM_NULL || platform == GLFW_ANY_PLATFORM;
}

// --- timer -------------------------------------------------------------------

GLFWAPI double glfwGetTime(void) {
    REQUIRE_INIT(0);
    return (double) (now_ns() - glfw.time_base) / 1e9;
}

GLFWAPI void glfwSetTime(double t) {
    REQUIRE_INIT();
    if (t != t || t < 0 || t > 18446744073.0) {
        glfw_error(GLFW_INVALID_VALUE, "invalid time %f", t);
        return;
    }
    glfw.time_base = now_ns() - (uint64_t) (t * 1e9);
}

GLFWAPI uint64_t glfwGetTimerValue(void) { return now_ns(); }
GLFWAPI uint64_t glfwGetTimerFrequency(void) { return 1000000000u; }

// --- monitor -----------------------------------------------------------------

GLFWAPI GLFWmonitor **glfwGetMonitors(int *count) {
    if (count) *count = 0;
    REQUIRE_INIT(NULL);
    if (count) *count = 1;
    return glfw.monitors;
}

GLFWAPI GLFWmonitor *glfwGetPrimaryMonitor(void) { REQUIRE_INIT(NULL); return &glfw.monitor; }

GLFWAPI void glfwGetMonitorPos(GLFWmonitor *m, int *x, int *y) {
    (void) m;
    if (x) *x = 0;
    if (y) *y = 0;
}

GLFWAPI void glfwGetMonitorWorkarea(GLFWmonitor *m, int *x, int *y, int *w, int *h) {
    if (x) *x = 0;
    if (y) *y = 0;
    if (w) *w = m->mode.width;
    if (h) *h = m->mode.height;
}

GLFWAPI void glfwGetMonitorPhysicalSize(GLFWmonitor *m, int *w, int *h) {
    if (w) *w = m->width_mm;
    if (h) *h = m->height_mm;
}

GLFWAPI void glfwGetMonitorContentScale(GLFWmonitor *m, float *x, float *y) {
    (void) m;
    if (x) *x = 1;
    if (y) *y = 1;
}

GLFWAPI const char *glfwGetMonitorName(GLFWmonitor *m) { return m->name; }
GLFWAPI void glfwSetMonitorUserPointer(GLFWmonitor *m, void *p) { m->user = p; }
GLFWAPI void *glfwGetMonitorUserPointer(GLFWmonitor *m) { return m->user; }

GLFWAPI GLFWmonitorfun glfwSetMonitorCallback(GLFWmonitorfun cb) {
    GLFWmonitorfun prev = glfw.monitor_cb;
    glfw.monitor_cb = cb;
    return prev;
}

GLFWAPI const GLFWvidmode *glfwGetVideoModes(GLFWmonitor *m, int *count) {
    glfw_update_monitor();
    if (count) *count = 1;
    return &m->mode;
}

GLFWAPI const GLFWvidmode *glfwGetVideoMode(GLFWmonitor *m) {
    glfw_update_monitor();
    return &m->mode;
}

GLFWAPI void glfwSetGamma(GLFWmonitor *m, float gamma) {
    (void) m; (void) gamma;
    glfw_error(GLFW_FEATURE_UNAVAILABLE, "gamma ramps are not available on Android");
}

GLFWAPI const GLFWgammaramp *glfwGetGammaRamp(GLFWmonitor *m) {
    (void) m;
    glfw_error(GLFW_FEATURE_UNAVAILABLE, "gamma ramps are not available on Android");
    return NULL;
}

GLFWAPI void glfwSetGammaRamp(GLFWmonitor *m, const GLFWgammaramp *r) {
    (void) r;
    glfwSetGamma(m, 1);
}

// --- Vulkan ------------------------------------------------------------------
// Android's own loader; a window made with GLFW_NO_API hands its surface to it

static void *vulkan_lib;
static PFN_vkGetInstanceProcAddr vk_gipa;

GLFWAPI void glfwInitVulkanLoader(PFN_vkGetInstanceProcAddr loader) { vk_gipa = loader; }

static int load_vulkan(void) {
    if (vk_gipa) return 1;
    if (!vulkan_lib) vulkan_lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vulkan_lib) vk_gipa = (PFN_vkGetInstanceProcAddr) dlsym(vulkan_lib, "vkGetInstanceProcAddr");
    return vk_gipa != NULL;
}

GLFWAPI int glfwVulkanSupported(void) { REQUIRE_INIT(0); return load_vulkan(); }

GLFWAPI const char **glfwGetRequiredInstanceExtensions(uint32_t *count) {
    static const char *exts[] = {"VK_KHR_surface", "VK_KHR_android_surface"};
    *count = 0;
    REQUIRE_INIT(NULL);
    if (!load_vulkan()) {
        glfw_error(GLFW_API_UNAVAILABLE, "Vulkan loader not found");
        return NULL;
    }
    *count = 2;
    return exts;
}

GLFWAPI PFN_vkVoidFunction glfwGetInstanceProcAddress(VkInstance instance, const char *name) {
    REQUIRE_INIT(NULL);
    if (!load_vulkan()) return NULL;
    PFN_vkVoidFunction f = vk_gipa(instance, name);
    if (!f && vulkan_lib) f = (PFN_vkVoidFunction) dlsym(vulkan_lib, name);
    return f;
}

GLFWAPI int glfwGetPhysicalDevicePresentationSupport(VkInstance i, VkPhysicalDevice d, uint32_t family) {
    (void) i; (void) d; (void) family;
    // Every Android queue family that can draw can also present
    return GLFW_TRUE;
}

GLFWAPI VkResult glfwCreateWindowSurface(VkInstance instance, GLFWwindow *w,
                                         const VkAllocationCallbacks *alloc, VkSurfaceKHR *surface) {
    *surface = VK_NULL_HANDLE;
    REQUIRE_INIT(VK_ERROR_INITIALIZATION_FAILED);
    if (w->ctx) {
        glfw_error(GLFW_INVALID_VALUE, "window has an OpenGL context");
        return VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
    }
    PFN_vkCreateAndroidSurfaceKHR create = load_vulkan()
        ? (PFN_vkCreateAndroidSurfaceKHR) vk_gipa(instance, "vkCreateAndroidSurfaceKHR") : NULL;
    if (!create) {
        glfw_error(GLFW_API_UNAVAILABLE, "VK_KHR_android_surface is missing");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    pthread_mutex_lock(&android.lock);
    ANativeWindow *native = android.window;
    if (native) ANativeWindow_acquire(native);
    pthread_mutex_unlock(&android.lock);
    if (!native) {
        glfw_error(GLFW_PLATFORM_ERROR, "no Android surface");
        return VK_ERROR_SURFACE_LOST_KHR;
    }
    VkAndroidSurfaceCreateInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = native,
    };
    VkResult r = create(instance, &info, alloc, surface);
    ANativeWindow_release(native);
    return r;
}
