// Starts our OpenJDK build inside the :game process.
//
// We go through libjli's JLI_Launch, the same entry point bin/java uses, so
// every option Minecraft and mod loaders pass (--add-opens, -p, @argfiles,
// -XX:...) is understood exactly as on the PC. libjli finds the JDK home from
// its own location and dlopens lib/server/libjvm.so; the VM runs main() in a
// new thread and JLI_Launch returns when it finishes. System.exit() ends the
// whole :game process, which is why the game never shares a process with the
// launcher UI.
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "GhostCraft"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef int (*JLI_Launch_t)(int argc, char **argv,
                            int jargc, const char **jargv,
                            int appclassc, const char **appclassv,
                            const char *fullversion, const char *dotversion,
                            const char *pname, const char *lname,
                            jboolean javaargs, jboolean cpwildcard,
                            jboolean javaw, jint ergo);

// The VM, Minecraft and its log4j all write to fd 1/2; send both to one file
// the launcher UI tails. A thread follows the same file into logcat, where adb
// can read it even when the file itself isn't accessible. The file stays the
// primary copy: an abort can't lose lines that are still in a pipe.
static void *follow_log(void *arg) {
    int in = (int) (intptr_t) arg;
    char buf[4096], line[1024];
    size_t used = 0;
    for (;;) {
        ssize_t n = read(in, buf, sizeof(buf));
        if (n <= 0) {
            usleep(100000);
            continue;
        }
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n' || used == sizeof(line) - 1) {
                line[used] = 0;
                __android_log_write(ANDROID_LOG_INFO, "GhostCraft-Game", line);
                used = 0;
                if (buf[i] == '\n') continue;
            }
            line[used++] = buf[i];
        }
    }
    return NULL;
}

static int redirect_output(const char *path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return -1;
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    close(fd);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    int in = open(path, O_RDONLY | O_CLOEXEC);
    pthread_t t;
    if (in >= 0 && pthread_create(&t, NULL, follow_log, (void *) (intptr_t) in) == 0) pthread_detach(t);
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_krist_ghostcraft_GameBridge_launch(JNIEnv *env, jclass cls, jstring jhome,
                                            jobjectArray jargs, jstring jdir, jstring jlog) {
    (void) cls;
    const char *home = (*env)->GetStringUTFChars(env, jhome, NULL);
    const char *log = (*env)->GetStringUTFChars(env, jlog, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    if (redirect_output(log) != 0) LOGE("cannot open log %s", log);
    // Minecraft and log4j resolve logs/, config/, crash-reports/ against the cwd
    if (chdir(dir) != 0) fprintf(stderr, "[GhostCraft] chdir %s failed\n", dir);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);

    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }

    printf("[GhostCraft] JAVA_HOME=%s\n[GhostCraft]", home);
    for (int i = 0; i < argc; i++) printf(" %s", argv[i]);
    printf("\n");

    // With LD_LIBRARY_PATH set libjli would re-exec /proc/self/exe, which here
    // is app_process, not java
    unsetenv("LD_LIBRARY_PATH");
    setenv("JAVA_HOME", home, 1);

    char path[4096];
    snprintf(path, sizeof(path), "%s/lib/libjli.so", home);
    void *jli = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    JLI_Launch_t launch = jli ? (JLI_Launch_t) dlsym(jli, "JLI_Launch") : NULL;
    (*env)->ReleaseStringUTFChars(env, jhome, home);
    (*env)->ReleaseStringUTFChars(env, jlog, log);
    if (!launch) {
        fprintf(stderr, "[GhostCraft] libjli: %s\n", dlerror());
        return -1;
    }

    int code = launch(argc, argv, 0, NULL, 0, NULL,
                      "21-ghostcraft", "21", "java", "openjdk",
                      JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
    printf("[GhostCraft] JVM exited with code %d\n", code);
    fflush(stdout);
    return code;
}
