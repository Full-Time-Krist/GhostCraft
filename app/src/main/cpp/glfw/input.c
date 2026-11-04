// Input: the UI thread posts events, glfwPollEvents replays them on the game
// thread into window state and callbacks.
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "internal.h"

void glfw_post(int type, int a, int b, int c, int d, double x, double y) {
    pthread_mutex_lock(&android.lock);
    if (android.count < QUEUE_SIZE) {
        android.queue[(android.head + android.count) % QUEUE_SIZE] = (Event) {type, a, b, c, d, x, y};
        android.count++;
    }
    pthread_cond_broadcast(&android.cond);
    pthread_mutex_unlock(&android.lock);
}

static Window *target(void) {
    for (Window *w = glfw.windows; w; w = w->next)
        if (w->primary) return w;
    return NULL;
}

// GLFW forgets held keys and buttons when focus goes, so nothing stays stuck
static void release_all(Window *w) {
    for (int k = 0; k <= GLFW_KEY_LAST; k++)
        if (w->keys[k] == GLFW_PRESS) {
            w->keys[k] = GLFW_RELEASE;
            if (w->cb.key) w->cb.key(w, k, k, GLFW_RELEASE, 0);
        }
    for (int b = 0; b <= GLFW_MOUSE_BUTTON_LAST; b++)
        if (w->buttons[b] == GLFW_PRESS) {
            w->buttons[b] = GLFW_RELEASE;
            if (w->cb.button) w->cb.button(w, b, GLFW_RELEASE, 0);
        }
}

static void dispatch(Window *w, const Event *e) {
    switch (e->type) {
        case EV_KEY: {
            int key = e->a, action = e->c, mods = e->d;
            if (key >= 0 && key <= GLFW_KEY_LAST) {
                if (action == GLFW_RELEASE && w->keys[key] == GLFW_RELEASE) return;
                w->keys[key] = action == GLFW_RELEASE && w->sticky_keys ? STICK
                             : action == GLFW_RELEASE ? GLFW_RELEASE : GLFW_PRESS;
            }
            if (!w->lock_mods) mods &= ~(GLFW_MOD_CAPS_LOCK | GLFW_MOD_NUM_LOCK);
            if (w->cb.key) w->cb.key(w, key, e->b, action, mods);
            break;
        }
        case EV_CHAR:
            if (e->a < 32 || (e->a > 126 && e->a < 160)) return;
            if (w->cb.charmods) w->cb.charmods(w, e->a, e->b);
            if (w->cb.chr) w->cb.chr(w, e->a);
            break;
        case EV_BUTTON: {
            int b = e->a;
            if (b < 0 || b > GLFW_MOUSE_BUTTON_LAST) return;
            w->buttons[b] = e->b == GLFW_RELEASE && w->sticky_buttons ? STICK : e->b;
            if (getenv("GHOSTCRAFT_INPUT_LOG"))
                glfw_log("button %d %d at %.0f,%.0f focused %d callback %p", b, e->b, w->cx, w->cy, w->focused, (void *) w->cb.button);
            if (w->cb.button) w->cb.button(w, b, e->b, e->c);
            break;
        }
        case EV_MOVE:
            // absolute positions mean nothing while the cursor is captured
            if (w->cursor_mode == GLFW_CURSOR_DISABLED) return;
            w->cx = e->x;
            w->cy = e->y;
            if (w->cb.cursor) w->cb.cursor(w, w->cx, w->cy);
            break;
        case EV_DELTA:
            w->cx += e->x;
            w->cy += e->y;
            if (w->cursor_mode != GLFW_CURSOR_DISABLED) {
                w->cx = w->cx < 0 ? 0 : w->cx > w->width - 1 ? w->width - 1 : w->cx;
                w->cy = w->cy < 0 ? 0 : w->cy > w->height - 1 ? w->height - 1 : w->cy;
            }
            if (w->cb.cursor) w->cb.cursor(w, w->cx, w->cy);
            break;
        case EV_SCROLL:
            if (w->cb.scroll) w->cb.scroll(w, e->x, e->y);
            break;
        case EV_RESIZE:
            if (e->a <= 0 || e->b <= 0 || (e->a == w->width && e->b == w->height)) return;
            w->width = e->a;
            w->height = e->b;
            if (w->cb.size) w->cb.size(w, e->a, e->b);
            if (w->cb.fbsize) w->cb.fbsize(w, e->a, e->b);
            if (w->cb.refresh) w->cb.refresh(w);
            break;
        case EV_FOCUS:
            if (w->focused == e->a) return;
            w->focused = e->a;
            if (!e->a) release_all(w);
            if (w->cb.focus) w->cb.focus(w, e->a);
            if (w->cb.enter) w->cb.enter(w, e->a);
            break;
        case EV_ICONIFY:
            if (w->iconified == e->a) return;
            w->iconified = e->a;
            if (w->cb.iconify) w->cb.iconify(w, e->a);
            break;
        case EV_CLOSE:
            w->should_close = 1;
            if (w->cb.close) w->cb.close(w);
            break;
    }
}

