// Our GLFW 3.4 for Android: the subset of the API LWJGL binds, implemented over
// the SurfaceView's ANativeWindow and EGL.
//
// Two threads meet here. The Android UI thread (bridge.c) owns the Surface and
// turns touches and keys into events; the game thread calls the GLFW API and
// drains those events in glfwPollEvents, so callbacks always run where the game
// expects them. Everything shared between the two lives in `android` and is
// guarded by its lock.
#pragma once

#define EGL_EGL_PROTOTYPES 0
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/native_window.h>
#include <pthread.h>
#include <stdint.h>

#define GLFWAPI __attribute__((visibility("default")))

#define GLFW_TRUE 1
#define GLFW_FALSE 0
#define GLFW_DONT_CARE (-1)

#define GLFW_RELEASE 0
#define GLFW_PRESS 1
#define GLFW_REPEAT 2

#define GLFW_KEY_UNKNOWN (-1)
#define GLFW_KEY_SPACE 32
#define GLFW_KEY_ESCAPE 256
#define GLFW_KEY_ENTER 257
#define GLFW_KEY_KP_0 320
#define GLFW_KEY_KP_EQUAL 336
#define GLFW_KEY_LAST 348
#define GLFW_MOUSE_BUTTON_LAST 7

#define GLFW_MOD_SHIFT 0x0001
#define GLFW_MOD_CAPS_LOCK 0x0010
#define GLFW_MOD_NUM_LOCK 0x0020

#define GLFW_NOT_INITIALIZED 0x00010001
#define GLFW_NO_CURRENT_CONTEXT 0x00010002
#define GLFW_INVALID_ENUM 0x00010003
#define GLFW_INVALID_VALUE 0x00010004
#define GLFW_API_UNAVAILABLE 0x00010006
#define GLFW_VERSION_UNAVAILABLE 0x00010007
#define GLFW_PLATFORM_ERROR 0x00010008
#define GLFW_FORMAT_UNAVAILABLE 0x00010009
#define GLFW_NO_WINDOW_CONTEXT 0x0001000A
#define GLFW_FEATURE_UNAVAILABLE 0x0001000C

#define GLFW_FOCUSED 0x00020001
#define GLFW_ICONIFIED 0x00020002
#define GLFW_RESIZABLE 0x00020003
#define GLFW_VISIBLE 0x00020004
#define GLFW_DECORATED 0x00020005
#define GLFW_AUTO_ICONIFY 0x00020006
#define GLFW_FLOATING 0x00020007
#define GLFW_MAXIMIZED 0x00020008
#define GLFW_CENTER_CURSOR 0x00020009
#define GLFW_TRANSPARENT_FRAMEBUFFER 0x0002000A
#define GLFW_HOVERED 0x0002000B
#define GLFW_FOCUS_ON_SHOW 0x0002000C
#define GLFW_MOUSE_PASSTHROUGH 0x0002000D

#define GLFW_RED_BITS 0x00021001
#define GLFW_GREEN_BITS 0x00021002
#define GLFW_BLUE_BITS 0x00021003
#define GLFW_ALPHA_BITS 0x00021004
#define GLFW_DEPTH_BITS 0x00021005
#define GLFW_STENCIL_BITS 0x00021006
#define GLFW_SAMPLES 0x0002100D
#define GLFW_SRGB_CAPABLE 0x0002100E
#define GLFW_REFRESH_RATE 0x0002100F
#define GLFW_DOUBLEBUFFER 0x00021010

#define GLFW_CLIENT_API 0x00022001
#define GLFW_CONTEXT_VERSION_MAJOR 0x00022002
#define GLFW_CONTEXT_VERSION_MINOR 0x00022003
#define GLFW_CONTEXT_REVISION 0x00022004
#define GLFW_CONTEXT_ROBUSTNESS 0x00022005
#define GLFW_OPENGL_FORWARD_COMPAT 0x00022006
#define GLFW_CONTEXT_DEBUG 0x00022007
#define GLFW_OPENGL_PROFILE 0x00022008
#define GLFW_CONTEXT_RELEASE_BEHAVIOR 0x00022009
#define GLFW_CONTEXT_NO_ERROR 0x0002200A
#define GLFW_CONTEXT_CREATION_API 0x0002200B

#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x00030001
#define GLFW_OPENGL_ES_API 0x00030002
#define GLFW_OPENGL_ANY_PROFILE 0
#define GLFW_OPENGL_CORE_PROFILE 0x00032001
#define GLFW_OPENGL_COMPAT_PROFILE 0x00032002
#define GLFW_NATIVE_CONTEXT_API 0x00036001
#define GLFW_EGL_CONTEXT_API 0x00036002

