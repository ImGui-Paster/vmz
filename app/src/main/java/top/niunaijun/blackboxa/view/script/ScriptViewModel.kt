package top.niunaijun.blackboxa.view.script

import androidx.lifecycle.MutableLiveData
import android.content.Context
import android.net.Uri
import top.niunaijun.blackbox.script.ScriptEngine
import top.niunaijun.blackboxa.bean.ScriptBean
import top.niunaijun.blackboxa.view.base.BaseViewModel
import java.io.File

class ScriptViewModel : BaseViewModel() {

    val mScriptListLiveData = MutableLiveData<List<ScriptBean>>()

    val mImportResultLiveData = MutableLiveData<Boolean>()

    val mRunOutputLiveData = MutableLiveData<String>()

    val mRunFinishedLiveData = MutableLiveData<ScriptEngine.ScriptResult>()

    fun refreshList() {
        launchOnUI {
            val list = ScriptEngine.listImported().map { ScriptBean(it) }
            mScriptListLiveData.postValue(list)
        }
    }

    fun importScript(context: Context, uri: Uri, displayName: String?) {
        launchOnUI {
            val appContext = context.applicationContext
            val imported = ScriptEngine.importFromUri(appContext, uri, displayName)
            mImportResultLiveData.postValue(imported != null)
            refreshList()
        }
    }

    fun runScript(script: File) {
        launchOnUI {
            refreshList()
            ScriptEngine.execute(script, null, StringBuilder(), object : ScriptEngine.ScriptOutputListener {
                override fun onOutput(chunk: String) {
                    mRunOutputLiveData.postValue(chunk)
                }

                override fun onFinished(result: ScriptEngine.ScriptResult) {
                    mRunFinishedLiveData.postValue(result)
                }
            })
        }
    }
}
