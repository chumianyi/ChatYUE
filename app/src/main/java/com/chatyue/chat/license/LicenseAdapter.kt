package com.chatyue.chat.license

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.databinding.ItemLicenseBinding

/** 开源组件列表适配器。 */
class LicenseAdapter(
    private val items: List<LicenseEntry>,
    private val onItemClick: (LicenseEntry) -> Unit,
) : RecyclerView.Adapter<LicenseAdapter.Holder>() {

    class Holder(val binding: ItemLicenseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLicenseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.binding.licenseName.text = entry.name
        holder.binding.licenseMeta.text = buildString {
            append("v").append(entry.version)
            if (entry.holder.isNotBlank()) {
                append(" · ").append(entry.holder)
            }
        }
        holder.binding.licenseTag.text = entry.license
        holder.binding.root.setOnClickListener { onItemClick(entry) }
    }

    override fun getItemCount(): Int = items.size
}
