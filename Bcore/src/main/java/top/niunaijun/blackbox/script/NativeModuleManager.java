package top.niunaijun.blackbox.script;

import android.content.Context;
import android.os.Process;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.DiagnosticLogger;
import java.io.*;
import java.util.*;

/** Cooperative loading in the selected guest process, NOT host root or a Zygisk implementation. */
public final class NativeModuleManager {
    private static final Set<String> attempted = new HashSet<>();
    private NativeModuleManager() {}

    private static File registry() { return new File(BEnvironment.getSystemDir(), "native_modules"); }
    private static File ruleFile(String pkg, int user) throws IOException {
        if (!ModuleFiles.validPackage(pkg) || user < 0) throw new IOException("Invalid target");
        return new File(registry(), user + "_" + pkg + ".properties");
    }
    private static File statusFile(String pkg, int user) throws IOException {
        return new File(registry(), ruleFile(pkg, user).getName() + ".status");
    }

    /** Host UI only. One explicitly approved module per package/user; takes effect on restart. */
    public static synchronized String enable(File source, String pkg, int user, String phase) throws Exception {
        requireHost();
        ruleFile(pkg, user);
        if (!"before_create".equals(phase) && !"before_on_create".equals(phase) && !"after_on_create".equals(phase))
            throw new IOException("Unknown load phase");
        if (pkg.equals(BlackBoxCore.getHostPkg()) || !BlackBoxCore.get().isInstalled(pkg, user))
            throw new IOException("Target is not an installed guest application");
        File root = checkedSource(source);
        ModuleFiles.Spec spec = ModuleFiles.inspect(root);
        if (spec.adapter.equals("magicpro-payload") && !"1".equals(spec.targets.getProperty(pkg)))
            throw new IOException("Package is not enabled in inject.conf");
        Properties rule = new Properties();
        rule.setProperty("source", root.getCanonicalPath());
        rule.setProperty("package", pkg);
        rule.setProperty("process", pkg); // Main guest process only. Never match by PID/name substring.
        rule.setProperty("user", Integer.toString(user));
        rule.setProperty("phase", phase);
        rule.setProperty("sha256", ModuleFiles.sha256(spec.library));
        rule.setProperty("adapter", spec.adapter);
        rule.setProperty("id", spec.id);
        rule.setProperty("revision", UUID.randomUUID().toString());
        ModuleFiles.write(ruleFile(pkg, user), rule);
        report(pkg, user, "ARMED", "Restart the target guest to load; existing processes are unchanged", rule);
        return "ARMED: " + pkg + " / user " + user + " / " + spec.adapter
                + " / " + phase + "\nRestart the target inside BlackBox. Library load is not proof that its features work.\n";
    }

    public static synchronized String disable(String pkg, int user) throws Exception {
        requireHost();
        File rule = ruleFile(pkg, user);
        if (rule.exists() && !rule.delete()) throw new IOException("Cannot remove activation rule");
        report(pkg, user, "DISABLED", "Restart the guest to unload already mapped native code", new Properties());
        return "DISABLED: " + pkg + " / user " + user + ". Restart the guest; native code is not unloaded live.\n";
    }

    public static String status(String pkg, int user) throws Exception {
        requireHost();
        File file = statusFile(pkg, user);
        if (!file.isFile()) return "No native-module status for " + pkg + " / user " + user + "\n";
        Properties p = ModuleFiles.read(file);
        StringBuilder s = new StringBuilder("Last recorded attempt (not a live health check):\n");
        for (String k : new String[]{"state", "package", "user", "process", "pid", "timestamp", "adapter", "phase", "revision", "sha256", "detail"})
            s.append(k).append('=').append(p.getProperty(k, "")).append('\n');
        return s.toString();
    }

    private static void requireHost() {
        if (!BlackBoxCore.get().isMainProcess()) throw new SecurityException("Activation is controlled by host UI only");
    }

    private static File checkedSource(File source) throws IOException {
        File root = source.getCanonicalFile();
        String imported = BEnvironment.getLocalTmpDir().getCanonicalPath() + File.separator;
        if (!root.isDirectory() || !root.getPath().startsWith(imported)) throw new IOException("Module must be imported first");
        return root;
    }

