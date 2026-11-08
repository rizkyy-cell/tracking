package com.jrxrezkyy.webcloner

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.CycleInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : GlassActivity() {

    private lateinit var adapter: SiteAdapter
    private lateinit var input: EditText
    private lateinit var inputPanel: GlassPanel
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private var firstList = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setGlassContent(buildUi())
        // Pintasan buatan versi lama diarahkan ke perantara baru (perbaikan buka-dua-akun).
        ShortcutHelper.refreshAll(this)
        showLastCrash()
    }

    /** Jika aplikasi berhenti di sesi sebelumnya, tampilkan penyebabnya supaya bisa dilaporkan. */
    private fun showLastCrash() {
        val file = File(filesDir, App.CRASH_FILE)
        if (!file.exists()) return
        val report = try {
            file.readText()
        } catch (e: Exception) {
            return
        }
        file.delete()
        GlassDialog(this)
            .title(R.string.crash_title)
            .message(R.string.crash_message)
            .scrollText(report)
            .button(R.string.crash_copy) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .button(R.string.close, GlassDialog.Kind.ACCENT)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun buildUi(): View {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20), dp(16), dp(20), 0)

        // Judul + tombol pengaturan
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = Glass.text(this, getString(R.string.app_name), 36f, Glass.ink(this), Glass.Weight.BOLD)
        title.letterSpacing = -0.02f
        header.addView(title, LinearLayout.LayoutParams(0, Glass.WRAP, 1f))
        val settings = GlassPanel(this)
        settings.pill = true
        settings.elevationDp = 3f
        settings.isClickable = true
        settings.isFocusable = true
        settings.pressable = true
        settings.contentDescription = getString(R.string.settings)
        settings.addView(
            Glass.icon(this, R.drawable.ic_settings, 22, Glass.ink(this)),
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        )
        settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.screen_enter, R.anim.screen_exit)
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))

        val subtitle = Glass.text(this, getString(R.string.subtitle), 14f, Glass.soft(this))
        subtitle.setLineSpacing(0f, 1.15f)
        column.addView(subtitle, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply {
            topMargin = dp(6)
            bottomMargin = dp(20)
        })

        // Kolom alamat + tombol Buka
        inputPanel = GlassPanel(this)
        inputPanel.pill = true
        inputPanel.elevationDp = 5f
        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.setPadding(dp(18), dp(6), dp(8), dp(6))
        inputRow.addView(Glass.icon(this, R.drawable.ic_globe, 22, Glass.soft(this)))
        input = EditText(this)
        input.background = null
        input.hint = getString(R.string.hint_url)
        input.setHintTextColor((Glass.soft(this) and 0x00FFFFFF) or 0xA0000000.toInt())
        input.setTextColor(Glass.ink(this))
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.setSingleLine(true)
        input.setPadding(dp(12), dp(12), dp(8), dp(12))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, Glass.WRAP, 1f))
        val open = GlassButton(this, getString(R.string.open))
        open.setOnClickListener { submit() }
        inputRow.addView(open, LinearLayout.LayoutParams(Glass.WRAP, dp(46)))
        inputPanel.addView(inputRow, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        column.addView(inputPanel, LinearLayout.LayoutParams(Glass.MATCH, dp(62)))

        // Judul daftar
        val sectionRow = LinearLayout(this)
        sectionRow.orientation = LinearLayout.HORIZONTAL
        sectionRow.gravity = Gravity.BOTTOM
        val section = Glass.text(this, getString(R.string.saved_sites), 22f, Glass.ink(this), Glass.Weight.BOLD)
        sectionRow.addView(section, LinearLayout.LayoutParams(0, Glass.WRAP, 1f))
        countView = Glass.text(this, null, 14f, Glass.soft(this))
        sectionRow.addView(countView, LinearLayout.LayoutParams(Glass.WRAP, Glass.WRAP))
        column.addView(sectionRow, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply {
            topMargin = dp(28)
            bottomMargin = dp(6)
        })

        // Daftar akun
        val listHolder = FrameLayout(this)
        val list = RecyclerView(this)
        adapter = SiteAdapter(
            onOpen = { site -> TaskLauncher.open(this, site) },
            onMenu = { site -> AccountActions.show(this, site) { refreshList() } }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.clipToPadding = false
        list.clipChildren = false
        list.setPadding(0, dp(4), 0, dp(24))
        list.overScrollMode = View.OVER_SCROLL_NEVER
        listHolder.addView(list, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))

        emptyView = Glass.text(this, getString(R.string.empty_sites), 15f, Glass.soft(this))
        emptyView.gravity = Gravity.CENTER
        emptyView.setLineSpacing(0f, 1.15f)
        emptyView.setPadding(dp(24), dp(24), dp(24), dp(24))
        listHolder.addView(emptyView, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP, Gravity.CENTER))
        column.addView(listHolder, LinearLayout.LayoutParams(Glass.MATCH, 0, 1f))

        return column
    }

    private fun refreshList() {
        val sites = SiteStore.all(this)
        adapter.submit(sites, sites.associate { it.id to SiteStore.iconStamp(this, it.id) })
        emptyView.visibility = if (sites.isEmpty()) View.VISIBLE else View.GONE
        countView.text = if (sites.isEmpty()) "" else getString(R.string.account_count, sites.size)
        if (firstList) {
            firstList = false
            // Animasi masuk hanya untuk tampilan pertama; setelah itu baris langsung tampil.
            countView.postDelayed({ adapter.endIntro() }, 900L)
        }
    }

    private fun submit() {
        val url = UrlUtils.normalize(input.text?.toString().orEmpty())
        if (url == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            ObjectAnimator.ofFloat(inputPanel, View.TRANSLATION_X, 0f, dpf(10), 0f).apply {
                duration = 320L
                interpolator = CycleInterpolator(2f)
                start()
            }
            return
        }

        val host = UrlUtils.host(url)
        val existing = SiteStore.countForHost(this, host)
        if (existing == 0) {
            openNew(url)
        } else {
            GlassDialog(this)
                .title(R.string.new_account_title)
                .message(getString(R.string.new_account_message, host, existing))
                .button(R.string.cancel)
                .button(R.string.new_account_create, GlassDialog.Kind.ACCENT) { openNew(url) }
                .show()
        }
    }

    private fun openNew(url: String) {
        val site = SiteStore.create(this, url)
        input.setText("")
        refreshList()
        TaskLauncher.open(this, site)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
}
