package top.niunaijun.blackboxa.data

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.service.GameGuardianCompat
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.RootBean
import top.niunaijun.blackboxa.bean.RootUpdateBean
import top.niunaijun.blackboxa.util.getString

/**
 * Repository backing the Root Manager screen: per-app root visibility (DenyList).
 */
class RootRepository {

    private val tag = "RootRepository"

    fun getRootList(rootListLiveData: MutableLiveData<List<RootBean>>) {
        try {
            val list = arrayListOf<RootBean>()
            BlackBoxCore.get().users.forEach { user ->
                val userId = user.id
                val userName = AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId") ?: ""
                val apps: List<ApplicationInfo> = try {
                    BlackBoxCore.getBPackageManager().getInstalledApplications(0, userId) ?: emptyList()
                } catch (e: Exception) {
                    Log.w(tag, "getInstalledApplications failed for user $userId: ${e.message}")
                    emptyList()
                }
                apps.forEach { appInfo ->
                    val label = try {
                        BlackBoxCore.getPackageManager().getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        appInfo.packageName
                    }
                    val isGG = GameGuardianCompat.isGameGuardian(appInfo.packageName)
                    val rootEnabled = try {
                        BlackBoxCore.getBRootManager().isRootEnabled(appInfo.packageName, userId)
                    } catch (e: Exception) {
                        Log.w(tag, "isRootEnabled failed: ${e.message}")
                        false
                    }
                    val display = if (userName.isNotEmpty()) "$label • $userName" else label
                    list.add(RootBean(display, appInfo.packageName, userId, rootEnabled, isGG))
                }
            }
            rootListLiveData.postValue(list)
        } catch (e: Exception) {
            Log.e(tag, "getRootList failed: ${e.message}")
            rootListLiveData.postValue(emptyList())
        }
    }

    fun setRootEnabled(
            packageName: String,
            userID: Int,
            enabled: Boolean,
            updateLiveData: MutableLiveData<RootUpdateBean>
    ) {
        try {
            BlackBoxCore.getBRootManager().setRootEnabled(packageName, userID, enabled)
            val msg = if (enabled) {
                getString(top.niunaijun.blackboxa.R.string.root_enabled_toast)
            } else {
                getString(top.niunaijun.blackboxa.R.string.root_hidden_toast)
            }
            updateLiveData.postValue(RootUpdateBean(packageName, userID, true, msg))
        } catch (e: Exception) {
            Log.e(tag, "setRootEnabled failed: ${e.message}")
            updateLiveData.postValue(RootUpdateBean(packageName, userID, false, e.message ?: "error"))
        }
    }
}
