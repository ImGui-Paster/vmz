package top.niunaijun.blackbox.core.env;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.system.root.BRootManagerService;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.fake.frameworks.BRootManager;
import top.niunaijun.blackbox.utils.RootLogger;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Per-process cache of the effective root visibility state for the currently
 * bound virtual application. Initialized once during virtual process
 * binding (see IOCore#enableRedirect) and reused by all runtime hooks
 * (OsStub uid spoofing, shell hooks, package visibility, native fake-root).
 *
 * <h3>Why this must never do a binder call</h3>
 * {@link #init(String, int)} runs on the main thread inside
 * {@code IOCore#enableRedirect}, which is itself called from the
 * {@code synchronized} {@code BActivityThread#handleBindApplication} before the
 * Application object exists. Querying the server over binder at that point
 * could block the main thread indefinitely while the server process was busy
 * bringing the very same process up, producing a frozen black screen with no
 * crash (the app was never drawn, so only "not responsive" showed up in
 * logcat).
 *
 * The state is therefore pushed to us inside {@link AppConfig}, which the
 * server fills in locally before the process is even started.
 */
public final class RootConfig {
    public static final String TAG = "RootConfig";

    private static volatile boolean sInitialized = false;
    private static volatile boolean sRootEnabled = false;
    private static volatile boolean sOverlayGranted = false;
    private static volatile String sPackageName = null;
    private static volatile int sUserId = -1;

    private RootConfig() {
    }

    /**
     * Initialize root state for the current virtual process. Safe to call
     * multiple times; the state is only computed once per process.
     */
    public static void init(String packageName, int userId) {
        if (sInitialized && packageName != null && packageName.equals(sPackageName) && userId == sUserId) {
            return;
        }
        boolean enabled = false;
        boolean overlay = false;
        // Preferred (and only non-blocking) source: the state the server has
        // already resolved for us and shipped inside AppConfig. No binder, no lock.
        AppConfig config = null;
        try {
            config = BActivityThread.getAppConfig();
        } catch (Throwable ignored) {
        }
        if (config != null && packageName != null && packageName.equals(config.packageName)) {
            enabled = config.rootEnabled;
            overlay = config.overlayGranted;
        } else {
            // Fallback only: AppConfig not available yet (e.g. a hook running
            // very early). Use the purely local global switch - deliberately
            // NOT a binder query, to keep the main thread unblockable.
            try {
                enabled = !BlackBoxCore.get().isHideRoot()
                        && !BRootManagerService.isDefaultRootHidden(packageName);
            } catch (Throwable ignored) {
                enabled = false;
            }
        }
        sPackageName = packageName;
        sUserId = userId;
        sRootEnabled = enabled;
        sOverlayGranted = overlay;
        sInitialized = true;
        Slog.i(TAG, "Root state for " + packageName + " user " + userId + ": "
                + (enabled ? "ROOT-EMULATED" : "root hidden (non-root env)")
                + ", overlay=" + overlay);
        RootLogger.decision(TAG, "init: pkg=" + packageName + " user=" + userId
                + " source=" + (config != null && packageName != null && packageName.equals(config.packageName)
                ? "AppConfig(server-resolved)" : "local-fallback")
                + " -> " + (enabled ? "ROOT-EMULATED" : "ROOT-HIDDEN")
                + " overlay=" + overlay);
        if (config == null) {
            RootLogger.w(TAG, "AppConfig was NULL during RootConfig.init (early hook?) - fallback used");
        } else if (packageName != null && !packageName.equals(config.packageName)) {
            RootLogger.w(TAG, "AppConfig mismatch: asked for " + packageName
                    + " but config holds " + config.packageName + " - fallback used");
        }
    }

    /**
     * Whether fake-root (emulated su / uid 0) is active for the current virtual process.
     */
    public static boolean isRootEnabledForCurrentProcess() {
        return sInitialized && sRootEnabled;
    }

    public static boolean isOverlayGrantedForCurrentProcess() {
        return sInitialized && sOverlayGranted;
    }

    public static String getCurrentPackageName() {
        return sPackageName;
    }

    public static int getCurrentUserId() {
        return sUserId;
    }

    /**
     * Query the effective root state for an arbitrary virtual package (UI/server use).
     */
    public static boolean isRootEnabled(String packageName, int userId) {
        try {
            return BRootManager.get().isRootEnabled(packageName, userId);
        } catch (Throwable t) {
            try {
                return !BlackBoxCore.get().isHideRoot() && !BRootManagerService.isDefaultRootHidden(packageName);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
