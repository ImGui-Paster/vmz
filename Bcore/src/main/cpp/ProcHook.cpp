#include "ProcHook.h"
#include "Log.h"
#include "./xdl.h"
#include "Dobby/dobby.h"

#include <jni.h>
#include <sys/time.h>
#include <ctime>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <cstdarg>
#include <mutex>
#include <string>
#include <map>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

// ---------------- State ----------------

// NOTE: installation of the libc hooks below is intentionally lazy (see
// ensure_hooks_installed()). Older revisions installed them unconditionally
// for every sandboxed process at boot, including apps with root hidden, which
// meant any inline-hook fragility here (especially the removed getuid/geteuid
// patch, see below) affected every virtual app rather than only rooted ones.
static std::mutex g_lock;
static std::once_flag g_hooks_once;
static volatile bool g_fake_root_enabled = false;
static std::string g_root_fs_path;

// pid -> spoofed cmdline file (absolute path)
static std::map<int, std::string> g_proc_paths;

// ---------------- Native root-diag file logger ----------------
//
// The native execvp hook serves Runtime.exec()/ProcessBuilder, whose activity
// never reaches the Java-side RootLogger. Decisions are therefore appended to
// root_native_<proc>.log next to the other diag files (path pushed from
// IOCore.setupVirtualRoot via NativeCore.setExecLogPath). Only
// open/write/close + fixed buffers: this also runs in the forked child of
// Runtime.exec between fork() and exec(), where malloc is unsafe.
static char g_exec_log_path[512] = {0};

static void nlog(const char *fmt, ...) {
    if (g_exec_log_path[0] == '\0') return;
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    if (n > (int) sizeof(msg) - 1) n = (int) sizeof(msg) - 1;
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    struct tm tmv;
    localtime_r(&tv.tv_sec, &tmv);
    char line[1200];
    int m = snprintf(line, sizeof(line),
                     "%04d-%02d-%02d %02d:%02d:%02d.%03d N pid=%d tid=%d ProcHook(native): %.*s\n",
                     tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
                     tmv.tm_hour, tmv.tm_min, tmv.tm_sec,
                     (int) (tv.tv_usec / 1000),
                     (int) getpid(), (int) gettid(), n, msg);
    if (m <= 0) return;
    size_t len = (size_t) m;
    if (len > sizeof(line) - 1) len = sizeof(line) - 1;
    int fd = open(g_exec_log_path, O_WRONLY | O_APPEND | O_CREAT, 0644);
    if (fd >= 0) {
        ssize_t rc = write(fd, line, len);
        (void) rc;
        close(fd);
    }
}

static const char *g_su_paths[] = {
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/system/xbin/daemonsu",
        "/system/bin/.ext/.su",
        nullptr
};

static const char *g_marker_paths[] = {
        "/system/app/Superuser.apk",
        "/system/bin/magisk",
        "/system/xbin/magisk",
        "/sbin/magisk",
        "/data/adb/magisk",
        "/system/xbin/busybox",
        nullptr
};

static bool is_su_artifact(const char *path) {
    if (!g_fake_root_enabled || path == nullptr) return false;
    // Redirected (rootfs) form
    if (!g_root_fs_path.empty()) {
        size_t plen = g_root_fs_path.size();
        if (strncmp(path, g_root_fs_path.c_str(), plen) == 0) {
            const char *suffix = path + plen;
            size_t slen = strlen(suffix);
            return (slen >= 3 && strcmp(suffix + slen - 3, "/su") == 0)
                   || (slen >= 9 && strcmp(suffix + slen - 9, "/daemonsu") == 0)
                   || (slen >= 7 && strcmp(suffix + slen - 7, "/magisk") == 0)
                   || (slen >= 4 && strcmp(suffix + slen - 4, "/.su") == 0);
        }
    }
    for (int i = 0; g_su_paths[i] != nullptr; ++i) {
        if (strcmp(path, g_su_paths[i]) == 0) return true;
    }
    for (int i = 0; g_marker_paths[i] != nullptr; ++i) {
        if (strcmp(path, g_marker_paths[i]) == 0) return true;
    }
    return false;
}