#define GLFW_CURSOR 0x00033001
#define GLFW_STICKY_KEYS 0x00033002
#define GLFW_STICKY_MOUSE_BUTTONS 0x00033003
#define GLFW_LOCK_KEY_MODS 0x00033004
#define GLFW_RAW_MOUSE_MOTION 0x00033005
#define GLFW_CURSOR_NORMAL 0x00034001
#define GLFW_CURSOR_HIDDEN 0x00034002
#define GLFW_CURSOR_DISABLED 0x00034003
#define GLFW_CURSOR_CAPTURED 0x00034004

#define GLFW_PLATFORM 0x00050003
#define GLFW_ANY_PLATFORM 0x00060000
#define GLFW_PLATFORM_NULL 0x00060005

// sticky state: released, but not yet seen by glfwGetKey/glfwGetMouseButton
#define STICK 3

typedef struct Window GLFWwindow;
typedef struct Monitor GLFWmonitor;
typedef struct Cursor GLFWcursor;
typedef struct Window Window;
typedef struct Monitor Monitor;
typedef void (*GLFWglproc)(void);

typedef struct { int width, height, redBits, greenBits, blueBits, refreshRate; } GLFWvidmode;
typedef struct { int width, height; unsigned char *pixels; } GLFWimage;
typedef struct { unsigned short *red, *green, *blue; unsigned int size; } GLFWgammaramp;
typedef struct { unsigned char buttons[15]; float axes[6]; } GLFWgamepadstate;
typedef struct { void *allocate, *reallocate, *deallocate, *user; } GLFWallocator;

typedef void (*GLFWerrorfun)(int, const char *);
typedef void (*GLFWmonitorfun)(GLFWmonitor *, int);
typedef void (*GLFWjoystickfun)(int, int);
typedef void (*GLFWwindowposfun)(GLFWwindow *, int, int);
typedef void (*GLFWwindowsizefun)(GLFWwindow *, int, int);
typedef void (*GLFWwindowclosefun)(GLFWwindow *);
typedef void (*GLFWwindowrefreshfun)(GLFWwindow *);
typedef void (*GLFWwindowfocusfun)(GLFWwindow *, int);
typedef void (*GLFWwindowiconifyfun)(GLFWwindow *, int);
typedef void (*GLFWwindowmaximizefun)(GLFWwindow *, int);
typedef void (*GLFWframebuffersizefun)(GLFWwindow *, int, int);
typedef void (*GLFWwindowcontentscalefun)(GLFWwindow *, float, float);
typedef void (*GLFWkeyfun)(GLFWwindow *, int, int, int, int);
typedef void (*GLFWcharfun)(GLFWwindow *, unsigned int);
typedef void (*GLFWcharmodsfun)(GLFWwindow *, unsigned int, int);
typedef void (*GLFWmousebuttonfun)(GLFWwindow *, int, int, int);
typedef void (*GLFWcursorposfun)(GLFWwindow *, double, double);
typedef void (*GLFWcursorenterfun)(GLFWwindow *, int);
typedef void (*GLFWscrollfun)(GLFWwindow *, double, double);
typedef void (*GLFWdropfun)(GLFWwindow *, int, const char **);

struct Monitor {
    char name[32];
    GLFWvidmode mode;
    int width_mm, height_mm;
    void *user;
};

struct Cursor { int shape; };

typedef struct {
    int client_api, creation_api, major, minor, forward, debug, profile, robustness, no_error;
    int red, green, blue, alpha, depth, stencil, samples, srgb, doublebuffer, refresh;
    int visible, focused, resizable, decorated, floating, maximized, auto_iconify, focus_on_show;
} Hints;

struct Window {
    struct Window *next;
    Hints hints;
    void *user;
    GLFWmonitor *monitor;
    int width, height;
    int should_close, focused, iconified, visible;
    // created at another size than asked for: say so like a window manager
    // would, or the game keeps mapping the cursor to the size it asked for
    int announce_size;
    // the first window gets the Android surface, the rest draw off-screen
    int primary;

    EGLenum api;            // EGL_OPENGL_API or EGL_OPENGL_ES_API, bound per thread
    int ctx_major, ctx_minor;
    EGLConfig config;
    EGLContext ctx;
    EGLSurface surf;        // on the Android surface, if we hold one
    EGLSurface pbuf;        // 1x1, keeps the context current while backgrounded
    ANativeWindow *native;  // what surf was created on (or is copied to)
    int surface_gen;
    int surf_w, surf_h;     // copy mode: size of the pbuffer standing in for the window

