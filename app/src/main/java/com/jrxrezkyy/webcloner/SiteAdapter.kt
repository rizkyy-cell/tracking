package com.jrxrezkyy.webcloner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jrxrezkyy.webcloner.databinding.ItemSiteBinding

class SiteAdapter(
    private val onOpen: (Site) -> Unit,
    private val onMenu: (Site, View) -> Unit
) : RecyclerView.Adapter<SiteAdapter.VH>() {

    private var items: List<Site> = emptyList()

    fun submit(list: List<Site>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val binding: ItemSiteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val site = items[position]
        val ctx = holder.itemView.context

        holder.binding.siteName.text = site.name
        holder.binding.siteUrl.text = site.url
        holder.binding.siteIcon.setImageBitmap(
            IconFactory.create(site.host, SiteStore.favicon(ctx, site.host), 96, site.badge)
        )
        holder.binding.root.setOnClickListener { onOpen(site) }
        holder.binding.menuButton.setOnClickListener { onMenu(site, it) }
    }
}