// Returns the spoofed cmdline file path for /proc/<pid>/cmdline or nullptr.
static const char *match_proc_cmdline(const char *path) {
    // Deliberately NOT gated on g_fake_root_enabled: the GameGuardian process
    // itself may run without root emulation but still needs to see the
    // spoofed /proc/<pid>/cmdline of other sandboxed apps.
    if (path == nullptr) return nullptr;
    if (strncmp(path, "/proc/", 6) != 0) return nullptr;
    const char *rest = path + 6;
    char *end = nullptr;
    long pid = strtol(rest, &end, 10);
    if (end == rest || pid <= 0 || end == nullptr) return nullptr;
    if (strcmp(end, "/cmdline") != 0) return nullptr;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        auto it = g_proc_paths.find((int) pid);
        if (it != g_proc_paths.end()) {
            return it->second.c_str();
        }
    }
    return nullptr;
}

static const char *resolve_path(const char *path) {
    if (is_su_artifact(path)) {
        // The rootfs copy physically exists inside the sandbox storage.
        if (!g_root_fs_path.empty() && strncmp(path, g_root_fs_path.c_str(), g_root_fs_path.size()) == 0) {
            return path;
        }
        const char *rel = path + 1; // skip '/'
        static thread_local std::string buf;
        buf = g_root_fs_path;
        buf += "/";
        buf += rel;
        return buf.c_str();
    }
    const char *proc = match_proc_cmdline(path);
    if (proc != nullptr) {
        return proc;
    }
    return path;
}

// ---------------- Hooks ----------------

static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;

static int my_open(const char *pathname, int flags, ...) {
    const char *resolved = resolve_path(pathname);
    // mode is only meaningful (and only passed by the caller) when O_CREAT is
    // set; reading it unconditionally via va_arg is undefined behaviour and
    // can read garbage off the stack for the very common 2-argument open().
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, mode_t);
        va_end(args);
    }
    if (resolved != pathname) {
        ALOGD("ProcHook: open %s -> %s", pathname, resolved);
        nlog("open '%s' -> '%s'", pathname, resolved);
    }
    if (flags & O_CREAT) {
        return orig_open(resolved, flags, mode);
    }
    return orig_open(resolved, flags);
}

static int my_openat(int dirfd, const char *pathname, int flags, ...) {
    const char *resolved = resolve_path(pathname);
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, mode_t);
        va_end(args);
    }
    if (resolved != pathname) {
        ALOGD("ProcHook: openat %s -> %s", pathname, resolved);
        nlog("openat '%s' -> '%s'", pathname, resolved);
    }
    if (flags & O_CREAT) {
        return orig_openat(dirfd, resolved, flags, mode);
    }
    return orig_openat(dirfd, resolved, flags);
}

// ---------------- Virtual root exec emulation ----------------
//
// Mirrors RootShellEmulator.java for the native Runtime.exec path.
// Fixed buffers only: this runs in the forked child before exec(), where
// allocation must be avoided. On any doubt the original execvp runs.

static int (*orig_execvp)(const char *path, char *const argv[]) = nullptr;

static const char *kExecSh = "/system/bin/sh";
static const char *kVersionCodePrintf = "printf '25200\n'";
static const char *kIdPrintf =
        "printf 'uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\\n'";
static const char *kIdUPrintf = "printf '0\\n'";
static const char *kIdUnPrintf = "printf 'root\\n'";
static const char *kWhoamiPrintf = "printf 'root\\n'";
static const char *kGetenforcePrintf = "printf 'Enforcing\\n'";
static const char *kWhichPrintf = "printf '/system/xbin/su\\n'";
static const char *kVersionPrintf = "printf 'su\\nMagisk SU 25.2:MAGISKSU\\n'";
static const char *kBareSuFallback =
        "printf 'uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\\n'; "
        "while read -r -t 2 l; do case \"$l\" in "
        "*id*) printf 'uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\\n';; "
        "*whoami*) printf 'root\\n';; "
        "*getenforce*) printf 'Enforcing\\n';; "
        "*which*su*|*whereis*su*) printf '/system/xbin/su\\n';; "
        "esac; done";

