package com.jrxrezkyy.webcloner

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Daftar akun berbentuk panel kaca. Muncul bertahap sekali saat layar pertama dibuka. */
class SiteAdapter(
    private val onOpen: (Site) -> Unit,
    private val onMenu: (Site) -> Unit
) : RecyclerView.Adapter<SiteAdapter.VH>() {

    private var items: List<Site> = emptyList()
    private var intro = true

    fun submit(list: List<Site>) {
        items = list
        notifyDataSetChanged()
    }

    /** Dipanggil setelah daftar pertama tampil; baris berikutnya muncul tanpa animasi masuk. */
    fun endIntro() {
        intro = false
    }

    class VH(
        val panel: GlassPanel,
        val icon: ImageView,
        val name: TextView,
        val url: TextView,
        val more: ImageView
    ) : RecyclerView.ViewHolder(panel)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx: Context = parent.context
        val panel = GlassPanel(ctx)
        panel.radius = ctx.dpf(26)
        panel.isClickable = true
        panel.isFocusable = true
        panel.pressable = true
        panel.layoutParams = RecyclerView.LayoutParams(Glass.MATCH, Glass.WRAP).apply {
            setMargins(0, ctx.dp(6), 0, ctx.dp(6))
        }

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(6), ctx.dp(12))

        val icon = ImageView(ctx)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(icon, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val name = Glass.text(ctx, null, 17f, Glass.ink(ctx), Glass.Weight.BOLD)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        val url = Glass.text(ctx, null, 13f, Glass.soft(ctx))
        url.maxLines = 1
        url.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(name, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        texts.addView(url, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply { topMargin = ctx.dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, Glass.WRAP, 1f).apply { marginStart = ctx.dp(14) })

        val more = Glass.iconButton(
            ctx, R.drawable.ic_more, ctx.getString(R.string.more_options), Glass.soft(ctx), 48, 24
        )
        row.addView(more)

        panel.addView(row, ViewGroup.LayoutParams(Glass.MATCH, Glass.WRAP))
        return VH(panel, icon, name, url, more)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val site = items[position]
        val ctx = holder.itemView.context
        holder.name.text = site.name
        holder.url.text = site.url
        holder.icon.setImageBitmap(IconFactory.forSite(ctx, site, 96))
        holder.panel.setOnClickListener { onOpen(site) }
        holder.more.setOnClickListener { onMenu(site) }
        holder.panel.setOnLongClickListener {
            onMenu(site)
            true
        }

        holder.panel.animate().cancel()
        if (intro) {
            holder.panel.alpha = 0f
            holder.panel.translationY = ctx.dpf(28)
            holder.panel.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L + position * 55L)
                .setDuration(420L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .start()
        } else {
            holder.panel.alpha = 1f
            holder.panel.translationY = 0f
            holder.panel.scaleX = 1f
            holder.panel.scaleY = 1f
        }
    }
}
