package top.niunaijun.blackbox.core.system.root;

interface IBRootManagerService {
    /**
     * Returns effective root-visibility state for a virtual package.
     * Explicit per-app state wins over the global default.
     */
    boolean isRootEnabled(String packageName, int userId);

    /**
     * Persist explicit per-app root state (toggle in Root Manager UI).
     */
    void setRootEnabled(String packageName, int userId, boolean enabled);

    /**
     * Packages with explicitly enabled root for given user.
     */
    List<String> getRootEnabledPackages(int userId);

    /**
     * DenyList: packages with root fully hidden (clean non-root environment).
     */
    List<String> getRootDisabledPackages(int userId);

    /**
     * Overlay (SYSTEM_ALERT_WINDOW) policy flag, used by GameGuardian auto-setup.
     */
    boolean isOverlayGranted(String packageName, int userId);

    void setOverlayGranted(String packageName, int userId, boolean granted);

    /**
     * JSON map of running virtual processes: {"<pid>": "<virtual process name>"}.
     * Used to expose virtual process names over /proc/<pid>/cmdline (GameGuardian).
     */
    String getVirtualProcessMapJson();
}