GLFWAPI void glfwPollEvents(void) {
    REQUIRE_INIT();
    Window *w = target();
    if (!w) return;
    if (w->announce_size) {
        w->announce_size = 0;
        if (w->cb.size) w->cb.size(w, w->width, w->height);
        if (w->cb.fbsize) w->cb.fbsize(w, w->width, w->height);
    }
    // Callbacks may call back into GLFW (and post nothing): copy out, then run
    // them without the lock
    Event batch[64];
    for (;;) {
        int n = 0;
        pthread_mutex_lock(&android.lock);
        while (n < 64 && android.count) {
            batch[n++] = android.queue[android.head];
            android.head = (android.head + 1) % QUEUE_SIZE;
            android.count--;
        }
        pthread_mutex_unlock(&android.lock);
        if (!n) break;
        for (int i = 0; i < n; i++) dispatch(w, &batch[i]);
    }
}

static void wait_events(double timeout) {
    pthread_mutex_lock(&android.lock);
    if (!android.count) {
        if (timeout < 0) {
            pthread_cond_wait(&android.cond, &android.lock);
        } else {
            struct timespec until;
            clock_gettime(CLOCK_REALTIME, &until);
            long long ns = until.tv_nsec + (long long) (timeout * 1e9);
            until.tv_sec += ns / 1000000000;
            until.tv_nsec = ns % 1000000000;
            pthread_cond_timedwait(&android.cond, &android.lock, &until);
        }
    }
    pthread_mutex_unlock(&android.lock);
    glfwPollEvents();
}

GLFWAPI void glfwWaitEvents(void) { REQUIRE_INIT(); wait_events(-1); }

GLFWAPI void glfwWaitEventsTimeout(double timeout) {
    REQUIRE_INIT();
    if (timeout != timeout || timeout < 0) {
        glfw_error(GLFW_INVALID_VALUE, "invalid timeout %f", timeout);
        return;
    }
    wait_events(timeout);
}

GLFWAPI void glfwPostEmptyEvent(void) {
    pthread_mutex_lock(&android.lock);
    pthread_cond_broadcast(&android.cond);
    pthread_mutex_unlock(&android.lock);
}

// --- keyboard and mouse state ------------------------------------------------

GLFWAPI int glfwGetInputMode(GLFWwindow *w, int mode) {
    switch (mode) {
        case GLFW_CURSOR: return w->cursor_mode;
        case GLFW_STICKY_KEYS: return w->sticky_keys;
        case GLFW_STICKY_MOUSE_BUTTONS: return w->sticky_buttons;
        case GLFW_LOCK_KEY_MODS: return w->lock_mods;
        case GLFW_RAW_MOUSE_MOTION: return w->raw_motion;
        default:
            glfw_error(GLFW_INVALID_ENUM, "invalid input mode 0x%x", mode);
            return 0;
    }
}

