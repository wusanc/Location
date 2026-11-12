package com.wusan.location.ui.mock

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.wusan.location.R
import java.util.Locale

class HistoricalRouteAdapter(
    private val dataSet: MutableList<HistoricalRoute>,
    private val onItemClicked: (HistoricalRoute, isLongClicked: Boolean) -> Unit
) : RecyclerView.Adapter<HistoricalRouteAdapter.ViewHolder>() {

    class ViewHolder(
        val root: View
    ) : RecyclerView.ViewHolder(root) {
        val name: MaterialTextView = root.findViewById(R.id.name)
        val route: MaterialTextView = root.findViewById(R.id.desc)
    }

    operator fun get(position: Int): HistoricalRoute {
        return dataSet[position]
    }

    @SuppressLint("NotifyDataSetChanged")
    fun replaceItems(items: List<HistoricalRoute>) {
        val replacement = items.toList()
        dataSet.clear()
        dataSet.addAll(replacement)
        notifyDataSetChanged()
    }

    fun removeItem(position: Int): HistoricalRoute {
        val removed = dataSet.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, itemCount - position)
        return removed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_route_item, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataSet[position]
        holder.name.text = item.name
        val first = item.route.firstOrNull()
        holder.route.text = if (first == null) "0 个坐标点" else String.format(
            Locale.ROOT, "%d 个坐标点 · 起点 %.5f, %.5f", item.route.size, first.first, first.second
        )
        holder.root.setOnClickListener {
            onItemClicked(item, false)
        }
        holder.root.setOnLongClickListener {
            onItemClicked(item, true)
            true
        }
    }
}