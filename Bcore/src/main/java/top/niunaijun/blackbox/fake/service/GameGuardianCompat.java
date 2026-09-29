package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.fake.frameworks.BRootManager;
import top.niunaijun.blackbox.utils.RootLogger;
import top.niunaijun.blackbox.utils.Slog;

/**
 * GameGuardian (GG) compatibility layer.
 *
 * Provides:
 *  - Detection of GameGuardian running as a virtual app inside the sandbox.
 *  - Automatic environment preparation: emulated root, overlay policy flag,
 *    virtual /data/local/tmp visibility.
 *  - Periodic synchronization of virtual process names so GG can discover
 *    sandbox processes by their virtual names (/proc/<pid>/cmdline spoofing).
 *    Real memory access stays untouched: GG reads/writes /proc/<pid>/mem and
 *    attaches via ptrace directly, which works between processes sharing the
 *    host UID without real root.
 */
public final class GameGuardianCompat {
    public static final String TAG = "GameGuardianCompat";

    /** Well-known GameGuardian package names. */
    public static final String[] GAMEGUARDIAN_PACKAGES = {
            "speed.dada.gameguardian",
            "speed.dada.gameguardian.paid",
            "speed.dada.gameguardian2",
            "com.speedsoftware.gameguardian",
    };

    private static final long SYNC_INTERVAL_MS = 3000L;

    private static final AtomicBoolean sSyncStarted = new AtomicBoolean(false);
    private static Handler sHandler;
    private static final Map<Integer, String> sLastMap = new HashMap<>();
    private static final Map<Integer, String> sSpoofPaths = new HashMap<>();

    private GameGuardianCompat() {
    }

    /**
     * Returns the path of the spoofed cmdline file for a virtual pid,
     * or null when the pid is not a known virtual process.
     */
    public static String getSpoofFilePath(int pid) {
        synchronized (sSpoofPaths) {
            return sSpoofPaths.get(pid);
        }
    }

    public static boolean isGameGuardian(String packageName) {
        if (packageName == null) {
            return false;
        }
        for (String pkg : GAMEGUARDIAN_PACKAGES) {
            if (pkg.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called when a virtual process is bound. Prepares the GG environment and
     * starts the proc-name sync loop for GG processes.
     */
    public static void onVirtualAppBound(Context context, String packageName, int userId, boolean rootEnabled) {
        try {
            if (!isGameGuardian(packageName)) {
                return;
            }
            Slog.i(TAG, "GameGuardian detected in sandbox (" + packageName + "), preparing environment");
            RootLogger.decision(TAG, "GameGuardian bound: pkg=" + packageName
                    + " user=" + userId + " rootEmulation=" + rootEnabled);
            // IMPORTANT: this runs on the main thread from IOCore#enableRedirect,
            // during handleBindApplication and before the Application exists.
            // Binder calls here can block the main thread while the server is
            // still bringing this very process up -> frozen black screen.
            // Everything that talks to the server is therefore deferred.
            if (rootEnabled) {
                Thread setup = new Thread(() -> {
                    try {
                        // Ensure overlay policy flag is on so GG can draw its floating icon.
                        if (!BRootManager.get().isOverlayGranted(packageName, userId)) {
                            BRootManager.get().setOverlayGranted(packageName, userId, true);
                        }
                    } catch (Throwable t) {
                        Slog.w(TAG, "GG overlay flag setup failed: " + t.getMessage());
                    }
                }, "GGOverlaySetup");
                setup.setDaemon(true);
                setup.start();
            }
            startProcSync();
        } catch (Throwable t) {
            Slog.w(TAG, "GG setup failed: " + t.getMessage());
        }
    }

    /**
     * Starts the periodic virtual-process-name synchronization loop (GG process only).
     */
    public static void startProcSync() {
        if (!sSyncStarted.compareAndSet(false, true)) {
            return;
        }
        // Runs on a dedicated background thread, never on the main looper:
        // each iteration performs a binder call to the server plus file IO.
        HandlerThread thread = new HandlerThread("GGProcSync");
        thread.setDaemon(true);
        thread.start();
        Looper looper = thread.getLooper();
        if (looper == null) {
            sSyncStarted.set(false);
            return;
        }
        sHandler = new Handler(looper);
        sHandler.postDelayed(() -> syncLoop(), SYNC_INTERVAL_MS);
        Slog.i(TAG, "Virtual process name sync started");
    }

    private static void syncLoop() {
        try {
            refreshProcMap();
        } catch (Throwable t) {
            Slog.w(TAG, "proc sync failed: " + t.getMessage());
        }
        if (sHandler != null) {
            sHandler.postDelayed(() -> syncLoop(), SYNC_INTERVAL_MS);
        }
    }

    /**
     * Fetches the pid -> virtual name map from the server, materializes spoofed
     * /proc files and pushes the mapping into the native layer.
     */
    public static synchronized void refreshProcMap() {
        String json;
        try {
            json = BRootManager.get().getVirtualProcessMapJson();
        } catch (Throwable t) {
            return;
        }
        if (json == null || json.isEmpty()) {
            return;
        }
        Map<Integer, String> parsed = new HashMap<>();
        try {
            JSONObject root = new JSONObject(json);
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                int pid = Integer.parseInt(key);
                String name = root.optString(key, null);
                if (name != null && !name.isEmpty()) {
                    parsed.put(pid, name);
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "proc map parse failed: " + t.getMessage());
            return;
        }

        // Remove stale entries.
        for (Integer oldPid : new java.util.ArrayList<>(sLastMap.keySet())) {
            if (!parsed.containsKey(oldPid)) {
                sLastMap.remove(oldPid);
                synchronized (sSpoofPaths) {
                    String removed = sSpoofPaths.remove(oldPid);
                    if (removed != null) {
                        new File(removed).getParentFile().delete();
                    }
                }
                NativeCore.removeProcMapping(oldPid);
            }
        }
        // Add/update entries.
        for (Map.Entry<Integer, String> e : parsed.entrySet()) {
            String existing = sLastMap.get(e.getKey());
            if (e.getValue().equals(existing)) {
                continue;
            }
            String spoofPath = writeSpoofFile(e.getKey(), e.getValue());
            if (spoofPath != null) {
                NativeCore.addProcMapping(e.getKey(), e.getValue(), spoofPath);
                sLastMap.put(e.getKey(), e.getValue());
                synchronized (sSpoofPaths) {
                    sSpoofPaths.put(e.getKey(), spoofPath);
                }
            }
        }
    }

    private static String writeSpoofFile(int pid, String virtualName) {
        try {
            File dir = new File(BEnvironment.getProcSpoofDir(), String.valueOf(pid));
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File cmdline = new File(dir, "cmdline");
            // cmdline content: name bytes followed by a NUL terminator, like the kernel.
            byte[] data = virtualName.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[data.length + 1];
            System.arraycopy(data, 0, out, 0, data.length);
            out[data.length] = 0;
            try (FileOutputStream fos = new FileOutputStream(cmdline)) {
                fos.write(out);
            }
            return cmdline.getAbsolutePath();
        } catch (Throwable t) {
            Slog.w(TAG, "writeSpoofFile failed for pid " + pid + ": " + t.getMessage());
            return null;
        }
    }
}
