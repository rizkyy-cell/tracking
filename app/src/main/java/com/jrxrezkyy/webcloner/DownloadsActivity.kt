package com.jrxrezkyy.webcloner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Daftar unduhan: kemajuan, buka, bagikan, coba lagi, batalkan, hapus dari daftar. */
class DownloadsActivity : GlassActivity() {

    private class Row(
        val root: LinearLayout,
        val icon: ImageView,
        val name: TextView,
        val status: TextView,
        val bar: ProgressBar,
        val divider: View,
        var item: DownloadItem
    )

    private lateinit var group: GlassPanel
    private lateinit var col: LinearLayout
    private lateinit var empty: TextView
    private val rows = LinkedHashMap<Long, Row>()
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(20), dp(12), dp(20), dp(8))
        val back = GlassPanel(this)
        back.pill = true
        back.elevationDp = 3f
        back.isClickable = true
        back.isFocusable = true
        back.pressable = true
        back.contentDescription = getString(R.string.back)
        back.addView(
            Glass.icon(this, R.drawable.ic_back, 22, Glass.ink(this)),
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        )
        back.setOnClickListener { finish() }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = Glass.text(this, getString(R.string.dl_title), 28f, Glass.ink(this), Glass.Weight.BOLD)
        title.letterSpacing = -0.02f
        top.addView(title, LinearLayout.LayoutParams(0, Glass.WRAP, 1f).apply { marginStart = dp(14) })
        val clear = Glass.text(this, getString(R.string.dl_clear), 15f, Glass.accent(this), Glass.Weight.MEDIUM)
        clear.setPadding(dp(12), dp(10), dp(12), dp(10))
        clear.setOnClickListener { Downloads.clearFinished(this) }
        top.addView(clear)
        column.addView(top, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(8), dp(20), dp(32))

        group = GlassPanel(this)
        group.radius = dpf(26)
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        group.addView(col, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        content.addView(group, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))

        empty = Glass.text(this, getString(R.string.dl_empty), 15f, Glass.soft(this))
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(24), dp(48), dp(24), dp(24))
        content.addView(empty, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))

        scroll.addView(content, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        column.addView(scroll, LinearLayout.LayoutParams(Glass.MATCH, 0, 1f))
        setGlassContent(column)
    }

    override fun onStart() {
        super.onStart()
        Downloads.addListener(listener)
        refresh()
    }

    override fun onStop() {
        Downloads.removeListener(listener)
        super.onStop()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.screen_pop_enter, R.anim.screen_pop_exit)
    }

    // ------------------------------------------------------------------ isi

    private fun refresh() {
        val list = Downloads.list(this)
        val keep = list.map { it.id }.toSet()
        rows.keys.retainAll(keep)
        for (item in list) {
            var r = rows[item.id]
            if (r == null) {
                r = makeRow(item)
                rows[item.id] = r
            }
            r.item = item
            bind(r)
        }
        val desired = list.map { rows.getValue(it.id).root }
        val same = col.childCount == desired.size &&
            desired.indices.all { col.getChildAt(it) === desired[it] }
        if (!same) {
            col.removeAllViews()
            for (v in desired) col.addView(v, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        }
        list.forEachIndexed { i, it ->
            rows.getValue(it.id).divider.visibility = if (i == 0) View.GONE else View.VISIBLE
        }
        val none = list.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        group.visibility = if (none) View.GONE else View.VISIBLE
    }

    private fun makeRow(item: DownloadItem): Row {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = Glass.rippleRect(this)
        root.isClickable = true
        root.isFocusable = true

        val divider = View(this)
        divider.setBackgroundColor(Glass.hairline(this))
        root.addView(divider, LinearLayout.LayoutParams(Glass.MATCH, 1).apply { marginStart = dp(58) })

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(dp(18), dp(12), dp(16), dp(10))
        val icon = ImageView(this)
        line.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val name = Glass.text(this, item.name, 16f, Glass.ink(this), Glass.Weight.MEDIUM)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        val status = Glass.text(this, "", 13f, Glass.soft(this))
        status.maxLines = 2
        status.ellipsize = TextUtils.TruncateAt.END
        texts.addView(name)
        texts.addView(status, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply { topMargin = dp(2) })
        line.addView(texts, LinearLayout.LayoutParams(0, Glass.WRAP, 1f).apply { marginStart = dp(16) })
        root.addView(line, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(Glass.accent(this))
        bar.indeterminateTintList = ColorStateList.valueOf(Glass.accent(this))
        root.addView(bar, LinearLayout.LayoutParams(Glass.MATCH, dp(3)).apply {
            marginStart = dp(58)
            marginEnd = dp(18)
            bottomMargin = dp(12)
        })

        val row = Row(root, icon, name, status, bar, divider, item)
        root.setOnClickListener { actions(row.item) }
        return row
    }

    private fun bind(r: Row) {
        val d = r.item
        r.name.text = d.name
        val size = { n: Long -> Formatter.formatShortFileSize(this, n) }
        val iconRes: Int
        val tint: Int
        when (d.status) {
            DlStatus.QUEUED -> {
                iconRes = R.drawable.ic_install
                tint = Glass.accent(this)
                r.status.text = getString(R.string.dl_status_queued)
            }
            DlStatus.RUNNING -> {
                iconRes = R.drawable.ic_install
                tint = Glass.accent(this)
                r.status.text = if (d.total > 0) {
                    getString(
                        R.string.dl_status_running,
                        (d.done * 100L / d.total).toInt().coerceIn(0, 100), size(d.done), size(d.total)
                    )
                } else {
                    getString(R.string.dl_status_running_unknown, size(d.done))
                }
            }
            DlStatus.DONE -> {
                iconRes = R.drawable.ic_check
                tint = Glass.ink(this)
                r.status.text = getString(R.string.dl_status_done, size(d.done), d.where)
            }
            DlStatus.FAILED -> {
                iconRes = R.drawable.ic_close
                tint = Glass.danger(this)
                r.status.text = d.error ?: getString(R.string.dl_status_failed)
            }
            DlStatus.CANCELED -> {
                iconRes = R.drawable.ic_close
                tint = Glass.soft(this)
                r.status.text = getString(R.string.dl_status_canceled)
            }
        }
        r.icon.setImageResource(iconRes)
        r.icon.imageTintList = ColorStateList.valueOf(tint)
        r.status.setTextColor(if (d.status == DlStatus.FAILED) Glass.danger(this) else Glass.soft(this))
        if (d.active) {
            r.bar.visibility = View.VISIBLE
            if (d.status == DlStatus.RUNNING && d.total > 0) {
                r.bar.isIndeterminate = false
                r.bar.progress = (d.done * 1000L / d.total).toInt().coerceIn(0, 1000)
            } else {
                r.bar.isIndeterminate = true
            }
        } else {
            r.bar.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ aksi

    private fun actions(item: DownloadItem) {
        val d = GlassDialog(this, sheet = true)
            .title(item.name)
            .subtitle(item.where.ifBlank { getString(R.string.dl_folder_default) })
        when (item.status) {
            DlStatus.DONE -> {
                d.item(R.drawable.ic_open, R.string.dl_open) { openFile(item) }
                d.item(R.drawable.ic_share, R.string.dl_share) { shareFile(item) }
                d.item(R.drawable.ic_trash, R.string.dl_remove) { Downloads.remove(this, item) }
            }
            DlStatus.QUEUED, DlStatus.RUNNING -> {
                d.item(R.drawable.ic_close, R.string.dl_cancel, danger = true) { Downloads.cancel(item) }
            }
            DlStatus.FAILED, DlStatus.CANCELED -> {
                if (item.request != null) {
                    d.item(R.drawable.ic_refresh, R.string.dl_retry) { Downloads.retry(this, item) }
                }
                d.item(R.drawable.ic_trash, R.string.dl_remove) { Downloads.remove(this, item) }
            }
        }
        d.show()
    }

    private fun openFile(item: DownloadItem) {
        val intent = DlNotify.viewIntent(item)
        if (intent == null) {
            Toast.makeText(this, R.string.dl_cannot_open, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.dl_cannot_open, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.dl_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(item: DownloadItem) {
        val uri = item.uri
        if (uri == null) {
            Toast.makeText(this, R.string.dl_cannot_open, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mime.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, item.name))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.dl_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }
}
