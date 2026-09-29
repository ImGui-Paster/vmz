package top.niunaijun.blackbox.script;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.pm.IBPackageManagerService;
import top.niunaijun.blackbox.utils.DiagnosticLogger;

/** Explicit user action: arm the module, stop only its guest, then launch via BlackBox. */
public final class NativeModuleLauncher {
    private static final Set<String> pending = new HashSet<>();
    private NativeModuleLauncher() {}

    public static String enableAndLaunch(File source, String pkg, int user, String phase) throws Exception {
        if (!BlackBoxCore.get().isMainProcess()) throw new SecurityException("Host UI only");
        if (!ModuleFiles.validPackage(pkg) || user < 0 || pkg.equals(BlackBoxCore.getHostPkg()))
            throw new IOException("Invalid guest target");
        String key = user + ":" + pkg;
        synchronized (pending) {
            if (!pending.add(key)) throw new IOException("Launch already in progress for this guest");
        }
        boolean armed = false;
        try {
            // Check the real virtual PM, not isInstalled's host-package fallback.
            IBPackageManagerService pm = BlackBoxCore.getBPackageManager().getServiceWithFallback();
            if (pm == null || !pm.isInstalled(pkg, user))
                throw new IOException("Guest package service unavailable or target not installed for this user");
            if (BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(pkg, user) == null)
                throw new IOException("No launch activity for the selected guest");
            NativeModuleManager.enable(source, pkg, user, phase);
            armed = true;
            DiagnosticLogger.i("NativeModules", "RESTART_REQUESTED pkg=" + pkg + " user=" + user);
            // Direct AIDL call propagates failures; the convenience wrapper swallows RemoteException.
            // Do not clear data, stop other users, or use the device's ActivityManager/am force-stop.
            pm.stopPackage(pkg, user);
            if (!BlackBoxCore.get().launchApk(pkg, user))
                throw new IOException("BlackBox declined launch; check permissions and the target launcher");
            // Do not replace guest-written LOAD_* status with host launch bookkeeping.
            DiagnosticLogger.i("NativeModules", "LAUNCH_REQUESTED pkg=" + pkg + " user=" + user);
            return "LAUNCH_REQUESTED: " + pkg + " / user " + user
                    + "\nModule enabled; BlackBox accepted the launch request. Open Last load status after startup."
                    + "\nThis is not confirmation that the library or its menu is working.\n";
        } catch (Exception error) {
            DiagnosticLogger.w("NativeModules", "AUTO_LAUNCH_FAILED pkg=" + pkg + " user=" + user + ": " + error);
            if (armed) throw new IOException("Module remains enabled, but automatic restart/launch failed: "
                    + error.getMessage() + ". You can start this guest manually.", error);
            throw error;
        } finally {
            synchronized (pending) { pending.remove(key); }
        }
    }
}
