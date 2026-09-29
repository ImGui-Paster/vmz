#ifndef VIRTUALM_PROCHOOK_H
#define VIRTUALM_PROCHOOK_H

#include <jni.h>

/**
 * Native support layer for Root emulation and GameGuardian:
 *  - visibility of emulated su artifacts for native (non-Java) open/openat calls
 *  - /proc/<pid>/cmdline virtual process name mapping (GameGuardian discovery)
 *
 * Only open/openat are inline-hooked (see ProcHook.cpp). Tiny bionic syscall
 * stubs (access/stat/lstat/getuid/geteuid/...) are deliberately NOT hooked:
 * they are too small to host a Dobby trampoline safely on every ABI, and a
 * botched patch corrupts neighbouring libc code -> frozen black screen, no crash.
 *
 * NOTE: the anti-virtual-detection module (AntiDetection.cpp) is intentionally
 * NOT modified by this feature. These hooks are additive and only divert
 * exact su/proc-cmdline matches when fake-root is enabled for the process.
 */
class ProcHook {
public:
    static void init(JNIEnv *env);

    // JNI bridge (registered from BoxCore.cpp)
    static void setFakeRootEnabled(JNIEnv *env, jclass clazz, jboolean enabled);
    static void setRootFsPath(JNIEnv *env, jclass clazz, jstring rootFsPath);
    static void setExecLogPath(JNIEnv *env, jclass clazz, jstring logPath);
    static void addProcMapping(JNIEnv *env, jclass clazz, jint pid, jstring virtualName, jstring spoofPath);
    static void removeProcMapping(JNIEnv *env, jclass clazz, jint pid);
};

#endif //VIRTUALM_PROCHOOK_H
