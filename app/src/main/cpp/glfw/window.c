// Windows and their EGL contexts.
//
// The EGL library is chosen at run time: the system one gives only OpenGL ES,
// Mesa's (GHOSTCRAFT_EGL, stage 2 step 6) gives desktop OpenGL through Zink.
// A context that asks for desktop GL falls back to GLES when the library
// can't do it, so the game at least gets far enough to say why.
#include <dirent.h>
#include <dlfcn.h>
#include <stdio.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "internal.h"

#define GL_EXTENSIONS 0x1F03
#define GL_NUM_EXTENSIONS 0x821D

// Our Mesa lives in the app's files, outside the linker's search path, so
// libEGL_mesa's DT_NEEDED (libgallium...) wouldn't be found by name. Load the
// whole folder by path first; the linker then matches them by soname. Passes
// repeat until nothing new loads, which settles the order between them.
static void preload_dir(const char *lib) {
    char dir[512];
    snprintf(dir, sizeof(dir), "%s", lib);
    char *slash = strrchr(dir, '/');
    if (!slash) return;
    *slash = 0;
    for (int progress = 1; progress;) {
        progress = 0;
        DIR *d = opendir(dir);
        if (!d) return;
        struct dirent *e;
        while ((e = readdir(d))) {
            size_t n = strlen(e->d_name);
            // Vulkan drivers are loaded by whoever uses them, not by EGL
            if (n < 4 || strcmp(e->d_name + n - 3, ".so") || !strncmp(e->d_name, "libvulkan_", 10)) continue;
            char path[1024];
            snprintf(path, sizeof(path), "%s/%s", dir, e->d_name);
            if (dlopen(path, RTLD_NOW | RTLD_NOLOAD)) continue;
            if (dlopen(path, RTLD_NOW | RTLD_GLOBAL)) progress = 1;
        }
        closedir(d);
    }
}

