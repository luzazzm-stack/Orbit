package app.orbit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.orbit.R

sealed class PanelItem {
    data class Header(val text: String) : PanelItem()

    data class Row(
        val title: String,
        val subtitle: String = "",
        val iconRes: Int = 0,
        val value: String = "",
        val actionIcon: Int = 0,
        val onClick: () -> Unit,
        val onAction: (() -> Unit)? = null
    ) : PanelItem()
}

class PanelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<PanelItem>()

    fun submit(new: List<PanelItem>) {
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (items[position] is PanelItem.Header) TYPE_HEADER else TYPE_ROW

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inf.inflate(R.layout.item_panel_header, parent, false))
        } else {
            RowHolder(inf.inflate(R.layout.item_panel_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PanelItem.Header -> (holder as HeaderHolder).bind(item)
            is PanelItem.Row -> (holder as RowHolder).bind(item)
        }
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val text: TextView = v.findViewById(R.id.headerText)
        fun bind(item: PanelItem.Header) {
            text.text = item.text
        }
    }

    private class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val main: LinearLayout = v.findViewById(R.id.rowMain)
        private val icon: ImageView = v.findViewById(R.id.rowIcon)
        private val title: TextView = v.findViewById(R.id.rowTitle)
        private val sub: TextView = v.findViewById(R.id.rowSub)
        private val value: TextView = v.findViewById(R.id.rowValue)
        private val action: ImageView = v.findViewById(R.id.rowAction)

        fun bind(item: PanelItem.Row) {
            title.text = item.title

            sub.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
            sub.text = item.subtitle

            value.visibility = if (item.value.isBlank()) View.GONE else View.VISIBLE
            value.text = item.value

            if (item.iconRes != 0) {
                icon.visibility = View.VISIBLE
                icon.setImageResource(item.iconRes)
            } else {
                icon.visibility = View.GONE
            }

            main.setOnClickListener { item.onClick() }

            val secondary = item.onAction
            if (secondary != null && item.actionIcon != 0) {
                action.visibility = View.VISIBLE
                action.setImageResource(item.actionIcon)
                action.setOnClickListener { secondary() }
            } else {
                action.visibility = View.GONE
                action.setOnClickListener(null)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
    }
}
