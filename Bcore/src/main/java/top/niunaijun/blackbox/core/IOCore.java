package top.niunaijun.blackbox.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.env.RootConfig;
import top.niunaijun.blackbox.core.env.RootShellEmulator;
import top.niunaijun.blackbox.fake.service.GameGuardianCompat;
import top.niunaijun.blackbox.utils.DiagnosticLogger;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.RootLogger;
import top.niunaijun.blackbox.utils.TrieTree;


@SuppressLint("SdCardPath")
public class IOCore {
    public static final String TAG = "IOCore";

    private static final IOCore sIOCore = new IOCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> sCachePackageRedirect = new HashMap<>();

    public static IOCore get() {
        return sIOCore;
    }

    
    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        NativeCore.addIORule(origPath, redirectPath);
    }

    public void addBlackRedirect(String path) {
        if (TextUtils.isEmpty(path))
            return;
        sBlackTree.add(path);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        if (path.contains("/blackbox/")) {
            return path;
        }
        // Never redirect host diagnostic logs into the sandbox tree.
        if (top.niunaijun.blackbox.utils.DiagnosticLogger.isProtectedPath(path)) {
            return path;
        }
        // Virtual process name mapping over /proc/<pid>/cmdline (GameGuardian process discovery).
        if (path.startsWith("/proc/")) {
            String procMapped = redirectProcPath(path);
            if (procMapped != null) {
                return procMapped;
            }
        }
        String search = sBlackTree.search(path);
        if (!TextUtils.isEmpty(search))
            return search;

        
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(mRedirectMap.get(key)));

        return path;
    }

    /**
     * Maps /proc/<pid>/cmdline of other sandbox processes to their virtual names.
     * /proc/<pid>/mem, maps and everything else pass through untouched so that
     * GameGuardian memory access keeps working.
     */
    private String redirectProcPath(String path) {
        try {
            if (!path.endsWith("/cmdline")) {
                return null;
            }
            String mid = path.substring("/proc/".length(), path.length() - "/cmdline".length());
            int pid = Integer.parseInt(mid);
            String spoof = GameGuardianCompat.getSpoofFilePath(pid);
            if (spoof != null) {
                return spoof;
            }
        } catch (NumberFormatException ignored) {
        } catch (Throwable ignored) {
        }
        return null;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;

        
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(rule.get(key)));

        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    

    public void enableRedirect(Context context) {
        top.niunaijun.blackbox.utils.DiagnosticLogger.Scope scope =
                top.niunaijun.blackbox.utils.DiagnosticLogger.scope("IOCore.enableRedirect");
        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, BlackBoxCore.getUserId());
            int systemUserId = BlackBoxCore.getHostUserId();
            RootLogger.decision(TAG, "enableRedirect begin: pkg=" + packageName
                    + " user=" + BlackBoxCore.getUserId() + " hostUserId=" + systemUserId);
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);

            
            File profilesRoot = new File(BEnvironment.getVirtualRoot(), "profiles");
            FileUtils.mkdirs(profilesRoot.getAbsolutePath());
            
            rule.put("/data/misc/profiles", profilesRoot.getAbsolutePath());

            File profilesCurDir = new File(profilesRoot, String.format("cur/%d/%s", BlackBoxCore.getUserId(), packageName));
            File profilesRefDir = new File(profilesRoot, String.format("ref/%d/%s", BlackBoxCore.getUserId(), packageName));
            FileUtils.mkdirs(profilesCurDir.getAbsolutePath());
            FileUtils.mkdirs(profilesRefDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/cur/%d/%s", BlackBoxCore.getUserId(), packageName), profilesCurDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/ref/%d/%s", BlackBoxCore.getUserId(), packageName), profilesRefDir.getAbsolutePath());

            if (BlackBoxCore.getContext().getExternalCacheDir() != null && context.getExternalCacheDir() != null) {
                File external = BEnvironment.getExternalUserDir(BlackBoxCore.getUserId());

                
                rule.put("/sdcard", external.getAbsolutePath());
                rule.put(String.format("/storage/emulated/%d", systemUserId), external.getAbsolutePath());

                blackRule.add("/sdcard/Pictures");
                blackRule.add(String.format("/storage/emulated/%d/Pictures", systemUserId));
            }

            // Virtual /data/local/tmp: always available inside the sandbox
            // (custom script execution module target directory).
            File localTmp = BEnvironment.getLocalTmpDir();
            FileUtils.mkdirs(localTmp.getAbsolutePath());
            rule.put("/data/local/tmp", localTmp.getAbsolutePath());

            // Per-app Root Management: explicit DenyList wins over the global switch.
            RootConfig.init(packageName, BlackBoxCore.getUserId());
            if (!RootConfig.isRootEnabledForCurrentProcess()) {
                // Clean non-root environment simulation.
                hideRoot(rule);
                RootLogger.decision(TAG, "root DISABLED for " + packageName
                        + " -> hideRoot applied (clean non-root env)");
            } else {
                // Emulated root environment inside the sandbox.
                setupVirtualRoot(rule);
                RootLogger.decision(TAG, "root ENABLED for " + packageName
                        + " -> virtual rootfs + su emulation applied");
            }
            proc(rule);
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        for (String s : blackRule) {
            get().addBlackRedirect(s);
        }
        NativeCore.enableIO();
        if (RootConfig.isRootEnabledForCurrentProcess()) {
            NativeCore.setFakeRootEnabled(true);
            RootLogger.decision(TAG, "native fake-root ENABLED (libc open/openat su-path resolution)");
        } else {
            RootLogger.decision(TAG, "native fake-root left OFF (root disabled)");
        }
        try {
            GameGuardianCompat.onVirtualAppBound(context, packageName, BlackBoxCore.getUserId(),
                    RootConfig.isRootEnabledForCurrentProcess());
        } catch (Throwable ignored) {
        }
        try {
            scope.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Emulated root environment: creates fake root artifacts inside the sandbox
     * rootfs and maps the well-known su paths onto them, so virtual apps with
     * root permission can "see" root. Execution of these artifacts is
     * intercepted at the shell layer (OsStub posix_spawn/execve rewrite via
     * RootShellEmulator to /system/bin/sh) and emulated without executing the
     * fake su from noexec app-data. No real host rooting is involved.
     */
    private void setupVirtualRoot(Map<String, String> rule) {
        File rootFs = BEnvironment.getRootFsDir();
        String rootFsPath = rootFs.getAbsolutePath();
        String[] suPaths = {
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
        };
        for (String suPath : suPaths) {
            String relative = suPath.startsWith("/") ? suPath.substring(1) : suPath;
            File fake = new File(rootFs, relative);
            ensureFakeBinary(fake);
            rule.put(suPath, fake.getAbsolutePath());
        }
        String[] markerPaths = {
                "/system/app/Superuser.apk",
                "/system/bin/magisk",
                "/system/xbin/magisk",
                "/sbin/magisk",
                "/data/adb/magisk",
                "/system/xbin/busybox",
        };
        for (String marker : markerPaths) {
            String relative = marker.startsWith("/") ? marker.substring(1) : marker;
            File fake = new File(rootFs, relative);
            ensureFakeFile(fake);
            rule.put(marker, fake.getAbsolutePath());
        }
        ensureSuProxyScript();
        try {
            NativeCore.setRootFsPath(rootFsPath);
        } catch (Throwable ignored) {
        }
        // Native-side exec/open decision log: Runtime.exec()/ProcessBuilder never
        // reaches the Java RootLogger (UNIXProcess.forkAndExec -> libc execvp),
        // so ProcHook mirrors its decisions into root_native_<proc>.log.
        try {
            File execLog = new File(DiagnosticLogger.getLogDir(),
                    "root_native_" + DiagnosticLogger.getProcLabel() + ".log");
            NativeCore.setExecLogPath(execLog.getAbsolutePath());
        } catch (Throwable t) {
            RootLogger.w(TAG, "setExecLogPath failed: " + t);
        }
        // Spoof /proc/self/status so the sandbox process reports uid 0.
        try {
            File statusSpoof = new File(BEnvironment.getProcSpoofDir(), "self_status_" + Process.myPid());
            writeFakeSelfStatus(statusSpoof);
            if (statusSpoof.exists()) {
                rule.put("/proc/self/status", statusSpoof.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
        RootLogger.decision(TAG, "setupVirtualRoot done: rootfs=" + rootFsPath
                + " suRules=" + suPaths.length + " markerRules=" + markerPaths.length
                + " proxyScript=" + new File(rootFs, RootShellEmulator.SU_PROXY_SCRIPT_REL).getAbsolutePath()
                + " statusSpoofPid=" + Process.myPid());
    }

    /**
     * The interactive proxy su shell executed for bare {@code su} invocations
     * (see RootShellEmulator#bareSuInvocation). Answers identity probes with
     * emulated root output and executes every other stdin line for real.
     * The full conversation is appended to {@code root_su_session.log} in the
     * diagnostics directory so a failing root check can be diagnosed from the
     * exact su session (what the app sent, and how the session ended).
     */
    private static void ensureSuProxyScript() {
        try {
            File rootFs = BEnvironment.getRootFsDir();
            File script = new File(rootFs, RootShellEmulator.SU_PROXY_SCRIPT_REL);
            String sessionLogPath;
            try {
                // Same directory as bb_diag/root_diag/root_native logs - readable
                // by the user from /storage/emulated/0/Android/data/<host>/files/.
                sessionLogPath = new File(DiagnosticLogger.getLogDir(),
                        "root_su_session.log").getAbsolutePath();
            } catch (Throwable t) {
                // Fallback: inside the rootfs (app-private, still useful via bugreport).
                sessionLogPath = new File(rootFs, "logs/su_session.log").getAbsolutePath();
            }
            String content = buildSuProxyScript(sessionLogPath);
            boolean needWrite = true;
            if (script.exists()) {
                // Re-write when the existing file is not ours (size heuristic:
                // missing body means an old/partial install).
                needWrite = script.length() != content.length();
            }
            if (needWrite) {
                script.getParentFile().mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(script)) {
                    fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            script.setReadable(true, false);
        } catch (Throwable t) {
            RootLogger.w("IOCore", "ensureSuProxyScript failed: " + t);
        }
    }

    static String buildSuProxyScript(String sessionLogPath) {
        return "#!/system/bin/sh\n"
                + "# VMREWORK virtual su - interactive proxy root shell.\n"
                + "# Identity probes are answered with emulated root output; every other\n"
                + "# stdin line is executed for real inside the sandbox. Exits on stdin EOF,\n"
                + "# `exit`, or after ~15 min idle (450 x 2 s read timeouts; root apps such as libsu-based loaders keep the su session open far longer than a one-shot root checker).\n"
                + "# Every session is logged to the diagnostics directory.\n"
                + "# IMPORTANT: ignore SIGPIPE. Some root checkers (e.g. Elixir Loader) spawn\n"
                + "# bare `su` and close/never read our stdout; without this the banner write\n"
                + "# kills the shell before it can answer stdin or exit cleanly on EOF.\n"
                + "trap '' PIPE\n"
                + "ID='uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0'\n"
                + "LOG=\"" + sessionLogPath + "\"\n"
                + "echo \"===== su session start pid=$$ ppid=$PPID args=[$*]\" >> \"$LOG\" 2>/dev/null\n"
                + "printf '%s\\n' \"$ID\"\n"
                + "echo \"<< banner: $ID\" >> \"$LOG\" 2>/dev/null\n"
                + "t=0\n"
                + "while true; do\n"
                + "  if read -r -t 2 line; then\n"
                + "    t=0\n"
                + "    echo \">> [$line]\" >> \"$LOG\" 2>/dev/null\n"
                + "    case \"$line\" in\n"
                + "      exit|logout|quit) echo \"<< exit command\" >> \"$LOG\" 2>/dev/null; break;;\n"
                + "      'id -u'|'id -un'|'id -u -n'|'id -n -u') printf '0\\n';;\n"
                + "      'id'|'/system/bin/id'|'/system/xbin/id') printf '%s\\n' \"$ID\";;\n"
                + "      'whoami'|'/system/bin/whoami'|'/system/xbin/whoami') printf 'root\\n';;\n"
                + "      'getenforce'|'/system/bin/getenforce') printf 'Enforcing\\n';;\n"
                + "      'which su'|'which magisk'|'which daemonsu'|'whereis su') printf '/system/xbin/su\\n';;\n"
                + "      '') ;;\n"
                + "      *) eval \"$line\" ;;\n"
                + "    esac\n"
                + "  else\n"
                + "    t=$((t+1))\n"
                + "    if [ \"$t\" -ge 450 ]; then\n"
                + "      echo \"<< idle timeout\" >> \"$LOG\" 2>/dev/null\n"
                + "      break\n"
                + "    fi\n"
                + "  fi\n"
                + "done\n"
                + "echo \"===== su session end pid=$$ t=$t\" >> \"$LOG\" 2>/dev/null\n"
                + "exit 0\n";
    }

    private void ensureFakeBinary(File file) {
        try {
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write("#!/system/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                file.setExecutable(true, false);
                file.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureFakeFile(File file) {
        try {
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    private void writeFakeSelfStatus(File target) {
        try {
            java.io.File real = new java.io.File("/proc/self/status");
            byte[] raw = new byte[8192];
            int len;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(real)) {
                while ((len = fis.read(raw)) > 0) {
                    bos.write(raw, 0, len);
                }
            }
            String content = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            for (String line : content.split("\n")) {
                if (line.startsWith("Uid:")) {
                    sb.append("Uid:\t0\t0\t0\t0\n");
                } else if (line.startsWith("Gid:")) {
                    sb.append("Gid:\t0\t0\t0\t0\n");
                } else {
                    sb.append(line).append('\n');
                }
            }
            target.getParentFile().mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                fos.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private void hideRoot(Map<String, String> rule) {
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
    }

    private void proc(Map<String, String> rule) {
        int appPid = BlackBoxCore.getAppPid();
        int pid = Process.myPid();
        String selfProc = "/proc/self/";
        String proc = "/proc/" + pid + "/";

        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline", cmdline);
        rule.put(selfProc + "cmdline", cmdline);
    }
}
