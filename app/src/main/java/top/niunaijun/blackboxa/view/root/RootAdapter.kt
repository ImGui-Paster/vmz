package top.niunaijun.blackboxa.view.root

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.RootBean

class RootAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return RootVH(inflate(R.layout.item_root, parent))
    }

    class RootVH(itemView: View) : RVHolder<RootBean>(itemView) {

        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        private val statusDot: View = itemView.findViewById(R.id.statusDot)
        private val checkbox: SwitchCompat = itemView.findViewById(R.id.checkbox)

        override fun setContent(item: RootBean, isSelected: Boolean, payload: Any?) {
            val context = itemView.context

            tvTitle.text = item.appName
            tvSubtitle.text = if (item.isGameGuardian) {
                context.getString(R.string.root_item_gg_suffix, item.packageName, item.userID)
            } else {
                context.getString(R.string.root_item_suffix, item.packageName, item.userID)
            }

            // Colour the state dot so root exposure is readable without having
            // to interpret the switch position (matters for colour-blind users
            // and for screenshots attached to bug reports).
            val dotColor = if (item.rootEnabled) R.color.root_enabled else R.color.root_hidden
            statusDot.background?.mutate()?.setTint(ContextCompat.getColor(context, dotColor))

            // Detach the listener before assigning isChecked: RecyclerView
            // recycles this holder, and a stale listener would fire
            // performClick() for the *previous* row and silently toggle root on
            // the wrong app.
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = item.rootEnabled
            checkbox.setOnCheckedChangeListener { buttonView, _ ->
                if (buttonView.isPressed) {
                    itemView.performClick()
                }
            }

            // Accessibility: the row is the real click target, so describe the
            // whole state there instead of leaving TalkBack to read a bare
            // package name plus an unlabelled switch.
            itemView.contentDescription = context.getString(
                if (item.rootEnabled) R.string.root_a11y_enabled else R.string.root_a11y_hidden,
                item.appName
            )
        }
    }
}