    int cursor_mode, sticky_keys, sticky_buttons, lock_mods, raw_motion;
    double cx, cy;
    char keys[GLFW_KEY_LAST + 1];
    char buttons[GLFW_MOUSE_BUTTON_LAST + 1];

    struct {
        GLFWwindowposfun pos;
        GLFWwindowsizefun size;
        GLFWwindowclosefun close;
        GLFWwindowrefreshfun refresh;
        GLFWwindowfocusfun focus;
        GLFWwindowiconifyfun iconify;
        GLFWwindowmaximizefun maximize;
        GLFWframebuffersizefun fbsize;
        GLFWwindowcontentscalefun scale;
        GLFWkeyfun key;
        GLFWcharfun chr;
        GLFWcharmodsfun charmods;
        GLFWmousebuttonfun button;
        GLFWcursorposfun cursor;
        GLFWcursorenterfun enter;
        GLFWscrollfun scroll;
        GLFWdropfun drop;
    } cb;
};

enum { EV_KEY, EV_CHAR, EV_BUTTON, EV_MOVE, EV_DELTA, EV_SCROLL, EV_RESIZE, EV_FOCUS, EV_ICONIFY, EV_CLOSE };

typedef struct {
    int type, a, b, c, d;
    double x, y;
} Event;

#define QUEUE_SIZE 1024

// State shared with the UI thread
struct Android {
    pthread_mutex_t lock;
    pthread_cond_t cond;
    ANativeWindow *window;  // current Surface, NULL while backgrounded
    int width, height;      // its buffer size
    int surface_gen;        // bumped whenever window changes
    int surface_in_use;     // the game still has an EGLSurface on an old window
    int display_w, display_h, refresh;
    float xdpi, ydpi;
    int window_ready;
    int cursor_mode;
    Event queue[QUEUE_SIZE];
    int head, count;
    char *clip_in;          // Android → game
    char *clip_out;         // game → Android, taken by the UI thread
    int clip_out_dirty;
};

struct Glfw {
    int initialized;
    Hints hints;
    Window *windows;
    Monitor monitor;
    GLFWmonitor *monitors[1];
    uint64_t time_base;

    void *egl_lib, *gl_lib;
    EGLDisplay dpy;
    // GHOSTCRAFT_PRESENT=copy: render into a pbuffer and copy each frame into
    // the window with glReadPixels, for drivers without an Android WSI
    // (Turnip loaded directly by Zink)
    int present_copy;
    PFNEGLGETDISPLAYPROC GetDisplay;
    PFNEGLINITIALIZEPROC Initialize;
    PFNEGLBINDAPIPROC BindAPI;
    PFNEGLCHOOSECONFIGPROC ChooseConfig;
    PFNEGLGETCONFIGATTRIBPROC GetConfigAttrib;
    PFNEGLCREATECONTEXTPROC CreateContext;
    PFNEGLDESTROYCONTEXTPROC DestroyContext;
    PFNEGLCREATEWINDOWSURFACEPROC CreateWindowSurface;
    PFNEGLCREATEPBUFFERSURFACEPROC CreatePbufferSurface;
    PFNEGLDESTROYSURFACEPROC DestroySurface;
    PFNEGLMAKECURRENTPROC MakeCurrent;
    PFNEGLSWAPBUFFERSPROC SwapBuffers;
    PFNEGLSWAPINTERVALPROC SwapInterval;
    PFNEGLGETPROCADDRESSPROC GetProcAddress;
    PFNEGLGETERRORPROC GetError;
    PFNEGLQUERYSTRINGPROC QueryString;

    GLFWerrorfun error_cb;
    GLFWmonitorfun monitor_cb;
    GLFWjoystickfun joystick_cb;
};

extern struct Glfw glfw;
extern struct Android android;
extern __thread Window *glfw_current;

void glfw_error(int code, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
void glfw_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void glfw_post(int type, int a, int b, int c, int d, double x, double y);
int glfw_load_egl(void);
void glfw_sync_surface(Window *w);
void glfw_update_monitor(void);
void glfwDefaultWindowHints(void);
void glfwDestroyWindow(GLFWwindow *w);
GLFWglproc glfwGetProcAddress(const char *name);
double glfwGetTime(void);

#define REQUIRE_INIT(ret)                                  \
    do {                                                   \
        if (!glfw.initialized) {                           \
            glfw_error(GLFW_NOT_INITIALIZED, "GLFW is not initialized"); \
            return ret;                                    \
        }                                                  \
    } while (0)
