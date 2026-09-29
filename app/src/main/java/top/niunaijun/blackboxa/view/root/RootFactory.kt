package top.niunaijun.blackboxa.view.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackboxa.data.RootRepository

class RootFactory(private val repo: RootRepository) : ViewModelProvider.NewInstanceFactory() {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return RootViewModel(repo) as T
    }
}