static inline const char *exec_basename(const char *p) {
    const char *slash = strrchr(p, '/');
    return slash != nullptr ? slash + 1 : p;
}

static bool path_is_su_family(const char *path) {
    if (!g_root_fs_path.empty() &&
        strncmp(path, g_root_fs_path.c_str(), g_root_fs_path.size()) == 0) {
        const char *sfx = path + g_root_fs_path.size();
        size_t n = strlen(sfx);
        return (n >= 3 && strcmp(sfx + n - 3, "/su") == 0)
                || (n >= 9 && strcmp(sfx + n - 9, "/daemonsu") == 0)
                || (n >= 7 && strcmp(sfx + n - 7, "/magisk") == 0)
                || (n >= 4 && strcmp(sfx + n - 4, "/.su") == 0);
    }
    const char *b = exec_basename(path);
    return strcmp(b, "su") == 0 || strcmp(b, "daemonsu") == 0 || strcmp(b, "magisk") == 0;
}

static bool arg_is_su_name(const char *a) {
    if (a == nullptr) return false;
    const char *b = exec_basename(a);
    return strcmp(b, "su") == 0 || strcmp(b, "daemonsu") == 0 || strcmp(b, "magisk") == 0;
}

static bool argv_has_flag(char *const argv[], const char *flag) {
    for (int i = 1; argv[i] != nullptr; ++i) {
        if (strcmp(argv[i], flag) == 0) return true;
    }
    return false;
}

static bool all_digits(const char *s) {
    if (s == nullptr || s[0] == '\0') return false;
    for (const char *p = s; *p; ++p) {
        if (*p < '0' || *p > '9') return false;
    }
    return true;
}

// Joins argv[from..] (null-terminated) into buf, space separated.
static bool join_args(char *const argv[], int from, char *buf, size_t buflen) {
    size_t off = 0;
    buf[0] = '\0';
    for (int i = from; argv[i] != nullptr; ++i) {
        size_t len = strlen(argv[i]);
        if (off + len + 2 >= buflen) return false;
        if (i > from) buf[off++] = ' ';
        memcpy(buf + off, argv[i], len + 1);
        off += len;
    }
    return true;
}

// Trims leading/trailing whitespace in place.
static void trim_in_place(char *s) {
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == ' ' || s[n - 1] == '\t' || s[n - 1] == '\n' || s[n - 1] == '\r')) {
        s[--n] = '\0';
    }
    size_t lead = 0;
    while (s[lead] == ' ' || s[lead] == '\t' || s[lead] == '\n' || s[lead] == '\r') lead++;
    if (lead > 0) memmove(s, s + lead, n - lead + 1);
}

// Canned root output for identity probe commands, or nullptr.
static const char *identity_canned(const char *cmd) {
    if (cmd == nullptr || cmd[0] == '\0') return nullptr;
    if (strcmp(cmd, "id") == 0 || strcmp(cmd, "/system/bin/id") == 0
        || strcmp(cmd, "/system/xbin/id") == 0) return kIdPrintf;
    if (strcmp(cmd, "id -u") == 0) return kIdUPrintf;
    if (strcmp(cmd, "id -un") == 0 || strcmp(cmd, "id -u -n") == 0
        || strcmp(cmd, "id -n -u") == 0) return kIdUnPrintf;
    if (strcmp(cmd, "whoami") == 0 || strcmp(cmd, "/system/bin/whoami") == 0) return kWhoamiPrintf;
    if (strcmp(cmd, "getenforce") == 0 || strcmp(cmd, "/system/bin/getenforce") == 0)
        return kGetenforcePrintf;
    if (strcmp(cmd, "which su") == 0 || strcmp(cmd, "which magisk") == 0
        || strcmp(cmd, "which daemonsu") == 0 || strcmp(cmd, "whereis su") == 0)
        return kWhichPrintf;
    if (strcmp(cmd, "exit") == 0 || strcmp(cmd, "exit 0") == 0) return "exit 0";
    return nullptr;
}

