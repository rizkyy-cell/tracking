package com.jrxrezkyy.webcloner

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Susunan layar browser, dibuat lewat kode. Nama properti sengaja sama dengan versi layout XML
 * lama (webView, swipe, progress, errorGroup, ...) agar sisa BrowserActivity tidak perlu berubah.
 */
class BrowserUi(private val activity: BrowserActivity) {

    private val ink = Glass.ink(activity)
    private val soft = Glass.soft(activity)

    val webView = WebView(activity)
    val swipe = SwipeRefreshLayout(activity)
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    val errorGroup = LinearLayout(activity)
    val errorText: TextView = Glass.text(activity, null, 15f, ink)
    val retryButton = GlassButton(activity, activity.getString(R.string.retry))
    val fullscreenContainer = FrameLayout(activity)
    val mainGroup = LinearLayout(activity)

    val bar = GlassPanel(activity)
    val closeButton: ImageView
    val refreshButton: ImageView
    val moreButton: ImageView
    val titleView: TextView = Glass.text(activity, null, 16f, ink, Glass.Weight.BOLD)
    val subtitleView: TextView = Glass.text(activity, null, 12f, soft)

    val root: View get() = mainGroup

    init {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()

        closeButton = Glass.iconButton(
            activity, R.drawable.ic_close, activity.getString(R.string.close), ink
        )
        refreshButton = Glass.iconButton(
            activity, R.drawable.ic_refresh, activity.getString(R.string.refresh), ink
        )
        moreButton = Glass.iconButton(
            activity, R.drawable.ic_more, activity.getString(R.string.more_options), ink
        )

        // ---- bilah kaca di atas
        bar.pill = true
        bar.elevationDp = 6f
        val barRow = LinearLayout(activity)
        barRow.orientation = LinearLayout.HORIZONTAL
        barRow.gravity = Gravity.CENTER_VERTICAL
        barRow.setPadding(dp(6), 0, dp(6), 0)
        barRow.addView(closeButton)
        val titles = LinearLayout(activity)
        titles.orientation = LinearLayout.VERTICAL
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        subtitleView.maxLines = 1
        subtitleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titles.addView(titleView, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        titles.addView(subtitleView, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        barRow.addView(titles, LinearLayout.LayoutParams(0, Glass.WRAP, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(4)
        })
        barRow.addView(refreshButton)
        barRow.addView(moreButton)
        bar.addView(barRow, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))

        mainGroup.orientation = LinearLayout.VERTICAL
        mainGroup.addView(bar, LinearLayout.LayoutParams(Glass.MATCH, dp(56)).apply {
            setMargins(dp(12), dp(8), dp(12), dp(6))
        })

        // ---- garis kemajuan
        progress.isIndeterminate = false
        progress.max = 100
        progress.visibility = View.GONE
        progress.progressTintList = ColorStateList.valueOf(Glass.accent(activity))
        progress.progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        mainGroup.addView(progress, LinearLayout.LayoutParams(Glass.MATCH, dp(3)).apply {
            setMargins(dp(28), 0, dp(28), dp(4))
        })

        // ---- kartu halaman: sudut atas membulat, isi dipotong mengikuti bentuk
        val card = FrameLayout(activity)
        card.setBackgroundColor(Glass.surface(activity))
        swipe.setColorSchemeColors(Glass.accent(activity))
        swipe.setProgressBackgroundColorSchemeColor(if (Glass.night(activity)) 0xFF1B2552.toInt() else Color.WHITE)
        swipe.addView(webView, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))
        card.addView(swipe, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))

        errorGroup.orientation = LinearLayout.VERTICAL
        errorGroup.gravity = Gravity.CENTER
        errorGroup.setPadding(dp(32), dp(32), dp(32), dp(32))
        errorGroup.setBackgroundColor(Glass.surface(activity))
        errorGroup.isClickable = true
        errorGroup.isFocusable = true
        errorGroup.visibility = View.GONE
        errorText.gravity = Gravity.CENTER
        errorGroup.addView(errorText, LinearLayout.LayoutParams(Glass.WRAP, Glass.WRAP))
        errorGroup.addView(retryButton, LinearLayout.LayoutParams(Glass.WRAP, dp(48)).apply {
            topMargin = dp(18)
        })
        card.addView(errorGroup, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))

        mainGroup.addView(card, LinearLayout.LayoutParams(Glass.MATCH, 0, 1f))

        // ---- lapisan layar penuh untuk video
        fullscreenContainer.setBackgroundColor(Color.BLACK)
        fullscreenContainer.visibility = View.GONE
    }
}
