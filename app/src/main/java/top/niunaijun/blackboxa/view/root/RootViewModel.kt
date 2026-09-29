package top.niunaijun.blackboxa.view.root

import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackboxa.bean.RootBean
import top.niunaijun.blackboxa.bean.RootUpdateBean
import top.niunaijun.blackboxa.data.RootRepository
import top.niunaijun.blackboxa.view.base.BaseViewModel

class RootViewModel(private val mRepo: RootRepository) : BaseViewModel() {

    val mRootListLiveData = MutableLiveData<List<RootBean>>()

    val mUpdateLiveData = MutableLiveData<RootUpdateBean>()

    fun getRootList() {
        launchOnUI {
            mRepo.getRootList(mRootListLiveData)
        }
    }

    fun setRootEnabled(packageName: String, userID: Int, enabled: Boolean) {
        launchOnUI {
            mRepo.setRootEnabled(packageName, userID, enabled, mUpdateLiveData)
        }
    }
}