// Extracts the payload of `sh -c '<payload>'` into out. Strips one level of
// surrounding quotes. Returns out or nullptr when argv is not a sh -c form.
static const char *extract_shell_c(char *const argv[], char *out, size_t outlen) {
    for (int i = 1; argv[i] != nullptr; ++i) {
        if (strcmp(argv[i], "-c") == 0) {
            if (argv[i + 1] == nullptr) return nullptr;
            if (!join_args(argv, i + 1, out, outlen)) return nullptr;
            trim_in_place(out);
            size_t n = strlen(out);
            if (n >= 2 && ((out[0] == '\'' && out[n - 1] == '\'')
                           || (out[0] == '"' && out[n - 1] == '"'))) {
                out[n - 1] = '\0';
                memmove(out, out + 1, n - 1);
                trim_in_place(out);
            }
            return out;
        }
    }
    return nullptr;
}

// Runs the emulated invocation via orig_execvp(kExecSh, {sh, -c, payload}).
static int exec_emulated_c(const char *payload) {
    char *nargv[] = {const_cast<char *>(kExecSh), const_cast<char *>("-c"),
                     const_cast<char *>(payload), nullptr};
    ALOGD("ProcHook: exec -> %s -c '%s'", kExecSh, payload);
    nlog("exec decision: rewrite to %s -c '%s'", kExecSh, payload);
    return orig_execvp(kExecSh, nargv);
}

