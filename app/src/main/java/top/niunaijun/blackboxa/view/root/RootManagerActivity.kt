package top.niunaijun.blackboxa.view.root

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.RootBean
import top.niunaijun.blackboxa.databinding.ActivityRootBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity

/**
 * Root Management / DenyList screen.
 *
 * Switch ON  -> emulated root is visible for the app (su, uid 0, virtual rootfs).
 * Switch OFF -> root fully hidden: clean non-root environment simulation.
 */
class RootManagerActivity : LoadingActivity() {

    private lateinit var viewModel: RootViewModel

    private lateinit var mAdapter: RVAdapter<RootBean>

    private val viewBinding: ActivityRootBinding by inflate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.root_manager, true)
        initViewModel()

        initRecyclerView()
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getRootFactory())[RootViewModel::class.java]
        showLoading()

        viewModel.mRootListLiveData.observe(this) {
            hideLoading()
            mAdapter.setItems(it)
            if (it.isEmpty()) {
                toast(R.string.root_list_empty)
            }
        }

        viewModel.mUpdateLiveData.observe(this) { result ->
            if (result == null) {
                return@observe
            }
            val items = mAdapter.getItems()
            for (index in items.indices) {
                val bean = items[index]
                if (bean.packageName == result.packageName && bean.userID == result.userID) {
                    if (result.success) {
                        bean.rootEnabled = !bean.rootEnabled
                    }
                    mAdapter.replaceAt(index, bean)
                    break
                }
            }
            hideLoading()
            toast(result.msg)
        }

        viewModel.getRootList()
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<RootBean>(this, RootAdapter()).bind(viewBinding.recyclerView)
            .setItemClickListener { view, item, _ ->
                val checkbox = view.findViewById<SwitchCompat>(R.id.checkbox)
                if (item.rootEnabled) {
                    hideRoot(item, checkbox)
                } else {
                    grantRoot(item, checkbox)
                }
            }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun grantRoot(item: RootBean, checkbox: SwitchCompat) {
        MaterialDialog(this).show {
            title(R.string.root_enable_title)
            message(text = getString(R.string.root_enable_hint, item.appName))
            positiveButton(R.string.done) {
                showLoading()
                viewModel.setRootEnabled(item.packageName, item.userID, true)
                if (item.isGameGuardian) {
                    offerHostOverlayPermission()
                }
            }
            negativeButton(R.string.cancel) {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    /**
     * GameGuardian floating icon (SYSTEM_ALERT_WINDOW) is rendered by the host
     * process, so the host app needs the overlay permission for the GG overlay
     * to actually display on screen.
     */
    private fun offerHostOverlayPermission() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            return
        }
        MaterialDialog(this).show {
            title(R.string.gg_overlay_title)
            message(R.string.gg_overlay_hint)
            positiveButton(R.string.done) {
                try {
                    val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    toast(R.string.gg_overlay_unavailable)
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun hideRoot(item: RootBean, checkbox: SwitchCompat) {
        MaterialDialog(this).show {
            title(R.string.root_disable_title)
            message(text = getString(R.string.root_disable_hint, item.appName))
            positiveButton(R.string.done) {
                showLoading()
                viewModel.setRootEnabled(item.packageName, item.userID, false)
            }
            negativeButton(R.string.cancel) {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    private var mSkipFirstResume = false

    override fun onResume() {
        super.onResume()
        // Refresh when coming back (e.g. after granting host overlay permission for GG).
        if (mSkipFirstResume) {
            viewModel.getRootList()
        }
        mSkipFirstResume = true
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, RootManagerActivity::class.java)
            context.startActivity(intent)
        }
    }
}
