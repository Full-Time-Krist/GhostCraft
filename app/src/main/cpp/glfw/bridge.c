// The Android side of GLFW: GameSurface (Kotlin) calls these on the UI thread.
//
// ART loads this library with System.loadLibrary and the game's JVM with
// dlopen of the same path from the same linker namespace, so both get one copy
// and share `android`. glfwInit logs its address to make that visible.
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "internal.h"

#define JNI(name) Java_dev_krist_ghostcraft_GlfwBridge_##name

JNIEXPORT void JNICALL JNI(setDisplay)(JNIEnv *env, jclass cls, jint w, jint h, jint refresh, jfloat xdpi, jfloat ydpi) {
    (void) env; (void) cls;
    pthread_mutex_lock(&android.lock);
    android.display_w = w;
    android.display_h = h;
    android.refresh = refresh;
    android.xdpi = xdpi;
    android.ydpi = ydpi;
    pthread_mutex_unlock(&android.lock);
}

JNIEXPORT void JNICALL JNI(setSurface)(JNIEnv *env, jclass cls, jobject surface, jint w, jint h) {
    (void) cls;
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    pthread_mutex_lock(&android.lock);
    if (win && win == android.window) {
        // same Surface: a window surface follows its size by itself, but the
        // copy mode's pbuffer has to be made again
        ANativeWindow_release(win);
        if (w != android.width || h != android.height) android.surface_gen++;
    } else {
        ANativeWindow *old = android.window;
        android.window = win;
        android.surface_gen++;
        pthread_cond_broadcast(&android.cond);
        if (!win && android.surface_in_use) {
            // The game must drop its EGLSurface before the Surface is gone. It
            // does so at its next swap; give it a moment, then go on anyway:
            // EGL keeps its own reference and only fails to present.
            struct timespec until;
            clock_gettime(CLOCK_REALTIME, &until);
            until.tv_sec += 2;
            while (android.surface_in_use)
                if (pthread_cond_timedwait(&android.cond, &android.lock, &until) == ETIMEDOUT) break;
        }
        if (old) ANativeWindow_release(old);
    }
    if (win) {
        android.width = w;
        android.height = h;
    }
    pthread_mutex_unlock(&android.lock);
    if (win) glfw_post(EV_RESIZE, w, h, 0, 0, 0, 0);
    glfw_post(EV_ICONIFY, win == NULL, 0, 0, 0, 0, 0);
}

JNIEXPORT void JNICALL JNI(key)(JNIEnv *env, jclass cls, jint key, jint action, jint mods) {
    (void) env; (void) cls;
    glfw_post(EV_KEY, key, key, action, mods, 0, 0);
}

JNIEXPORT void JNICALL JNI(chr)(JNIEnv *env, jclass cls, jint codepoint, jint mods) {
    (void) env; (void) cls;
    glfw_post(EV_CHAR, codepoint, mods, 0, 0, 0, 0);
}

JNIEXPORT void JNICALL JNI(button)(JNIEnv *env, jclass cls, jint button, jint action, jint mods) {
    (void) env; (void) cls;
    glfw_post(EV_BUTTON, button, action, mods, 0, 0, 0);
}

JNIEXPORT void JNICALL JNI(move)(JNIEnv *env, jclass cls, jfloat x, jfloat y) {
    (void) env; (void) cls;
    glfw_post(EV_MOVE, 0, 0, 0, 0, x, y);
}

JNIEXPORT void JNICALL JNI(delta)(JNIEnv *env, jclass cls, jfloat dx, jfloat dy) {
    (void) env; (void) cls;
    glfw_post(EV_DELTA, 0, 0, 0, 0, dx, dy);
}

JNIEXPORT void JNICALL JNI(scroll)(JNIEnv *env, jclass cls, jfloat dx, jfloat dy) {
    (void) env; (void) cls;
    glfw_post(EV_SCROLL, 0, 0, 0, 0, dx, dy);
}

JNIEXPORT void JNICALL JNI(focus)(JNIEnv *env, jclass cls, jboolean focused) {
    (void) env; (void) cls;
    glfw_post(EV_FOCUS, focused, 0, 0, 0, 0, 0);
}

JNIEXPORT void JNICALL JNI(requestClose)(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    glfw_post(EV_CLOSE, 0, 0, 0, 0, 0, 0);
}

JNIEXPORT jboolean JNICALL JNI(windowReady)(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    pthread_mutex_lock(&android.lock);
    int ready = android.window_ready;
    pthread_mutex_unlock(&android.lock);
    return ready;
}

JNIEXPORT jint JNICALL JNI(cursorMode)(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    pthread_mutex_lock(&android.lock);
    int mode = android.cursor_mode;
    pthread_mutex_unlock(&android.lock);
    return mode;
}

// Clipboard text crosses as UTF-8 bytes: JNI strings are "modified UTF-8"
// and can't carry emoji
JNIEXPORT void JNICALL JNI(setClipboard)(JNIEnv *env, jclass cls, jbyteArray text) {
    (void) cls;
    jsize n = (*env)->GetArrayLength(env, text);
    char *s = malloc(n + 1);
    (*env)->GetByteArrayRegion(env, text, 0, n, (jbyte *) s);
    s[n] = 0;
    pthread_mutex_lock(&android.lock);
    free(android.clip_in);
    android.clip_in = s;
    pthread_mutex_unlock(&android.lock);
}

// What the game copied since the last call, or null
JNIEXPORT jbyteArray JNICALL JNI(takeClipboard)(JNIEnv *env, jclass cls) {
    (void) cls;
    jbyteArray out = NULL;
    pthread_mutex_lock(&android.lock);
    if (android.clip_out_dirty) {
        android.clip_out_dirty = 0;
        jsize n = (jsize) strlen(android.clip_out);
        out = (*env)->NewByteArray(env, n);
        (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) android.clip_out);
    }
    pthread_mutex_unlock(&android.lock);
    return out;
}