static int my_execvp(const char *path, char *const argv[]) {
    if (orig_execvp == nullptr) {
        // Should not happen (hook only installed together with orig capture).
        return -1;
    }
    if (!g_fake_root_enabled || path == nullptr || argv == nullptr || argv[0] == nullptr) {
        return orig_execvp(path, argv);
    }

    {
        char argbuf[1024];
        if (!join_args(argv, 0, argbuf, sizeof(argbuf))) {
            // argv too long for the buffer: argbuf holds a truncated prefix.
            // Mark the truncation explicitly - a silently lost payload (e.g. a
            // big `sh -c <script>`) hides exactly the invocation we need to see.
            size_t total = 0;
            for (int i = 0; argv[i] != nullptr; ++i) total += strlen(argv[i]) + 1;
            size_t used = strlen(argbuf);
            if (used + 48 < sizeof(argbuf)) {
                snprintf(argbuf + used, sizeof(argbuf) - used,
                         " ...<truncated, total=%d>", (int) total);
            }
        }
        nlog("execvp '%s' argv=[%s]", path, argbuf);
    }

    const char *b = exec_basename(path);

    // 1) Direct identity programs: `id`, `whoami`, `getenforce`, `which su`.
    if (!path_is_su_family(path)) {
        const char *canned = nullptr;
        if (strcmp(b, "id") == 0) {
            canned = kIdPrintf;
        } else if (strcmp(b, "whoami") == 0) {
            canned = kWhoamiPrintf;
        } else if (strcmp(b, "getenforce") == 0) {
            canned = kGetenforcePrintf;
        } else if (strcmp(b, "which") == 0 || strcmp(b, "whereis") == 0) {
            const char *last = nullptr;
            for (int i = 1; argv[i] != nullptr; ++i) last = argv[i];
            if (last != nullptr && arg_is_su_name(last)) canned = kWhichPrintf;
        } else if (strcmp(b, "sh") == 0 || strcmp(b, "mksh") == 0) {
            // Shell-wrapped probes: sh -c 'which su' / 'su -c id' / 'test -e ...'.
            char cmdbuf[1024];
            const char *ct = extract_shell_c(argv, cmdbuf, sizeof(cmdbuf));
            if (ct != nullptr) {
                canned = identity_canned(ct);
                if (canned == nullptr && strncmp(ct, "su", 2) == 0) {
                    // `su -c <x>` wrapped in a shell: emulate inner command.
                    char inner[1024];
                    bool done = false;
                    for (const char *p = ct + 2; !done && *p; ++p) {
                        if (p[0] == '-' && p[1] == 'c') {
                            const char *rest = p + 2;
                            while (*rest == ' ') rest++;
                            snprintf(inner, sizeof(inner), "%s", rest);
                            canned = identity_canned(inner);
                            done = true;
                            if (canned == nullptr) {
                                ALOGD("ProcHook: exec %s -> %s -c '%s' (inner)", path, kExecSh, inner);
                                char *nargv[] = {const_cast<char *>(kExecSh),
                                                 const_cast<char *>("-c"),
                                                 const_cast<char *>(inner), nullptr};
                                return orig_execvp(kExecSh, nargv);
                            }
                        }
                    }
                }
                if (canned == nullptr) {
                    // Existence probes: test -e/-f/-x <su path>, [ -x <su path> ], ls <su path>
                    const char *p = ct;
                    bool isTest = strncmp(p, "test ", 5) == 0;
                    bool isBracket = strncmp(p, "[ ", 2) == 0;
                    bool isLs = strncmp(p, "ls ", 3) == 0;
                    if (isBracket) p += 2;
                    else if (isTest) p += 5;
                    char probe[1024];
                    snprintf(probe, sizeof(probe), "%s", p);
                    trim_in_place(probe);
                    size_t plen = strlen(probe);
                    if (isBracket && plen >= 2 && probe[plen - 1] == ']') {
                        probe[plen - 1] = '\0';
                        trim_in_place(probe);
                    }
                    if (isTest || isBracket) {
                        char op[8] = {0};
                        char target[900] = {0};
                        if (sscanf(probe, "%7s %899s", op, target) == 2
                            && (strcmp(op, "-e") == 0 || strcmp(op, "-f") == 0
                                || strcmp(op, "-x") == 0)
                            && strchr(target, '/') != nullptr && arg_is_su_name(target)) {
                            ALOGD("ProcHook: exec %s -> true (probe '%s')", path, ct);
                            char *nargv[] = {const_cast<char *>(kExecSh),
                                             const_cast<char *>("-c"),
                                             const_cast<char *>("true"), nullptr};
                            return orig_execvp(kExecSh, nargv);
                        }
                    } else if (isLs) {
                        char op0[900];
                        char target[900] = {0};
                        if (sscanf(probe, "%899s %899s", op0, target) >= 1
                            && target[0] != '\0' && strchr(target, '/') != nullptr
                            && arg_is_su_name(target)) {
                            char payload[1100];
                            snprintf(payload, sizeof(payload), "printf '%s\\n'", target);
                            ALOGD("ProcHook: exec %s -> %s", path, payload);
                            char *nargv[] = {const_cast<char *>(kExecSh),
                                             const_cast<char *>("-c"),
                                             const_cast<char *>(payload), nullptr};
                            return orig_execvp(kExecSh, nargv);
                        }
                    }
                }
            }
        }
        if (canned != nullptr) {
            return exec_emulated_c(canned);
        }
        return orig_execvp(path, argv);
    }

    // 2) su family: magisk-style argument parsing.
    if (argv_has_flag(argv, "-v") || argv_has_flag(argv, "--version")) {
        return exec_emulated_c(kVersionPrintf);
    }
    if (argv_has_flag(argv, "-V") || argv_has_flag(argv, "--version-code")) {
        // libsu asks su -V for the numeric interface version code.
        return exec_emulated_c(kVersionCodePrintf);
    }

    char cmd[2048];
    bool haveCmd = false;
    // `su -c <cmd>` form
    for (int i = 1; argv[i] != nullptr; ++i) {
        if (strcmp(argv[i], "-c") == 0 || strcmp(argv[i], "--command") == 0) {
            if (argv[i + 1] != nullptr) {
                haveCmd = join_args(argv, i + 1, cmd, sizeof(cmd));
            }
            break;
        }
        if (strncmp(argv[i], "--command=", 10) == 0) {
            snprintf(cmd, sizeof(cmd), "%s", argv[i] + 10);
            haveCmd = true;
            break;
        }
    }
    // Positional form: `su [-] [--] [0|root|-u N|--user N] <cmd...>`
    if (!haveCmd) {
        int start = -1;
        for (int i = 1; argv[i] != nullptr; ++i) {
            const char *a = argv[i];
            if (strcmp(a, "-") == 0 || strcmp(a, "--") == 0 || strcmp(a, "0") == 0
                || strcmp(a, "root") == 0) {
                continue;
            }
            if (a[0] == '-' && a[1] != '\0') {
                if ((strcmp(a, "-u") == 0 || strcmp(a, "--user") == 0)
                    && all_digits(argv[i + 1])) {
                    ++i;
                }
                continue;
            }
            start = i;
            break;
        }
        if (start >= 0) {
            haveCmd = join_args(argv, start, cmd, sizeof(cmd));
        }
    }

    if (!haveCmd) {
        // Bare `su`: interactive proxy shell (real execution of stdin lines).
        char proxy[512];
        bool useProxy = !g_root_fs_path.empty();
        if (useProxy) {
            snprintf(proxy, sizeof(proxy), "%s/scripts/su_proxy.sh", g_root_fs_path.c_str());
            useProxy = access(proxy, R_OK) == 0;
        }
        if (useProxy) {
            ALOGD("ProcHook: exec %s -> %s %s (proxy shell)", path, kExecSh, proxy);
            nlog("exec decision: bare su -> proxy shell %s", proxy);
            char *nargv[] = {const_cast<char *>(kExecSh), const_cast<char *>(proxy), nullptr};
            return orig_execvp(kExecSh, nargv);
        }
        return exec_emulated_c(kBareSuFallback);
    }

    trim_in_place(cmd);
    const char *canned = identity_canned(cmd);
    if (canned != nullptr && strcmp(canned, "exit 0") != 0) {
        return exec_emulated_c(canned);
    }
    // Arbitrary command: real execution inside the sandbox (working root).
    return exec_emulated_c(cmd);
}