GLFWAPI void glfwSetInputMode(GLFWwindow *w, int mode, int value) {
    switch (mode) {
        case GLFW_CURSOR:
            if (value == GLFW_CURSOR_CAPTURED) value = GLFW_CURSOR_NORMAL;
            w->cursor_mode = value;
            if (w->primary) {
                // the UI thread switches touch handling and pointer capture on this
                pthread_mutex_lock(&android.lock);
                android.cursor_mode = value;
                pthread_mutex_unlock(&android.lock);
            }
            break;
        case GLFW_STICKY_KEYS:
            if (!value)
                for (int k = 0; k <= GLFW_KEY_LAST; k++)
                    if (w->keys[k] == STICK) w->keys[k] = GLFW_RELEASE;
            w->sticky_keys = value;
            break;
        case GLFW_STICKY_MOUSE_BUTTONS:
            if (!value)
                for (int b = 0; b <= GLFW_MOUSE_BUTTON_LAST; b++)
                    if (w->buttons[b] == STICK) w->buttons[b] = GLFW_RELEASE;
            w->sticky_buttons = value;
            break;
        case GLFW_LOCK_KEY_MODS: w->lock_mods = value; break;
        case GLFW_RAW_MOUSE_MOTION: w->raw_motion = value; break;
        default: glfw_error(GLFW_INVALID_ENUM, "invalid input mode 0x%x", mode);
    }
}

// Captured mouse and touch deltas are both unaccelerated already
GLFWAPI int glfwRawMouseMotionSupported(void) { return GLFW_TRUE; }

// Scancodes are the GLFW key codes themselves: the UI side maps Android keys
static const char *key_names[GLFW_KEY_LAST + 1] = {
    [39] = "'", [44] = ",", [45] = "-", [46] = ".", [47] = "/",
    [48] = "0", [49] = "1", [50] = "2", [51] = "3", [52] = "4",
    [53] = "5", [54] = "6", [55] = "7", [56] = "8", [57] = "9",
    [59] = ";", [61] = "=",
    [65] = "a", [66] = "b", [67] = "c", [68] = "d", [69] = "e", [70] = "f", [71] = "g",
    [72] = "h", [73] = "i", [74] = "j", [75] = "k", [76] = "l", [77] = "m", [78] = "n",
    [79] = "o", [80] = "p", [81] = "q", [82] = "r", [83] = "s", [84] = "t", [85] = "u",
    [86] = "v", [87] = "w", [88] = "x", [89] = "y", [90] = "z",
    [91] = "[", [92] = "\\", [93] = "]", [96] = "`",
    [320] = "0", [321] = "1", [322] = "2", [323] = "3", [324] = "4",
    [325] = "5", [326] = "6", [327] = "7", [328] = "8", [329] = "9",
    [330] = ".", [331] = "/", [332] = "*", [333] = "-", [334] = "+", [336] = "=",
};

GLFWAPI const char *glfwGetKeyName(int key, int scancode) {
    if (key == GLFW_KEY_UNKNOWN) key = scancode;
    return key >= 0 && key <= GLFW_KEY_LAST ? key_names[key] : NULL;
}

GLFWAPI int glfwGetKeyScancode(int key) { return key >= GLFW_KEY_SPACE && key <= GLFW_KEY_LAST ? key : -1; }

GLFWAPI int glfwGetKey(GLFWwindow *w, int key) {
    if (key < GLFW_KEY_SPACE || key > GLFW_KEY_LAST) {
        glfw_error(GLFW_INVALID_ENUM, "invalid key %d", key);
        return GLFW_RELEASE;
    }
    if (w->keys[key] == STICK) {
        w->keys[key] = GLFW_RELEASE;
        return GLFW_PRESS;
    }
    return w->keys[key];
}

