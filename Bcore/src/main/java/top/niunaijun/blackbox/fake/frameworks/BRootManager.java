package top.niunaijun.blackbox.fake.frameworks;

import android.os.RemoteException;

import java.util.List;

import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.root.IBRootManagerService;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Client-side facade for the Root Manager service.
 *
 * Provides per-app root visibility (DenyList) queries used by both the
 * virtual environment runtime and the launcher UI.
 */
public class BRootManager extends BlackManager<IBRootManagerService> {
    public static final String TAG = "BRootManager";

    private static final BRootManager sRootManager = new BRootManager();

    public static BRootManager get() {
        return sRootManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.ROOT_MANAGER;
    }

    public boolean isRootEnabled(String packageName, int userId) {
        try {
            IBRootManagerService service = getService();
            if (service != null) {
                return service.isRootEnabled(packageName, userId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "isRootEnabled failed: " + e.getMessage());
        }
        // Fail-open to the global default: root visible unless global hide is on.
        try {
            return !top.niunaijun.blackbox.BlackBoxCore.get().isHideRoot();
        } catch (Throwable t) {
            return false;
        }
    }

    public void setRootEnabled(String packageName, int userId, boolean enabled) {
        try {
            IBRootManagerService service = getService();
            if (service != null) {
                service.setRootEnabled(packageName, userId, enabled);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "setRootEnabled failed: " + e.getMessage());
        }
    }

    public List<String> getRootEnabledPackages(int userId) {
        try {
            IBRootManagerService service = getService();
            if (service != null) {
                return service.getRootEnabledPackages(userId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "getRootEnabledPackages failed: " + e.getMessage());
        }
        return java.util.Collections.emptyList();
    }

    public List<String> getRootDisabledPackages(int userId) {
        try {
            IBRootManagerService service = getService();
            if (service != null) {
                return service.getRootDisabledPackages(userId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "getRootDisabledPackages failed: " + e.getMessage());
        }
        return java.util.Collections.emptyList();
    }

    public boolean isOverlayGranted(String packageName, int userId) {
        try {
            IBRootManagerService service = getService();
            if (service != null) {
                return service.isOverlayGranted(packageName, userId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "isOverlayGranted failed: " + e.getMessage());
        }
        return false;
    }

    public void setOverlayGranted(String packageName, int userId, boolean granted) {
        try {
            IBRootManagerService service = getService();
            if (service != null) {
                service.setOverlayGranted(packageName, userId, granted);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "setOverlayGranted failed: " + e.getMessage());
        }
    }

    public String getVirtualProcessMapJson() {
        try {
            IBRootManagerService service = getService();
            if (service != null) {
                return service.getVirtualProcessMapJson();
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "getVirtualProcessMapJson failed: " + e.getMessage());
        }
        return "{}";
    }
}