// NOTE: access/faccessat/stat/lstat/getuid/geteuid are intentionally NOT
// inline-hooked here anymore. In bionic they are all thin syscall stubs of
// only a few instructions (same class as getuid/geteuid before them), i.e.
// too small to safely host a Dobby trampoline on every device/ABI; a botched
// patch there does not crash cleanly, it silently corrupts neighbouring libc
// code and manifests as a frozen black screen / ANR hang for the affected
// virtual app (typically heavier ones that hammer stat/access from many
// native threads). Java-level path/uid spoofing for virtual apps is already
// handled in OsStub / UnixFileSystemHook / IO redirect rules, which is
// sufficient for anything going through the JVM. GameGuardian keeps working
// through the remaining open/openat hooks below (cmdline spoofing + /proc
// reads), which are large enough functions to hook safely.
//
// execvp IS hooked (see below): Runtime.exec()/ProcessBuilder on Android 7+
// go through java.lang.UNIXProcess.forkAndExec whose forked child calls
// libc execvp() directly - the Java-side OsStub execve/posix_spawn hooks
// never see these calls, which is exactly why Root Checker found the fake su
// files but could never execute them ("not properly installed"). execvp is a
// real function (PATH-search loop), large enough for a trampoline, and the
// hook is installed lazily only for root-emulated processes. The rewrite
// logic mirrors RootShellEmulator.java; it must stay malloc-free because it
// runs in the forked child between fork() and exec() where the malloc lock
// may be held by another (non-existent in the child) thread.
static void hook_sym(const char *name, void *replace, void **orig) {
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGE("ProcHook: xdl_open failed for %s", name);
        return;
    }
    void *target = xdl_dsym(handle, name, nullptr);
    if (!target) {
        target = xdl_sym(handle, name, nullptr);
    }
    if (target) {
        if (DobbyHook(target, replace, orig) == 0) {
            ALOGD("ProcHook: hooked %s", name);
            nlog("hook %s installed", name);
        } else {
            ALOGE("ProcHook: DobbyHook failed for %s", name);
            nlog("hook %s FAILED (DobbyHook != 0)", name);
        }
    } else {
        ALOGE("ProcHook: symbol not found: %s", name);
        nlog("hook %s FAILED (symbol not found)", name);
    }
    xdl_close(handle);
}

