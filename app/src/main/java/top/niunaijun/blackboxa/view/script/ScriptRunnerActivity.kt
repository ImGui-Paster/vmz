package top.niunaijun.blackboxa.view.script

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.ScriptBean
import top.niunaijun.blackboxa.databinding.ActivityScriptBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity
import java.io.File

/** Script Runner with explicit guest-native-module activation. */
class ScriptRunnerActivity : LoadingActivity() {

    private lateinit var viewModel: ScriptViewModel
    private lateinit var mAdapter: RVAdapter<ScriptBean>
    private val viewBinding: ActivityScriptBinding by inflate()

    private val importLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = queryFileName(uri)
            showLoading()
            viewModel.importScript(this, uri, name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.script_runner, true)
        initViewModel()
        initRecyclerView()
        viewModel.refreshList()
        viewBinding.btnImport.setOnClickListener { importLauncher.launch("*/*") }
        viewBinding.btnClearConsole.setOnClickListener { viewBinding.consoleOutput.text = "" }
        viewBinding.consoleOutput.movementMethod = ScrollingMovementMethod()
        appendConsole(getString(R.string.script_console_hint) + "\n")
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getScriptFactory())[ScriptViewModel::class.java]
        viewModel.mScriptListLiveData.observe(this) {
            hideLoading()
            mAdapter.setItems(it)
        }
        viewModel.mImportResultLiveData.observe(this) { ok ->
            hideLoading()
            if (ok) toast(R.string.script_import_success) else toast(R.string.script_import_fail)
        }
        viewModel.mRunOutputLiveData.observe(this) { chunk -> appendConsole(chunk) }
        viewModel.mRunFinishedLiveData.observe(this) { result ->
            hideLoading()
            appendConsole(getString(R.string.script_finished, result.exitCode) + "\n\n")
        }
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<ScriptBean>(this, ScriptAdapter()).bind(viewBinding.recyclerView)
            .setItemClickListener { _, item, _ ->
                if (!NativeModulesDialog.open(this, item.file,
                        { text -> appendConsole(text) }, { file -> runFile(file) })) {
                    runFile(item.file)
                }
            }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun runFile(file: File) {
        appendConsole(getString(R.string.script_running, file.name) + "\n")
        showLoading()
        viewModel.runScript(file)
    }

    private fun appendConsole(text: String) {
        // Avoid retaining unbounded shell output in the TextView.
        if (viewBinding.consoleOutput.length() > 1048576) {
            viewBinding.consoleOutput.text = viewBinding.consoleOutput.text.takeLast(524288)
        }
        viewBinding.consoleOutput.append(text)
        viewBinding.consoleScroll.post { viewBinding.consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun queryFileName(uri: android.net.Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        } catch (_: Exception) { }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return name
    }

    companion object {
        fun start(context: Context) { context.startActivity(Intent(context, ScriptRunnerActivity::class.java)) }
    }
}