int glfw_load_egl(void) {
    if (glfw.egl_lib) return 1;
    const char *egl = getenv("GHOSTCRAFT_EGL");
    const char *gl = getenv("GHOSTCRAFT_GL");
    if (!egl || !*egl) egl = "libEGL.so";
    if (!gl || !*gl) gl = "libGLESv3.so";
    if (egl[0] == '/') preload_dir(egl);
    void *lib = dlopen(egl, RTLD_NOW | RTLD_GLOBAL);
    if (!lib) {
        glfw_error(GLFW_API_UNAVAILABLE, "cannot load %s: %s", egl, dlerror());
        return 0;
    }
#define LOAD(name)                                                       \
    if (!(glfw.name = (void *) dlsym(lib, "egl" #name))) {               \
        glfw_error(GLFW_API_UNAVAILABLE, "%s has no egl" #name, egl);   \
        return 0;                                                        \
    }
    LOAD(GetDisplay) LOAD(Initialize) LOAD(BindAPI) LOAD(ChooseConfig) LOAD(GetConfigAttrib)
    LOAD(CreateContext) LOAD(DestroyContext) LOAD(CreateWindowSurface) LOAD(CreatePbufferSurface)
    LOAD(DestroySurface) LOAD(MakeCurrent) LOAD(SwapBuffers) LOAD(SwapInterval)
    LOAD(GetProcAddress) LOAD(GetError) LOAD(QueryString)
#undef LOAD
    glfw.dpy = glfw.GetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major, minor;
    if (glfw.dpy == EGL_NO_DISPLAY || !glfw.Initialize(glfw.dpy, &major, &minor)) {
        glfw_error(GLFW_API_UNAVAILABLE, "eglInitialize failed: 0x%x", glfw.GetError());
        return 0;
    }
    glfw.egl_lib = lib;
    glfw.gl_lib = dlopen(gl, RTLD_NOW | RTLD_GLOBAL);
    const char *present = getenv("GHOSTCRAFT_PRESENT");
    glfw.present_copy = present && !strcmp(present, "copy");
    glfw_log("EGL %d.%d from %s: %s, APIs: %s", major, minor, egl,
             glfw.QueryString(glfw.dpy, EGL_VENDOR), glfw.QueryString(glfw.dpy, EGL_CLIENT_APIS));
    return 1;
}

// --- context creation ------------------------------------------------------

static int choose_config(Window *w, EGLint renderable, EGLConfig *out) {
    const Hints *h = &w->hints;
#define BITS(v) ((v) == GLFW_DONT_CARE ? EGL_DONT_CARE : (v))
    EGLint attrs[] = {
        EGL_RENDERABLE_TYPE, renderable,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, BITS(h->red), EGL_GREEN_SIZE, BITS(h->green), EGL_BLUE_SIZE, BITS(h->blue),
        EGL_ALPHA_SIZE, BITS(h->alpha), EGL_DEPTH_SIZE, BITS(h->depth), EGL_STENCIL_SIZE, BITS(h->stencil),
        EGL_SAMPLE_BUFFERS, h->samples > 0, EGL_SAMPLES, h->samples > 0 ? h->samples : 0,
        EGL_NONE,
    };
#undef BITS
    EGLint n = 0;
    // copy mode draws into a pbuffer only
    if (glfw.present_copy) attrs[3] = EGL_PBUFFER_BIT;
    if (glfw.ChooseConfig(glfw.dpy, attrs, out, 1, &n) && n > 0) return 1;
    // Some drivers have no pbuffer-capable configs; surfaceless works there
    attrs[3] = EGL_WINDOW_BIT;
    if (glfw.ChooseConfig(glfw.dpy, attrs, out, 1, &n) && n > 0) return 1;
    glfw.ChooseConfig(glfw.dpy, (EGLint[]) {EGL_NONE}, NULL, 0, &n);
    glfw_log("no EGL config for renderable 0x%x among %d configs", renderable, n);
    return 0;
}

static EGLContext try_context(Window *w, EGLenum api, int major, int minor, EGLContext share) {
    const Hints *h = &w->hints;
    if (!glfw.BindAPI(api)) return EGL_NO_CONTEXT;
    EGLint renderable = api == EGL_OPENGL_API ? EGL_OPENGL_BIT
                      : major >= 3 ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_ES2_BIT;
    if (!choose_config(w, renderable, &w->config)) return EGL_NO_CONTEXT;

    EGLint attrs[16], n = 0;
    attrs[n++] = EGL_CONTEXT_MAJOR_VERSION;
    attrs[n++] = major;
    attrs[n++] = EGL_CONTEXT_MINOR_VERSION;
    attrs[n++] = minor;
    if (api == EGL_OPENGL_API) {
        if (h->profile) {
            attrs[n++] = EGL_CONTEXT_OPENGL_PROFILE_MASK;
            attrs[n++] = h->profile == GLFW_OPENGL_CORE_PROFILE ? EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT
                                                                : EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT;
        }
    }
    // EGL_KHR_create_context flags: the EGL 1.5 attributes for the same bits
    // are rejected by 1.4 displays (Mesa here)
    EGLint flags = (h->debug ? EGL_CONTEXT_OPENGL_DEBUG_BIT_KHR : 0) |
                   (api == EGL_OPENGL_API && h->forward ? EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR : 0);
    if (flags) {
        attrs[n++] = EGL_CONTEXT_FLAGS_KHR;
        attrs[n++] = flags;
    }
    attrs[n++] = EGL_NONE;
    EGLContext ctx = glfw.CreateContext(glfw.dpy, w->config, share, attrs);
    if (ctx != EGL_NO_CONTEXT) {
        w->api = api;
        w->ctx_major = major;
        w->ctx_minor = minor;
    }
    return ctx;
}

static int create_context(Window *w, Window *share) {
    const Hints *h = &w->hints;
    EGLContext sh = share ? share->ctx : EGL_NO_CONTEXT;
    if (h->client_api == GLFW_OPENGL_API) {
        w->ctx = try_context(w, EGL_OPENGL_API, h->major, h->minor, sh);
        if (w->ctx) return 1;
        glfw_log("no desktop OpenGL %d.%d in this EGL (0x%x), falling back to OpenGL ES",
                 h->major, h->minor, glfw.GetError());
        w->ctx = try_context(w, EGL_OPENGL_ES_API, 3, 2, sh);
        if (!w->ctx) w->ctx = try_context(w, EGL_OPENGL_ES_API, 3, 0, sh);
    } else {
        w->ctx = try_context(w, EGL_OPENGL_ES_API, h->major, h->minor, sh);
    }
    if (!w->ctx) {
        glfw_error(GLFW_VERSION_UNAVAILABLE, "eglCreateContext failed: 0x%x", glfw.GetError());
        return 0;
    }
    EGLint pb[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    w->pbuf = glfw.CreatePbufferSurface(glfw.dpy, w->config, pb);
    glfw_log("context: %s %d.%d", w->api == EGL_OPENGL_API ? "OpenGL" : "OpenGL ES", w->ctx_major, w->ctx_minor);
    return 1;
}

static EGLSurface draw_surface(Window *w) { return w->surf ? w->surf : w->pbuf; }

// --- the Android surface ---------------------------------------------------

// Called on the thread that has (or is about to get) w's context: brings w's
// EGL surface in line with the Surface the UI thread published.
void glfw_sync_surface(Window *w) {
    if (!w->primary || !w->ctx) return;
    pthread_mutex_lock(&android.lock);
    if (w->surface_gen == android.surface_gen) {
        pthread_mutex_unlock(&android.lock);
        return;
    }
    w->surface_gen = android.surface_gen;
    if (w->surf) {
        if (glfw_current == w) glfw.MakeCurrent(glfw.dpy, w->pbuf, w->pbuf, w->ctx);
        glfw.DestroySurface(glfw.dpy, w->surf);
        w->surf = EGL_NO_SURFACE;
    }
    if (w->native) {
        ANativeWindow_release(w->native);
        w->native = NULL;
    }
    if (android.window && glfw.present_copy) {
        // GL reads rows bottom-up; the compositor flips them back for free
        ANativeWindow_setBuffersGeometry(android.window, android.width, android.height, WINDOW_FORMAT_RGBA_8888);
        ANativeWindow_setBuffersTransform(android.window, ANATIVEWINDOW_TRANSFORM_MIRROR_VERTICAL);
        EGLint attrs[] = {EGL_WIDTH, android.width, EGL_HEIGHT, android.height, EGL_NONE};
        w->surf = glfw.CreatePbufferSurface(glfw.dpy, w->config, attrs);
        w->surf_w = android.width;
        w->surf_h = android.height;
        if (w->surf) {
            w->native = android.window;
            ANativeWindow_acquire(w->native);
        } else {
            glfw_log("eglCreatePbufferSurface %dx%d failed: 0x%x", android.width, android.height, glfw.GetError());
        }
    } else if (android.window) {
        EGLint format;
        glfw.GetConfigAttrib(glfw.dpy, w->config, EGL_NATIVE_VISUAL_ID, &format);
        ANativeWindow_setBuffersGeometry(android.window, 0, 0, format);
        w->surf = glfw.CreateWindowSurface(glfw.dpy, w->config, android.window, NULL);
        if (w->surf) {
            w->native = android.window;
            ANativeWindow_acquire(w->native);
        } else {
            glfw_log("eglCreateWindowSurface failed: 0x%x", glfw.GetError());
        }
    }
    android.surface_in_use = w->native != NULL;
    pthread_cond_broadcast(&android.cond);
    pthread_mutex_unlock(&android.lock);
    if (glfw_current == w) {
        EGLSurface s = draw_surface(w);
        glfw.MakeCurrent(glfw.dpy, s, s, w->ctx);
    }
}

// --- windows -----------------------------------------------------------------

GLFWAPI void glfwDefaultWindowHints(void) {
    Hints h = {
        .client_api = GLFW_OPENGL_API, .creation_api = GLFW_NATIVE_CONTEXT_API, .major = 1, .minor = 0,
        .red = 8, .green = 8, .blue = 8, .alpha = 8, .depth = 24, .stencil = 8,
        .doublebuffer = 1, .refresh = GLFW_DONT_CARE,
        .visible = 1, .focused = 1, .resizable = 1, .decorated = 1, .auto_iconify = 1, .focus_on_show = 1,
    };
    glfw.hints = h;
}

GLFWAPI void glfwWindowHint(int hint, int value) {
    Hints *h = &glfw.hints;
    switch (hint) {
        case GLFW_RED_BITS: h->red = value; break;
        case GLFW_GREEN_BITS: h->green = value; break;
        case GLFW_BLUE_BITS: h->blue = value; break;
        case GLFW_ALPHA_BITS: h->alpha = value; break;
        case GLFW_DEPTH_BITS: h->depth = value; break;
        case GLFW_STENCIL_BITS: h->stencil = value; break;
        case GLFW_SAMPLES: h->samples = value; break;
        case GLFW_SRGB_CAPABLE: h->srgb = value; break;
        case GLFW_REFRESH_RATE: h->refresh = value; break;
        case GLFW_DOUBLEBUFFER: h->doublebuffer = value; break;
        case GLFW_CLIENT_API: h->client_api = value; break;
        case GLFW_CONTEXT_CREATION_API: h->creation_api = value; break;
        case GLFW_CONTEXT_VERSION_MAJOR: h->major = value; break;
        case GLFW_CONTEXT_VERSION_MINOR: h->minor = value; break;
        case GLFW_OPENGL_FORWARD_COMPAT: h->forward = value; break;
        case GLFW_CONTEXT_DEBUG: h->debug = value; break;
        case GLFW_OPENGL_PROFILE: h->profile = value; break;
        case GLFW_CONTEXT_ROBUSTNESS: h->robustness = value; break;
        case GLFW_CONTEXT_NO_ERROR: h->no_error = value; break;
        case GLFW_VISIBLE: h->visible = value; break;
        case GLFW_FOCUSED: h->focused = value; break;
        case GLFW_RESIZABLE: h->resizable = value; break;
        case GLFW_DECORATED: h->decorated = value; break;
        case GLFW_FLOATING: h->floating = value; break;
        case GLFW_MAXIMIZED: h->maximized = value; break;
        case GLFW_AUTO_ICONIFY: h->auto_iconify = value; break;
        case GLFW_FOCUS_ON_SHOW: h->focus_on_show = value; break;
        default: break;  // desktop-only hints (X11 class, Cocoa frame name...) have no meaning here
    }
}

GLFWAPI void glfwWindowHintString(int hint, const char *value) { (void) hint; (void) value; }

// The first window waits for the SurfaceView: until then its size is unknown
static int wait_for_surface(int seconds) {
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += seconds;
    pthread_mutex_lock(&android.lock);
    while (!android.window)
        if (pthread_cond_timedwait(&android.cond, &android.lock, &until) == ETIMEDOUT) break;
    int ok = android.window != NULL;
    pthread_mutex_unlock(&android.lock);
    return ok;
}

GLFWAPI GLFWwindow *glfwCreateWindow(int width, int height, const char *title, GLFWmonitor *monitor,
                                     GLFWwindow *share) {
    REQUIRE_INIT(NULL);
    if (width <= 0 || height <= 0) {
        glfw_error(GLFW_INVALID_VALUE, "invalid window size %dx%d", width, height);
        return NULL;
    }
    Window *w = calloc(1, sizeof(Window));
    w->hints = glfw.hints;
    w->monitor = monitor;
    w->visible = w->hints.visible;
    w->focused = w->hints.focused;
    w->cursor_mode = GLFW_CURSOR_NORMAL;
    w->width = width;
    w->height = height;
    w->primary = 1;
    for (Window *o = glfw.windows; o; o = o->next)
        if (o->primary) w->primary = 0;

    if (w->primary) {
        if (!wait_for_surface(15)) glfw_log("no surface yet, the window starts off-screen");
        pthread_mutex_lock(&android.lock);
        if (android.window) {
            w->width = android.width;
            w->height = android.height;
        }
        android.window_ready = 1;
        android.cursor_mode = GLFW_CURSOR_NORMAL;
        pthread_mutex_unlock(&android.lock);
        // force glfw_sync_surface to pick up whatever is there now
        w->surface_gen = -1;
    }
    if (w->hints.client_api != GLFW_NO_API && !create_context(w, share)) {
        free(w);
        return NULL;
    }
    w->announce_size = w->width != width || w->height != height;
    w->cx = w->width / 2.0;
    w->cy = w->height / 2.0;
    w->next = glfw.windows;
    glfw.windows = w;
    glfw_log("window \"%s\" %dx%d%s", title ? title : "", w->width, w->height, w->primary ? " on the Android surface" : "");
    return w;
}

GLFWAPI void glfwDestroyWindow(GLFWwindow *w) {
    if (!w) return;
    if (glfw_current == w) {
        glfw.MakeCurrent(glfw.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        glfw_current = NULL;
    }
    if (w->surf) glfw.DestroySurface(glfw.dpy, w->surf);
    if (w->pbuf) glfw.DestroySurface(glfw.dpy, w->pbuf);
    if (w->ctx) glfw.DestroyContext(glfw.dpy, w->ctx);
    if (w->native) ANativeWindow_release(w->native);
    if (w->primary) {
        pthread_mutex_lock(&android.lock);
        android.surface_in_use = 0;
        android.window_ready = 0;
        pthread_cond_broadcast(&android.cond);
        pthread_mutex_unlock(&android.lock);
    }
    for (Window **p = &glfw.windows; *p; p = &(*p)->next)
        if (*p == w) {
            *p = w->next;
            break;
        }
    free(w);
}

GLFWAPI int glfwWindowShouldClose(GLFWwindow *w) { return w->should_close; }
GLFWAPI void glfwSetWindowShouldClose(GLFWwindow *w, int value) { w->should_close = value; }
GLFWAPI void glfwSetWindowTitle(GLFWwindow *w, const char *title) { (void) w; (void) title; }
GLFWAPI void glfwSetWindowIcon(GLFWwindow *w, int count, const GLFWimage *images) {
    (void) w; (void) count; (void) images;
}

GLFWAPI void glfwGetWindowPos(GLFWwindow *w, int *x, int *y) {
    (void) w;
    if (x) *x = 0;
    if (y) *y = 0;
}

GLFWAPI void glfwSetWindowPos(GLFWwindow *w, int x, int y) { (void) w; (void) x; (void) y; }

GLFWAPI void glfwGetWindowSize(GLFWwindow *w, int *width, int *height) {
    if (width) *width = w->width;
    if (height) *height = w->height;
}

GLFWAPI void glfwGetFramebufferSize(GLFWwindow *w, int *width, int *height) {
    glfwGetWindowSize(w, width, height);
}

// The surface is always the whole screen: size requests have nothing to resize
GLFWAPI void glfwSetWindowSizeLimits(GLFWwindow *w, int a, int b, int c, int d) {
    (void) w; (void) a; (void) b; (void) c; (void) d;
}
GLFWAPI void glfwSetWindowAspectRatio(GLFWwindow *w, int n, int d) { (void) w; (void) n; (void) d; }
GLFWAPI void glfwSetWindowSize(GLFWwindow *w, int width, int height) {
    if (!w->primary) {
        w->width = width;
        w->height = height;
    }
}

GLFWAPI void glfwGetWindowFrameSize(GLFWwindow *w, int *l, int *t, int *r, int *b) {
    (void) w;
    if (l) *l = 0;
    if (t) *t = 0;
    if (r) *r = 0;
    if (b) *b = 0;
}

GLFWAPI void glfwGetWindowContentScale(GLFWwindow *w, float *x, float *y) {
    (void) w;
    if (x) *x = 1;
    if (y) *y = 1;
}

GLFWAPI float glfwGetWindowOpacity(GLFWwindow *w) { (void) w; return 1; }
GLFWAPI void glfwSetWindowOpacity(GLFWwindow *w, float o) { (void) w; (void) o; }
GLFWAPI void glfwIconifyWindow(GLFWwindow *w) { (void) w; }
GLFWAPI void glfwRestoreWindow(GLFWwindow *w) { (void) w; }
GLFWAPI void glfwMaximizeWindow(GLFWwindow *w) { (void) w; }
GLFWAPI void glfwShowWindow(GLFWwindow *w) { w->visible = 1; }
GLFWAPI void glfwHideWindow(GLFWwindow *w) { w->visible = 0; }
GLFWAPI void glfwFocusWindow(GLFWwindow *w) { (void) w; }
GLFWAPI void glfwRequestWindowAttention(GLFWwindow *w) { (void) w; }
GLFWAPI GLFWmonitor *glfwGetWindowMonitor(GLFWwindow *w) { return w->monitor; }

GLFWAPI void glfwSetWindowMonitor(GLFWwindow *w, GLFWmonitor *m, int x, int y, int width, int height, int rate) {
    (void) x; (void) y; (void) width; (void) height; (void) rate;
    w->monitor = m;
}

GLFWAPI int glfwGetWindowAttrib(GLFWwindow *w, int attrib) {
    switch (attrib) {
        case GLFW_FOCUSED: return w->focused;
        case GLFW_ICONIFIED: return w->iconified;
        case GLFW_VISIBLE: return w->visible;
        case GLFW_HOVERED: return w->focused;
        case GLFW_MAXIMIZED: return 1;
        case GLFW_RESIZABLE: return w->hints.resizable;
        case GLFW_DECORATED: return w->hints.decorated;
        case GLFW_FLOATING: return w->hints.floating;
        case GLFW_AUTO_ICONIFY: return w->hints.auto_iconify;
        case GLFW_FOCUS_ON_SHOW: return w->hints.focus_on_show;
        case GLFW_TRANSPARENT_FRAMEBUFFER: return 0;
        case GLFW_DOUBLEBUFFER: return 1;
        case GLFW_CLIENT_API:
            return !w->ctx ? GLFW_NO_API : w->api == EGL_OPENGL_API ? GLFW_OPENGL_API : GLFW_OPENGL_ES_API;
        case GLFW_CONTEXT_CREATION_API: return GLFW_EGL_CONTEXT_API;
        case GLFW_CONTEXT_VERSION_MAJOR: return w->ctx_major;
        case GLFW_CONTEXT_VERSION_MINOR: return w->ctx_minor;
        case GLFW_CONTEXT_REVISION: return 0;
        case GLFW_OPENGL_FORWARD_COMPAT: return w->api == EGL_OPENGL_API && w->hints.forward;
        case GLFW_CONTEXT_DEBUG: return w->hints.debug;
        case GLFW_OPENGL_PROFILE: return w->api == EGL_OPENGL_API ? w->hints.profile : GLFW_OPENGL_ANY_PROFILE;
        case GLFW_CONTEXT_ROBUSTNESS: return 0;
        case GLFW_CONTEXT_NO_ERROR: return 0;
        default:
            glfw_error(GLFW_INVALID_ENUM, "invalid window attribute 0x%x", attrib);
            return 0;
    }
}

GLFWAPI void glfwSetWindowAttrib(GLFWwindow *w, int attrib, int value) {
    switch (attrib) {
        case GLFW_RESIZABLE: w->hints.resizable = value; break;
        case GLFW_DECORATED: w->hints.decorated = value; break;
        case GLFW_FLOATING: w->hints.floating = value; break;
        case GLFW_AUTO_ICONIFY: w->hints.auto_iconify = value; break;
        case GLFW_FOCUS_ON_SHOW: w->hints.focus_on_show = value; break;
        default: break;
    }
}

GLFWAPI void glfwSetWindowUserPointer(GLFWwindow *w, void *p) { w->user = p; }
GLFWAPI void *glfwGetWindowUserPointer(GLFWwindow *w) { return w->user; }

#define SETTER(name, type, field)                           \
    GLFWAPI type name(GLFWwindow *w, type cb) {             \
        type prev = w->cb.field;                            \
        w->cb.field = cb;                                   \
        return prev;                                        \
    }
SETTER(glfwSetWindowPosCallback, GLFWwindowposfun, pos)
SETTER(glfwSetWindowSizeCallback, GLFWwindowsizefun, size)
SETTER(glfwSetWindowCloseCallback, GLFWwindowclosefun, close)
SETTER(glfwSetWindowRefreshCallback, GLFWwindowrefreshfun, refresh)
SETTER(glfwSetWindowFocusCallback, GLFWwindowfocusfun, focus)
SETTER(glfwSetWindowIconifyCallback, GLFWwindowiconifyfun, iconify)
SETTER(glfwSetWindowMaximizeCallback, GLFWwindowmaximizefun, maximize)
SETTER(glfwSetFramebufferSizeCallback, GLFWframebuffersizefun, fbsize)
SETTER(glfwSetWindowContentScaleCallback, GLFWwindowcontentscalefun, scale)
SETTER(glfwSetKeyCallback, GLFWkeyfun, key)
SETTER(glfwSetCharCallback, GLFWcharfun, chr)
SETTER(glfwSetCharModsCallback, GLFWcharmodsfun, charmods)
SETTER(glfwSetMouseButtonCallback, GLFWmousebuttonfun, button)
SETTER(glfwSetCursorPosCallback, GLFWcursorposfun, cursor)
SETTER(glfwSetCursorEnterCallback, GLFWcursorenterfun, enter)
SETTER(glfwSetScrollCallback, GLFWscrollfun, scroll)
SETTER(glfwSetDropCallback, GLFWdropfun, drop)
#undef SETTER

// --- context -----------------------------------------------------------------

GLFWAPI void glfwMakeContextCurrent(GLFWwindow *w) {
    REQUIRE_INIT();
    if (!w) {
        if (glfw_current) glfw.MakeCurrent(glfw.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        glfw_current = NULL;
        return;
    }
    if (!w->ctx) {
        glfw_error(GLFW_NO_WINDOW_CONTEXT, "window has no OpenGL context");
        return;
    }
    // the client API is per thread in EGL, and a context may move between threads
    glfw.BindAPI(w->api);
    glfw_current = w;
    EGLSurface s = draw_surface(w);
    if (!glfw.MakeCurrent(glfw.dpy, s, s, w->ctx))
        glfw_error(GLFW_PLATFORM_ERROR, "eglMakeCurrent failed: 0x%x", glfw.GetError());
    glfw_sync_surface(w);
}

GLFWAPI GLFWwindow *glfwGetCurrentContext(void) { return glfw_current; }

// Copy mode: the frame goes from the pbuffer straight into the window's
// locked buffer. The pack state is the game's, so it's put back afterwards.
#define GL_PACK_ROW_LENGTH 0x0D02
#define GL_PACK_ALIGNMENT 0x0D05
#define GL_PIXEL_PACK_BUFFER 0x88EB
#define GL_PIXEL_PACK_BUFFER_BINDING 0x88ED
#define GL_READ_FRAMEBUFFER 0x8CA8
#define GL_READ_FRAMEBUFFER_BINDING 0x8CAA
#define GL_RGBA 0x1908
#define GL_UNSIGNED_BYTE 0x1401

static void present_copy(Window *w) {
    static void (*GetIntegerv)(unsigned, int *);
    static void (*PixelStorei)(unsigned, int);
    static void (*BindBuffer)(unsigned, unsigned);
    static void (*BindFramebuffer)(unsigned, unsigned);
    static void (*ReadPixels)(int, int, int, int, unsigned, unsigned, void *);
    if (!ReadPixels) {
        GetIntegerv = (void *) glfwGetProcAddress("glGetIntegerv");
        PixelStorei = (void *) glfwGetProcAddress("glPixelStorei");
        BindBuffer = (void *) glfwGetProcAddress("glBindBuffer");
        BindFramebuffer = (void *) glfwGetProcAddress("glBindFramebuffer");
        ReadPixels = (void *) glfwGetProcAddress("glReadPixels");
    }
    // frame counts in the log every 10 s: the only way to tell a slow game
    // from a stuck presentation from outside
    static int shown, skipped;
    static double since;
    double now = glfwGetTime();
    if (now - since >= 10) {
        glfw_log("presented %d frames, skipped %d (%dx%d)", shown, skipped, w->surf_w, w->surf_h);
        shown = skipped = 0;
        since = now;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(w->native, &buf, NULL) != 0) {
        skipped++;
        return;
    }
    if (buf.width != w->surf_w || buf.height != w->surf_h) {
        if (!skipped++) glfw_log("window buffer %dx%d, pbuffer %dx%d", buf.width, buf.height, w->surf_w, w->surf_h);
    } else {
        shown++;
        int pack, fb, row, align;
        GetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pack);
        GetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &fb);
        GetIntegerv(GL_PACK_ROW_LENGTH, &row);
        GetIntegerv(GL_PACK_ALIGNMENT, &align);
        BindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        BindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        PixelStorei(GL_PACK_ROW_LENGTH, buf.stride);
        PixelStorei(GL_PACK_ALIGNMENT, 4);
        ReadPixels(0, 0, buf.width, buf.height, GL_RGBA, GL_UNSIGNED_BYTE, buf.bits);
        PixelStorei(GL_PACK_ROW_LENGTH, row);
        PixelStorei(GL_PACK_ALIGNMENT, align);
        BindFramebuffer(GL_READ_FRAMEBUFFER, fb);
        BindBuffer(GL_PIXEL_PACK_BUFFER, pack);
    }
    ANativeWindow_unlockAndPost(w->native);
}

GLFWAPI void glfwSwapBuffers(GLFWwindow *w) {
    REQUIRE_INIT();
    if (!w->ctx) {
        glfw_error(GLFW_NO_WINDOW_CONTEXT, "window has no OpenGL context");
        return;
    }
    if (glfw_current == w) glfw_sync_surface(w);
    if (w->surf && w->native && glfw.present_copy) {
        if (glfw_current == w) present_copy(w);
    } else if (w->surf) {
        if (!glfw.SwapBuffers(glfw.dpy, w->surf)) {
            EGLint err = glfw.GetError();
            // the Surface went away under us; the UI thread will tell us soon
            if (err != EGL_BAD_SURFACE && err != EGL_BAD_NATIVE_WINDOW) glfw_log("eglSwapBuffers: 0x%x", err);
            usleep(16000);
        }
    } else {
        // backgrounded: nothing to show, don't spin
        usleep(50000);
    }
}

GLFWAPI void glfwSwapInterval(int interval) {
    REQUIRE_INIT();
    if (!glfw_current) {
        glfw_error(GLFW_NO_CURRENT_CONTEXT, "no current context");
        return;
    }
    glfw.SwapInterval(glfw.dpy, interval);
}

GLFWAPI GLFWglproc glfwGetProcAddress(const char *name) {
    REQUIRE_INIT(NULL);
    GLFWglproc f = (GLFWglproc) glfw.GetProcAddress(name);
    if (!f && glfw.gl_lib) f = (GLFWglproc) dlsym(glfw.gl_lib, name);
    if (!f) f = (GLFWglproc) dlsym(glfw.egl_lib, name);
    return f;
}

// LWJGL's GL loader is pointed at this library (org.lwjgl.opengl.libname) and
// looks functions up through glXGetProcAddress on Linux
GLFWAPI GLFWglproc glXGetProcAddress(const char *name) { return glfwGetProcAddress(name); }
GLFWAPI GLFWglproc glXGetProcAddressARB(const char *name) { return glfwGetProcAddress(name); }

// Whole-word search in a space-separated extension list
static int has_token(const char *list, const char *ext) {
    size_t n = strlen(ext);
    for (const char *p = list; p && (p = strstr(p, ext)); p += n)
        if ((p == list || p[-1] == ' ') && (p[n] == ' ' || !p[n])) return 1;
    return 0;
}

GLFWAPI int glfwExtensionSupported(const char *ext) {
    REQUIRE_INIT(0);
    if (!glfw_current) {
        glfw_error(GLFW_NO_CURRENT_CONTEXT, "no current context");
        return 0;
    }
    typedef const unsigned char *(*GetStringi)(unsigned, unsigned);
    typedef const unsigned char *(*GetString)(unsigned);
    typedef void (*GetIntegerv)(unsigned, int *);
    GetStringi stringi = (GetStringi) glfwGetProcAddress("glGetStringi");
    GetIntegerv integerv = (GetIntegerv) glfwGetProcAddress("glGetIntegerv");
    if (stringi && integerv) {
        int count = 0;
        integerv(GL_NUM_EXTENSIONS, &count);
        for (int i = 0; i < count; i++) {
            const char *e = (const char *) stringi(GL_EXTENSIONS, i);
            if (e && !strcmp(e, ext)) return 1;
        }
    } else {
        GetString string = (GetString) glfwGetProcAddress("glGetString");
        if (string && has_token((const char *) string(GL_EXTENSIONS), ext)) return 1;
    }
    return has_token(glfw.QueryString(glfw.dpy, EGL_EXTENSIONS), ext);
}

// GLFWNativeEGL
GLFWAPI EGLDisplay glfwGetEGLDisplay(void) { return glfw.dpy; }
GLFWAPI EGLContext glfwGetEGLContext(GLFWwindow *w) { return w->ctx; }
GLFWAPI EGLSurface glfwGetEGLSurface(GLFWwindow *w) { return draw_surface(w); }
GLFWAPI EGLConfig glfwGetEGLConfig(GLFWwindow *w) { return w->config; }