static void install_hooks_once() {
    // Only open/openat/execvp are hooked: variadic / real functions with
    // enough instructions to host a Dobby trampoline safely. Everything else
    // (access/faccessat/stat/lstat/getuid/geteuid) is deliberately left
    // alone - see the NOTE above.
    hook_sym("open", (void *) my_open, (void **) &orig_open);
    hook_sym("openat", (void *) my_openat, (void **) &orig_openat);
    hook_sym("execvp", (void *) my_execvp, (void **) &orig_execvp);
}

// Installs the path-redirection hooks at most once per process, and only
// when actually needed (root emulation or proc-name spoofing active). Apps
// that never enable root never touch these libc inline hooks at all.
static void ensure_hooks_installed() {
    std::call_once(g_hooks_once, install_hooks_once);
}

void ProcHook::init(JNIEnv *env) {
    // Deferred: see ensure_hooks_installed(). Nothing to do at process boot
    // time anymore, so non-rooted virtual apps are completely unaffected by
    // this module.
}

// ---------------- JNI bridge ----------------

void ProcHook::setFakeRootEnabled(JNIEnv *env, jclass clazz, jboolean enabled) {
    g_fake_root_enabled = enabled == JNI_TRUE;
    ALOGD("ProcHook: fake root %s", g_fake_root_enabled ? "enabled" : "disabled");
    nlog("fake root %s", g_fake_root_enabled ? "ENABLED" : "disabled");
    if (g_fake_root_enabled) {
        // Only patch libc for processes that actually asked for root emulation.
        ensure_hooks_installed();
    }
}

void ProcHook::setRootFsPath(JNIEnv *env, jclass clazz, jstring rootFsPath) {
    if (rootFsPath == nullptr) {
        std::lock_guard<std::mutex> guard(g_lock);
        g_root_fs_path.clear();
        return;
    }
    const char *c = env->GetStringUTFChars(rootFsPath, nullptr);
    if (c != nullptr) {
        std::lock_guard<std::mutex> guard(g_lock);
        g_root_fs_path = c;
        env->ReleaseStringUTFChars(rootFsPath, c);
    }
}

void ProcHook::setExecLogPath(JNIEnv *env, jclass clazz, jstring logPath) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (logPath == nullptr) {
        g_exec_log_path[0] = '\0';
        return;
    }
    const char *c = env->GetStringUTFChars(logPath, nullptr);
    if (c == nullptr) return;
    snprintf(g_exec_log_path, sizeof(g_exec_log_path), "%s", c);
    env->ReleaseStringUTFChars(logPath, c);
}

void ProcHook::addProcMapping(JNIEnv *env, jclass clazz, jint pid, jstring virtualName, jstring spoofPath) {
    // GameGuardian's own process needs the open/openat redirection in place to
    // see spoofed /proc/<pid>/cmdline entries for other sandboxed apps, even
    // if GG itself does not have root emulation enabled.
    ensure_hooks_installed();
    if (spoofPath == nullptr) return;
    const char *path = env->GetStringUTFChars(spoofPath, nullptr);
    if (path == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        g_proc_paths[(int) pid] = path;
    }
    if (virtualName != nullptr) {
        const char *name = env->GetStringUTFChars(virtualName, nullptr);
        if (name != nullptr) {
            ALOGD("ProcHook: pid %d -> %s", pid, name);
            env->ReleaseStringUTFChars(virtualName, name);
        }
    }
    env->ReleaseStringUTFChars(spoofPath, path);
}

void ProcHook::removeProcMapping(JNIEnv *env, jclass clazz, jint pid) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_proc_paths.erase((int) pid);
}
