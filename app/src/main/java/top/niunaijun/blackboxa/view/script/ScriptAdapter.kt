package top.niunaijun.blackboxa.view.script

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.ScriptBean

class ScriptAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return ScriptVH(inflate(R.layout.item_script, parent))
    }

    class ScriptVH(itemView: View) : RVHolder<ScriptBean>(itemView) {

        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)

        override fun setContent(item: ScriptBean, isSelected: Boolean, payload: Any?) {
            tvTitle.text = item.file.name
            val suffix = if (item.file.isDirectory) {
                itemView.context.getString(R.string.script_type_folder)
            } else {
                itemView.context.getString(R.string.script_type_file)
            }
            tvSubtitle.text = suffix
        }
    }
}
