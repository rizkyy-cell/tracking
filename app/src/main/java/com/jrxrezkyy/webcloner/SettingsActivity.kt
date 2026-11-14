package com.jrxrezkyy.webcloner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch

/** Pengaturan: kelola akun, tampilan, data, dan tentang (termasuk lisensi). */
class SettingsActivity : GlassActivity() {

    private lateinit var content: LinearLayout
    private var introPlayed = false

    // Pemilih folder unduhan (SAF). Harus didaftarkan saat konstruksi, makanya berupa properti.
    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // izin tidak bisa dipertahankan: tetap dicoba saat mengunduh
                }
                Downloads.setTree(this, uri)
                build()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        // Bilah atas: tombol kembali + judul
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
        val title = Glass.text(this, getString(R.string.settings), 28f, Glass.ink(this), Glass.Weight.BOLD)
        title.letterSpacing = -0.02f
        top.addView(title, LinearLayout.LayoutParams(0, Glass.WRAP, 1f).apply { marginStart = dp(14) })
        column.addView(top, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(4), dp(20), dp(32))
        scroll.addView(content, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        column.addView(scroll, LinearLayout.LayoutParams(Glass.MATCH, 0, 1f))

        setGlassContent(column)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        content.removeAllViews()

        // ---- Akun
        val sites = SiteStore.all(this)
        section(R.string.manage_accounts, if (sites.isEmpty()) null else getString(R.string.account_count, sites.size))
        if (sites.isEmpty()) {
            val msg = Glass.text(this, getString(R.string.no_accounts_settings), 15f, Glass.soft(this))
            msg.setPadding(dp(18), dp(18), dp(18), dp(18))
            group(listOf(msg))
        } else {
            group(sites.map { site ->
                val icon = ImageView(this)
                icon.setImageBitmap(IconFactory.forSite(this, site, 96))
                icon.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                row(
                    icon, site.name, site.host,
                    Glass.icon(this, R.drawable.ic_chevron, 20, Glass.soft(this))
                ) { AccountActions.show(this, site) { build() } }
            })
        }

        // ---- Tampilan
        section(R.string.appearance, null)
        val themeName = themeLabel(SiteStore.theme(this))
        val pull = MaterialSwitch(this)
        pull.isChecked = SiteStore.pullRefresh(this)
        pull.setOnCheckedChangeListener { _, checked -> SiteStore.setPullRefresh(this, checked) }
        val pullRow = row(
            Glass.icon(this, R.drawable.ic_refresh, 22, Glass.ink(this)),
            getString(R.string.pull_refresh), getString(R.string.pull_refresh_hint), pull
        ) { pull.toggle() }
        group(
            listOf(
                row(
                    Glass.icon(this, R.drawable.ic_theme, 22, Glass.ink(this)),
                    getString(R.string.theme), null,
                    Glass.text(this, themeName, 14f, Glass.soft(this))
                ) { chooseTheme() },
                pullRow
            )
        )

        // ---- Ikon
        section(R.string.icons_section, null)
        val badge = MaterialSwitch(this)
        badge.isChecked = SiteStore.showBadge(this)
        badge.setOnCheckedChangeListener { _, checked ->
            SiteStore.setShowBadge(this, checked)
            // Pintasan yang sudah ada di layar utama ikut diperbarui.
            ShortcutHelper.refreshAll(this)
        }
        group(
            listOf(
                row(
                    Glass.icon(this, R.drawable.ic_image, 22, Glass.ink(this)),
                    getString(R.string.show_badge), getString(R.string.show_badge_hint), badge
                ) { badge.toggle() }
            )
        )

        // ---- Unduhan
        section(R.string.downloads_section, null)
        group(
            listOf(
                row(
                    Glass.icon(this, R.drawable.ic_folder, 22, Glass.ink(this)),
                    getString(R.string.dl_folder), Downloads.folderLabel(this),
                    Glass.icon(this, R.drawable.ic_chevron, 20, Glass.soft(this))
                ) { chooseFolder() },
                row(
                    Glass.icon(this, R.drawable.ic_install, 22, Glass.ink(this)),
                    getString(R.string.dl_title), getString(R.string.dl_list_hint),
                    Glass.icon(this, R.drawable.ic_chevron, 20, Glass.soft(this))
                ) { startActivity(Intent(this, DownloadsActivity::class.java)) }
            )
        )

        // ---- Data
        section(R.string.data_section, null)
        group(
            listOf(
                row(
                    Glass.icon(this, R.drawable.ic_trash, 22, Glass.danger(this)),
                    getString(R.string.clear_all), getString(R.string.clear_all_hint), null,
                    titleColor = Glass.danger(this)
                ) { confirmClearAll() }
            )
        )

        // ---- Tentang
        section(R.string.about, null)
        group(
            listOf(
                row(
                    Glass.icon(this, R.drawable.ic_info, 22, Glass.ink(this)),
                    getString(R.string.version), null,
                    Glass.text(this, versionName(), 14f, Glass.soft(this))
                ) { },
                row(
                    Glass.icon(this, R.drawable.ic_people, 22, Glass.ink(this)),
                    getString(R.string.author), null,
                    Glass.text(this, Brand.HANDLE, 14f, Glass.soft(this))
                ) { },
                row(
                    Glass.icon(this, R.drawable.ic_license, 22, Glass.ink(this)),
                    getString(R.string.license), getString(R.string.license_short),
                    Glass.icon(this, R.drawable.ic_chevron, 20, Glass.soft(this))
                ) { showLicense() }
            )
        )

        val foot = Glass.text(this, getString(R.string.copyright, Brand.HANDLE), 13f, Glass.soft(this))
        foot.gravity = Gravity.CENTER
        content.addView(foot, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply {
            topMargin = dp(24)
        })

        // Bagian muncul bertahap, hanya saat layar pertama dibuka (bukan setiap kembali ke sini).
        if (!introPlayed) {
            introPlayed = true
            for (i in 0 until content.childCount) {
                val v = content.getChildAt(i)
                v.alpha = 0f
                v.translationY = dpf(24)
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60L + i * 45L)
                    .setDuration(420L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                    .start()
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.screen_pop_enter, R.anim.screen_pop_exit)
    }

    // ------------------------------------------------------------------ aksi

    private fun themeLabel(mode: Int): String = getString(
        when (mode) {
            1 -> R.string.theme_light
            2 -> R.string.theme_dark
            else -> R.string.theme_system
        }
    )

    private fun chooseTheme() {
        val current = SiteStore.theme(this)
        fun pick(mode: Int) {
            SiteStore.setTheme(this, mode)
            App.applyTheme(mode)
        }
        GlassDialog(this)
            .title(R.string.theme)
            .choice(getString(R.string.theme_system), current == 0) { pick(0) }
            .choice(getString(R.string.theme_light), current == 1) { pick(1) }
            .choice(getString(R.string.theme_dark), current == 2) { pick(2) }
            .show()
    }

    private fun chooseFolder() {
        val dialog = GlassDialog(this)
            .title(R.string.dl_folder)
            .subtitle(Downloads.folderLabel(this))
            .item(R.drawable.ic_folder, R.string.dl_folder_pick) { treePicker.launch(null) }
        if (Downloads.treeUri(this) != null) {
            dialog.item(R.drawable.ic_refresh, R.string.dl_folder_reset) {
                Downloads.setTree(this, null)
                build()
            }
        }
        dialog.show()
    }

    private fun confirmClearAll() {
        GlassDialog(this)
            .title(R.string.clear_all)
            .message(R.string.clear_all_confirm)
            .button(R.string.cancel)
            .button(R.string.clear_data_short, GlassDialog.Kind.DANGER) {
                val list = SiteStore.all(this)
                if (Profiles.supported()) {
                    for (s in list) Profiles.clearData(s)
                } else {
                    Profiles.clearData(Site("shared", "https://localhost", "shared", 1))
                }
                Toast.makeText(this, R.string.clear_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLicense() {
        val text = try {
            resources.openRawResource(R.raw.license_mit).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "MIT License\n\nCopyright (c) 2026 ${Brand.HANDLE}"
        }
        GlassDialog(this)
            .title(R.string.license)
            .subtitle(Brand.HANDLE)
            .scrollText(text)
            .button(R.string.close, GlassDialog.Kind.ACCENT)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (e: Exception) {
        "-"
    }

    // ------------------------------------------------------------------ pembangun tampilan

    private fun section(titleRes: Int, trailing: String?) {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.BOTTOM
        line.addView(
            Glass.text(this, getString(titleRes), 20f, Glass.ink(this), Glass.Weight.BOLD),
            LinearLayout.LayoutParams(0, Glass.WRAP, 1f)
        )
        if (trailing != null) {
            line.addView(Glass.text(this, trailing, 14f, Glass.soft(this)))
        }
        content.addView(line, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply {
            topMargin = dp(24)
            bottomMargin = dp(10)
            marginStart = dp(4)
            marginEnd = dp(4)
        })
    }

    private fun group(rows: List<View>) {
        val panel = GlassPanel(this)
        panel.radius = dpf(26)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { i, v ->
            if (i > 0) {
                val line = View(this)
                line.setBackgroundColor(Glass.hairline(this))
                col.addView(line, LinearLayout.LayoutParams(Glass.MATCH, 1).apply { marginStart = dp(58) })
            }
            col.addView(v, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        }
        panel.addView(col, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        content.addView(panel, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
    }

    private fun row(
        leading: View,
        title: String,
        subtitle: String?,
        trailing: View?,
        titleColor: Int = Glass.ink(this),
        onClick: () -> Unit
    ): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(60)
        r.setPadding(dp(18), dp(10), dp(16), dp(10))
        r.background = Glass.rippleRect(this)
        r.isClickable = true
        r.isFocusable = true
        r.setOnClickListener { onClick() }

        val lead = FrameLayout(this)
        lead.addView(leading, FrameLayout.LayoutParams(
            if (leading.layoutParams != null) leading.layoutParams.width else dp(22),
            if (leading.layoutParams != null) leading.layoutParams.height else dp(22),
            Gravity.CENTER
        ))
        r.addView(lead, LinearLayout.LayoutParams(dp(36), dp(36)))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(Glass.text(this, title, 16f, titleColor, Glass.Weight.MEDIUM))
        if (subtitle != null) {
            val s: TextView = Glass.text(this, subtitle, 13f, Glass.soft(this))
            texts.addView(s, LinearLayout.LayoutParams(Glass.WRAP, Glass.WRAP).apply { topMargin = dp(2) })
        }
        r.addView(texts, LinearLayout.LayoutParams(0, Glass.WRAP, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(8)
        })
        if (trailing != null) r.addView(trailing)
        return r
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
}