GLFWAPI int glfwGetMouseButton(GLFWwindow *w, int button) {
    if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) {
        glfw_error(GLFW_INVALID_ENUM, "invalid mouse button %d", button);
        return GLFW_RELEASE;
    }
    if (w->buttons[button] == STICK) {
        w->buttons[button] = GLFW_RELEASE;
        return GLFW_PRESS;
    }
    return w->buttons[button];
}

GLFWAPI void glfwGetCursorPos(GLFWwindow *w, double *x, double *y) {
    if (x) *x = w->cx;
    if (y) *y = w->cy;
}

GLFWAPI void glfwSetCursorPos(GLFWwindow *w, double x, double y) {
    w->cx = x;
    w->cy = y;
}

// There is no pointer to draw on a touchscreen; cursors are just handles
GLFWAPI GLFWcursor *glfwCreateCursor(const GLFWimage *image, int xhot, int yhot) {
    (void) image; (void) xhot; (void) yhot;
    return calloc(1, sizeof(GLFWcursor));
}

GLFWAPI GLFWcursor *glfwCreateStandardCursor(int shape) {
    GLFWcursor *c = calloc(1, sizeof(GLFWcursor));
    c->shape = shape;
    return c;
}

GLFWAPI void glfwDestroyCursor(GLFWcursor *c) { free(c); }
GLFWAPI void glfwSetCursor(GLFWwindow *w, GLFWcursor *c) { (void) w; (void) c; }

// --- clipboard ---------------------------------------------------------------

GLFWAPI void glfwSetClipboardString(GLFWwindow *w, const char *s) {
    (void) w;
    pthread_mutex_lock(&android.lock);
    free(android.clip_out);
    android.clip_out = strdup(s ? s : "");
    android.clip_out_dirty = 1;
    free(android.clip_in);
    android.clip_in = strdup(s ? s : "");
    pthread_mutex_unlock(&android.lock);
}

GLFWAPI const char *glfwGetClipboardString(GLFWwindow *w) {
    (void) w;
    // valid until the next call, as in GLFW
    static char *copy;
    pthread_mutex_lock(&android.lock);
    free(copy);
    copy = strdup(android.clip_in ? android.clip_in : "");
    pthread_mutex_unlock(&android.lock);
    return copy;
}

// --- joysticks (gamepads come with on-screen controls, step 7) ---------------

GLFWAPI int glfwJoystickPresent(int jid) { (void) jid; return GLFW_FALSE; }

GLFWAPI const float *glfwGetJoystickAxes(int jid, int *count) {
    (void) jid;
    *count = 0;
    return NULL;
}

GLFWAPI const unsigned char *glfwGetJoystickButtons(int jid, int *count) {
    (void) jid;
    *count = 0;
    return NULL;
}

GLFWAPI const unsigned char *glfwGetJoystickHats(int jid, int *count) {
    (void) jid;
    *count = 0;
    return NULL;
}

GLFWAPI const char *glfwGetJoystickName(int jid) { (void) jid; return NULL; }
GLFWAPI const char *glfwGetJoystickGUID(int jid) { (void) jid; return NULL; }
GLFWAPI void glfwSetJoystickUserPointer(int jid, void *p) { (void) jid; (void) p; }
GLFWAPI void *glfwGetJoystickUserPointer(int jid) { (void) jid; return NULL; }
GLFWAPI int glfwJoystickIsGamepad(int jid) { (void) jid; return GLFW_FALSE; }

GLFWAPI GLFWjoystickfun glfwSetJoystickCallback(GLFWjoystickfun cb) {
    GLFWjoystickfun prev = glfw.joystick_cb;
    glfw.joystick_cb = cb;
    return prev;
}

GLFWAPI int glfwUpdateGamepadMappings(const char *s) { (void) s; return GLFW_TRUE; }
GLFWAPI const char *glfwGetGamepadName(int jid) { (void) jid; return NULL; }
GLFWAPI int glfwGetGamepadState(int jid, GLFWgamepadstate *state) { (void) jid; (void) state; return GLFW_FALSE; }