    /** Called by Entry only from actual BActivityThread guest lifecycle callbacks. */
    public static void load(Context context, String pkg, String process, int user, String phase) {
        Properties rule = new Properties();
        try {
            if (context == null || !BlackBoxCore.get().isBlackProcess()
                    || BActivityThread.getAppConfig() == null
                    || !Objects.equals(pkg, BActivityThread.getAppPackageName())
                    || !Objects.equals(process, BActivityThread.getAppProcessName())
                    || user != BActivityThread.getUserId()) return;
            File file = ruleFile(pkg, user);
            if (!file.isFile()) return;
            rule = ModuleFiles.read(file);
            if (!ModuleFiles.matches(rule, pkg, process, user) || !phase.equals(rule.getProperty("phase"))) return;
            synchronized (attempted) {
                if (!attempted.add(user + ":" + pkg + ":" + process)) return;
            }
            report(pkg, user, "PREPARING", "Validating source and process ABI", rule);
            ModuleFiles.Spec spec = ModuleFiles.inspect(checkedSource(new File(rule.getProperty("source"))));
            if (!spec.adapter.equals(rule.getProperty("adapter")) || !spec.id.equals(rule.getProperty("id")))
                throw new IOException("Module manifest changed; enable it again");
            int required = BlackBoxCore.is64Bit() ? 2 : 1;
            if (ModuleFiles.elfClass(spec.library) != required) throw new IOException("ABI_MISMATCH: library and guest process differ");
            if (!ModuleFiles.sha256(spec.library).equals(rule.getProperty("sha256")))
                throw new IOException("HASH_MISMATCH: payload changed after approval");
            if (spec.adapter.equals("magicpro-payload") && !"1".equals(spec.targets.getProperty(pkg)))
                throw new IOException("Target is disabled in inject.conf");
            File appFiles = BEnvironment.getDataFilesDir(pkg, user);
            ModuleFiles.mkdir(appFiles);
            File destination = ModuleFiles.child(appFiles, spec.adapter.equals("magicpro-payload")
                    ? "Magic/libPUBGM.so" : "NativeModules/" + spec.id + "/" + rule.getProperty("sha256") + "/payload.so");
            ModuleFiles.mkdir(destination.getParentFile());
            if (spec.adapter.equals("magicpro-payload")) {
                for (String name : new String[]{"kami.conf", "features.conf"}) {
                    File config = ModuleFiles.child(spec.root, name);
                    if (config.isFile()) {
                        if (config.length() > 65536) throw new IOException("Config too large: " + name);
                        copyAtomic(config, ModuleFiles.child(appFiles, "Magic/" + name));
                    } else if (name.equals("features.conf")) {
                        Properties defaults = new Properties();
                        for (String key : new String[]{"esp_line", "esp_bone", "esp_name", "esp_distance", "esp_health", "esp_loot", "esp_ignore_bot", "feat_auto_pickup", "feat_pickup_range", "feat_beautify", "feat_accel", "feat_chase", "feat_guard"}) defaults.setProperty(key, "0");
                        for (String key : new String[]{"esp_count", "feat_peek_check", "feat_crouch_check"}) defaults.setProperty(key, "1");
                        defaults.setProperty("pickup_dist_m", "2.6"); defaults.setProperty("accel_speed", "80"); defaults.setProperty("guard_dist", "25");
                        File fallback = ModuleFiles.child(appFiles, "Magic/features.conf");
                        if (!fallback.exists()) ModuleFiles.write(fallback, defaults);
                    }
                }
            }
            copyAtomic(spec.library, destination);
            if (!ModuleFiles.sha256(destination).equals(rule.getProperty("sha256")))
                throw new IOException("Copied payload hash mismatch");
            report(pkg, user, "LOAD_STARTED", destination.getAbsolutePath(), rule);
            // Executes in the guest's address space. Native faults cannot be caught by Java.
            System.load(destination.getAbsolutePath());
            report(pkg, user, "LIBRARY_LOADED", "System.load returned; payload functionality and authorization are unverified", rule);
        } catch (Throwable error) {
            try { report(pkg, user, "LOAD_FAILED", error.getClass().getSimpleName() + ": " + error.getMessage(), rule); }
            catch (Throwable ignored) { DiagnosticLogger.w("NativeModules", "Cannot persist load failure: " + error); }
        }
    }

    private static void copyAtomic(File from, File to) throws IOException {
        ModuleFiles.mkdir(to.getParentFile());
        File tmp = File.createTempFile(".module-", ".tmp", to.getParentFile());
        try {
            try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(tmp)) {
                ModuleFiles.copy(in, out); out.getFD().sync();
            }
            if (!tmp.renameTo(to)) throw new IOException("Cannot replace " + to);
        } finally { tmp.delete(); }
    }

    private static void report(String pkg, int user, String state, String detail, Properties rule) throws IOException {
        Properties p = new Properties();
        for (String key : new String[]{"adapter", "phase", "revision", "sha256", "process"})
            p.setProperty(key, rule.getProperty(key, ""));
        p.setProperty("state", state); p.setProperty("detail", detail == null ? "" : detail);
        p.setProperty("package", pkg); p.setProperty("user", Integer.toString(user));
        p.setProperty("pid", Integer.toString(Process.myPid()));
        p.setProperty("timestamp", Long.toString(System.currentTimeMillis()));
        ModuleFiles.write(statusFile(pkg, user), p);
        DiagnosticLogger.i("NativeModules", state + " pkg=" + pkg + " user=" + user
                + " pid=" + Process.myPid() + " " + p.getProperty("detail"));
    }
}
