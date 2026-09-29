package top.niunaijun.blackbox.core.system.root;

import android.os.RemoteException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.RootLogger;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Server-side Root Management service.
 *
 * Persists per-app ("package|userId" -> state) root visibility configuration and
 * provides the runtime DenyList information used by the virtual environment to
 * either emulate a rooted sandbox or simulate a clean non-root environment.
 *
 * Policy:
 *  - Explicit per-app state (toggled from Root Manager UI) always wins.
 *  - Otherwise the global "hide root" configuration decides (see ClientConfiguration#isHideRoot()).
 *  - Packages listed in DEFAULT_ROOT_HIDDEN (GMS core) are rooted-hidden by default
 *    unless explicitly enabled by the user.
 */
public class BRootManagerService extends IBRootManagerService.Stub implements ISystemService {
    public static final String TAG = "BRootManagerService";

    /**
     * Root is emulated for newly installed virtual apps by default (VMOS-style).
     * The explicit DenyList from the Root Manager UI overrides this.
     */
    public static final boolean DEFAULT_ROOT_ENABLED = true;

    private static final BRootManagerService sService = new BRootManagerService();

    public static BRootManagerService get() {
        return sService;
    }

    /** Packages that must never be rooted by default to keep GMS flows intact. */
    private static final String[] DEFAULT_ROOT_HIDDEN = {
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.android.vending",
            "com.google.android.webview",
    };

    private final Map<String, Entry> mEntries = new HashMap<>();
    private boolean mLoaded = false;
    /** Guards disk writes only, never held together with the state monitor. */
    private final Object mIoLock = new Object();

    private static class Entry {
        boolean rootEnabled;
        boolean overlayGranted;
    }

    public BRootManagerService() {
        loadLocked();
    }

    private String key(String packageName, int userId) {
        return packageName + "|" + userId;
    }

    private File getConfFile() {
        return new File(BEnvironment.getSystemDir(), "root_conf.json");
    }

    private synchronized void loadLocked() {
        if (mLoaded) {
            return;
        }
        mLoaded = true;
        File conf = getConfFile();
        if (!conf.exists()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(conf)) {
            byte[] buf = new byte[(int) conf.length()];
            int read = fis.read(buf);
            if (read <= 0) {
                return;
            }
            JSONObject root = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
            JSONObject entries = root.optJSONObject("entries");
            if (entries == null) {
                return;
            }
            JSONArray names = entries.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                if (key == null) {
                    continue;
                }
                JSONObject item = entries.optJSONObject(key);
                if (item == null) {
                    continue;
                }
                Entry e = new Entry();
                e.rootEnabled = item.optBoolean("root", DEFAULT_ROOT_ENABLED);
                e.overlayGranted = item.optBoolean("overlay", false);
                mEntries.put(key, e);
            }
            Slog.d(TAG, "Loaded root conf: " + mEntries.size() + " entries");
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to load root conf: " + t.getMessage());
        }
    }

    /**
     * Serializes the current configuration to a string while holding the state
     * monitor. Must stay allocation-only: no disk IO here.
     */
    private String snapshotLocked() {
        JSONObject root = new JSONObject();
        JSONObject entries = new JSONObject();
        try {
            for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("root", e.getValue().rootEnabled);
                item.put("overlay", e.getValue().overlayGranted);
                entries.put(e.getKey(), item);
            }
            root.put("entries", entries);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to serialize root conf: " + t.getMessage());
        }
        return root.toString();
    }

    /**
     * Writes the given snapshot to disk.
     *
     * Deliberately NOT synchronized on {@code this}: the previous version held
     * the service monitor across an {@code fsync}, so every binder thread
     * asking {@link #isRootEnabled} (including the process-startup path) blocked
     * on disk IO. Writes are serialized with their own lock and use an atomic
     * temp-file rename so a crash mid-write cannot corrupt the config.
     */
    private void persistAsync(final String payload) {
        Thread writer = new Thread(() -> {
            synchronized (mIoLock) {
                try {
                    File conf = getConfFile();
                    File parent = conf.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    File tmp = new File(conf.getAbsolutePath() + ".tmp");
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        fos.write(payload.getBytes(StandardCharsets.UTF_8));
                        fos.getFD().sync();
                    }
                    if (!tmp.renameTo(conf)) {
                        // Fall back to a direct write if rename is unavailable.
                        try (FileOutputStream fos = new FileOutputStream(conf)) {
                            fos.write(payload.getBytes(StandardCharsets.UTF_8));
                            fos.getFD().sync();
                        }
                        tmp.delete();
                    }
                } catch (Throwable t) {
                    Slog.w(TAG, "Failed to persist root conf: " + t.getMessage());
                }
            }
        }, "RootConfWriter");
        writer.setDaemon(true);
        writer.start();
    }

    @Override
    public boolean isRootEnabled(String packageName, int userId) throws RemoteException {
        if (packageName == null) {
            return false;
        }
        synchronized (this) {
            Entry e = mEntries.get(key(packageName, userId));
            if (e != null) {
                RootLogger.decision(TAG, "isRootEnabled(" + packageName + "," + userId + ") = "
                        + e.rootEnabled + " [explicit entry]");
                return e.rootEnabled;
            }
        }
        if (isDefaultRootHidden(packageName)) {
            RootLogger.decision(TAG, "isRootEnabled(" + packageName + "," + userId + ") = false"
                    + " [GMS default-hidden list]");
            return false;
        }
        try {
            if (BlackBoxCore.get().isHideRoot()) {
                RootLogger.decision(TAG, "isRootEnabled(" + packageName + "," + userId + ") = false"
                        + " [global hide-root switch]");
                return false;
            }
        } catch (Throwable ignored) {
        }
        RootLogger.decision(TAG, "isRootEnabled(" + packageName + "," + userId + ") = "
                + DEFAULT_ROOT_ENABLED + " [default]");
        return DEFAULT_ROOT_ENABLED;
    }

    @Override
    public void setRootEnabled(String packageName, int userId, boolean enabled) throws RemoteException {
        if (packageName == null) {
            return;
        }
        String payload;
        synchronized (this) {
            String key = key(packageName, userId);
            Entry e = mEntries.get(key);
            if (e == null) {
                e = new Entry();
                mEntries.put(key, e);
            }
            e.rootEnabled = enabled;
            payload = snapshotLocked();
        }
        persistAsync(payload);
        Slog.i(TAG, "Root " + (enabled ? "enabled" : "disabled") + " for " + packageName + " user " + userId);
        RootLogger.decision(TAG, "setRootEnabled(" + packageName + "," + userId + ") = " + enabled
                + " (UI toggle, requires virtual app restart to apply)");
    }

    @Override
    public List<String> getRootEnabledPackages(int userId) throws RemoteException {
        List<String> result = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
                if (e.getValue().rootEnabled && keyUserId(e.getKey()) == userId) {
                    result.add(keyPackage(e.getKey()));
                }
            }
        }
        return result;
    }

    @Override
    public List<String> getRootDisabledPackages(int userId) throws RemoteException {
        List<String> result = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
                if (!e.getValue().rootEnabled && keyUserId(e.getKey()) == userId) {
                    result.add(keyPackage(e.getKey()));
                }
            }
        }
        return result;
    }

    @Override
    public boolean isOverlayGranted(String packageName, int userId) throws RemoteException {
        if (packageName == null) {
            return false;
        }
        synchronized (this) {
            Entry e = mEntries.get(key(packageName, userId));
            if (e != null) {
                return e.overlayGranted;
            }
        }
        return false;
    }

    @Override
    public void setOverlayGranted(String packageName, int userId, boolean granted) throws RemoteException {
        if (packageName == null) {
            return;
        }
        String payload;
        synchronized (this) {
            String key = key(packageName, userId);
            Entry e = mEntries.get(key);
            if (e == null) {
                e = new Entry();
                mEntries.put(key, e);
            }
            e.overlayGranted = granted;
            payload = snapshotLocked();
        }
        persistAsync(payload);
    }

    @Override
    public String getVirtualProcessMapJson() throws RemoteException {
        JSONObject map = new JSONObject();
        try {
            List<ProcessRecord> records = BProcessManagerService.get().getAllProcessRecords();
            if (records != null) {
                for (ProcessRecord record : records) {
                    if (record == null || record.pid <= 0) {
                        continue;
                    }
                    String name = record.processName;
                    if (name == null && record.info != null) {
                        name = record.info.packageName;
                    }
                    if (name != null) {
                        try {
                            map.put(String.valueOf(record.pid), name);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "getVirtualProcessMapJson failed: " + t.getMessage());
        }
        return map.toString();
    }

    private static String keyPackage(String key) {
        int idx = key.lastIndexOf('|');
        return idx > 0 ? key.substring(0, idx) : key;
    }

    private static int keyUserId(String key) {
        int idx = key.lastIndexOf('|');
        if (idx <= 0 || idx == key.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(key.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isDefaultRootHidden(String packageName) {
        if (packageName == null) {
            return false;
        }
        for (String pkg : DEFAULT_ROOT_HIDDEN) {
            if (pkg.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void systemReady() {
    }
}
