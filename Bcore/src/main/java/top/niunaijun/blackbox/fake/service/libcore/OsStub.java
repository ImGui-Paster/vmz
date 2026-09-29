package top.niunaijun.blackbox.fake.service.libcore;

import android.os.Process;

import java.lang.reflect.Method;

import black.libcore.io.BRLibcore;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.env.RootConfig;
import top.niunaijun.blackbox.core.env.RootShellEmulator;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.utils.DiagnosticLogger;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.RootLogger;
import top.niunaijun.blackbox.utils.Slog;


public class OsStub extends ClassInvocationStub {
    public static final String TAG = "OsStub";
    private Object mBase;

    public OsStub() {
        mBase = BRLibcore.get().os();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRLibcore.get()._set_os(proxyInvocation);
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public boolean isBadEnv() {
        return BRLibcore.get().os() != getProxyInvocation();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null)
                    continue;
                if (args[i] instanceof String && ((String) args[i]).startsWith("/")) {
                    String orig = (String) args[i];
                    args[i] = IOCore.get().redirectPath(orig);


                }
            }
        }
        return super.invoke(proxy, method, args);
    }

    @ProxyMethod("getuid")
    public static class getuid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callUid = (int) method.invoke(who, args);
            // Emulated root: the sandbox process reports uid 0.
            if (RootConfig.isRootEnabledForCurrentProcess()) {
                RootLogger.sample(TAG, "getuid -> 0 (real=" + callUid + ")", 0, 5, 100);
                return 0;
            }
            return getFakeUid(callUid);
        }
    }

    @ProxyMethod("geteuid")
    public static class geteuid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callUid = (int) method.invoke(who, args);
            if (RootConfig.isRootEnabledForCurrentProcess()) {
                RootLogger.sample(TAG, "geteuid -> 0 (real=" + callUid + ")", 1, 5, 100);
                return 0;
            }
            return getFakeUid(callUid);
        }
    }

    @ProxyMethod("getgid")
    public static class getgid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callGid = (int) method.invoke(who, args);
            if (RootConfig.isRootEnabledForCurrentProcess()) {
                return 0;
            }
            return callGid;
        }
    }

    @ProxyMethod("getegid")
    public static class getegid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callGid = (int) method.invoke(who, args);
            if (RootConfig.isRootEnabledForCurrentProcess()) {
                return 0;
            }
            return callGid;
        }
    }

    @ProxyMethod("stat")
    public static class stat extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length >= 1 && args[0] instanceof String
                    && RootConfig.isRootEnabledForCurrentProcess()
                    && RootShellEmulator.isSuPath((String) args[0])) {
                RootLogger.sample(TAG, "Os.stat su path (st_uid=0): " + args[0], 2, 10, 200);
                DiagnosticLogger.i("RootProbe", "Os.stat su path: " + args[0]);
            }
            Object invoke = null;
            try {
                invoke = method.invoke(who, args);
            } catch (Throwable e) {
                throw e.getCause();
            }
            int uid = RootConfig.isRootEnabledForCurrentProcess() ? 0 : getFakeUid(-1);
            Reflector.with(invoke).field("st_uid").set(uid);
            if (RootConfig.isRootEnabledForCurrentProcess()) {
                try {
                    Reflector.with(invoke).field("st_gid").set(0);
                } catch (Throwable ignored) {
                }
            }
            return invoke;
        }
    }

    /**
     * Virtual shell / root emulation.
     *
     * Android 8+ {@code Runtime.exec}/{@code ProcessBuilder} use
     * {@code Os.posix_spawn}, not {@code fork}+{@code execve}. The previous
     * hook required {@code sInForkedChild} and never ran for Root Checker.
     * Redirected su binaries also live on noexec app-data, so we rewrite
     * su/id/whoami/which-su/getenforce to {@code /system/bin/sh} instead of
     * executing the fake file.
     */
    @ProxyMethod("execve")
    public static class Execve extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            applyRootShellRewrite(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethods({"posix_spawn", "posix_spawnp"})
    public static class PosixSpawn extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            applyRootShellRewrite(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("access")
    public static class Access extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length >= 1 && args[0] instanceof String
                    && RootConfig.isRootEnabledForCurrentProcess()
                    && RootShellEmulator.isSuPath((String) args[0])) {
                RootLogger.sample(TAG, "Os.access su path -> allowed: " + args[0], 3, 10, 200);
                DiagnosticLogger.i("RootProbe", "Os.access su path: " + args[0]);
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class || ret == Boolean.class) {
                    return true;
                }
                return null;
            }
            return method.invoke(who, args);
        }
    }

    /**
     * Mutates {@code args} in place. Works for both {@code execve(path, argv, envp)}
     * and {@code posix_spawn(fd, path, actions, attr, argv, envp)}.
     */
    private static void applyRootShellRewrite(Object[] args) {
        if (args == null || !RootConfig.isRootEnabledForCurrentProcess()) {
            return;
        }
        int pathIdx = -1;
        int argvIdx = -1;
        for (int i = 0; i < args.length; i++) {
            if (pathIdx < 0 && args[i] instanceof String) {
                pathIdx = i;
            } else if (pathIdx >= 0 && argvIdx < 0 && args[i] instanceof String[]) {
                argvIdx = i;
                break;
            }
        }
        if (pathIdx < 0) {
            return;
        }
        String path = (String) args[pathIdx];
        String[] argv = argvIdx >= 0 ? (String[]) args[argvIdx] : new String[0];
        String[] rewritten = RootShellEmulator.rewrite(path, argv);
        if (rewritten == null || rewritten.length == 0) {
            return;
        }
        args[pathIdx] = rewritten[0];
        if (argvIdx >= 0) {
            args[argvIdx] = rewritten;
        }
        Slog.i(TAG, "root-shell rewrite " + path + " -> " + java.util.Arrays.toString(rewritten));
        DiagnosticLogger.i(TAG, "root-shell rewrite " + path + " -> " + java.util.Arrays.toString(rewritten));
    }

    private static int getFakeUid(int callUid) {
        if (callUid > 0 && callUid <= Process.FIRST_APPLICATION_UID)
            return callUid;

        if (BActivityThread.isThreadInit() && BActivityThread.currentActivityThread().isInit()) {
            return BActivityThread.getBAppId();
        } else {
            return BlackBoxCore.getHostUid();
        }
    }
}
