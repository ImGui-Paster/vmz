package top.niunaijun.blackboxa.bean

/**
 * Row of the Root Manager screen.
 *
 * @param rootEnabled true = emulated root visible for the app,
 *                    false = root fully hidden (DenyList, clean non-root environment).
 */
data class RootBean(
        val appName: String,
        val packageName: String,
        val userID: Int,
        var rootEnabled: Boolean,
        val isGameGuardian: Boolean
)

data class RootUpdateBean(val packageName: String, val userID: Int, val success: Boolean, val msg: String)
