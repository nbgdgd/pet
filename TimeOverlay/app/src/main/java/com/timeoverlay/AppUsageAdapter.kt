package com.timeoverlay

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timeoverlay.databinding.ItemAppUsageBinding

/** Список приложений: иконка, название, время и доля от самого залипательного. */
class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.Holder>() {

    private var items: List<AppUsage> = emptyList()
    private var maxMillis: Long = 1

    fun submit(list: List<AppUsage>) {
        items = list
        maxMillis = list.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemAppUsageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.icon.setImageDrawable(item.icon)
        holder.binding.name.text = item.label
        holder.binding.time.text = TimeFormat.formatLong(item.millis)
        holder.binding.share.progress = (item.millis * 100 / maxMillis).toInt()
    }

    class Holder(val binding: ItemAppUsageBinding) : RecyclerView.ViewHolder(binding.root)
}
