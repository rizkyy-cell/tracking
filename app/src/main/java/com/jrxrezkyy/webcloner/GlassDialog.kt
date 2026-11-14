package com.jrxrezkyy.webcloner

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Dialog kaca: jendela penuh transparan dengan satu [GlassPanel] di tengah/bawah. Semua view
 * dibuat dengan konteks Activity (bertema Material) agar komponen Material (mis. switch) aman.
 * Kaca membiaskan potret halaman di belakangnya (lihat [show]).
 */
class GlassDialog(private val ctx: Context, private val sheet: Boolean = false) :
    Dialog(ctx, R.style.GlassDialog) {

    enum class Kind { NEUTRAL, ACCENT, DANGER }

    private val panel = GlassPanel(ctx)
    private val box = LinearLayout(ctx)
    private var buttons: LinearLayout? = null
    private var itemCount = 0
    private var closing = false
    private var presenting = false
    private var snapshot: Bitmap? = null
    private var withInput = false
    private val ink = Glass.ink(ctx)
    private val soft = Glass.soft(ctx)

    init {
        panel.radius = ctx.dpf(30)
        // Kaca memakai potret layar di belakang dialog (lihat show()), bukan foto latar.
        panel.clear = false
        panel.elevationDp = 14f
        panel.isClickable = true
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
        panel.addView(box, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
    }

    // ------------------------------------------------------------------ isi

    fun title(text: CharSequence): GlassDialog {
        val v = Glass.text(ctx, text, 20f, ink, Glass.Weight.BOLD)
        box.addView(v, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply { bottomMargin = ctx.dp(4) })
        return this
    }

    fun title(res: Int): GlassDialog = title(ctx.getString(res))

    fun subtitle(text: CharSequence): GlassDialog {
        val v = Glass.text(ctx, text, 13f, soft)
        v.maxLines = 1
        v.ellipsize = android.text.TextUtils.TruncateAt.END
        box.addView(v, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply { bottomMargin = ctx.dp(8) })
        return this
    }

    fun message(text: CharSequence): GlassDialog {
        val v = Glass.text(ctx, text, 15f, soft)
        v.setLineSpacing(0f, 1.15f)
        box.addView(v, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply { topMargin = ctx.dp(4) })
        return this
    }

    fun message(res: Int): GlassDialog = message(ctx.getString(res))

    /** Kolom teks satu baris. Isinya dibaca lewat nilai kembalian saat tombol ditekan. */
    fun input(initial: String): EditText {
        withInput = true
        val field = EditText(ctx).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ink)
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            background = FieldBackground(ctx)
        }
        box.addView(field, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply {
            topMargin = ctx.dp(10)
            bottomMargin = ctx.dp(4)
        })
        return field
    }

    /** Baris aksi dengan ikon. Dialog ditutup dulu, lalu [onClick] dijalankan. */
    fun item(icon: Int, label: CharSequence, danger: Boolean = false, onClick: () -> Unit): GlassDialog {
        val color = if (danger) Glass.danger(ctx) else ink
        val row = row(icon, label, color)
        row.setOnClickListener {
            dismiss()
            onClick()
        }
        return this
    }

    fun item(icon: Int, res: Int, danger: Boolean = false, onClick: () -> Unit): GlassDialog =
        item(icon, ctx.getString(res), danger, onClick)

    /** Pilihan tunggal bertanda centang. */
    fun choice(label: CharSequence, selected: Boolean, onClick: () -> Unit): GlassDialog {
        val row = row(if (selected) R.drawable.ic_check else 0, label, ink)
        row.setOnClickListener {
            dismiss()
            onClick()
        }
        return this
    }

    /** Baris dengan saklar. Dialog tetap terbuka kecuali [closeOnChange]. */
    fun toggle(
        icon: Int,
        label: CharSequence,
        checked: Boolean,
        closeOnChange: Boolean,
        onChange: (Boolean) -> Unit
    ): GlassDialog {
        val row = row(icon, label, ink)
        val sw = MaterialSwitch(ctx)
        sw.isChecked = checked
        sw.isClickable = false
        sw.isFocusable = false
        row.addView(sw)
        row.setOnClickListener {
            val now = !sw.isChecked
            sw.isChecked = now
            onChange(now)
            if (closeOnChange) dismiss()
        }
        return this
    }

    /** Teks panjang yang bisa digulir (dipakai untuk lisensi). */
    fun scrollText(text: CharSequence): GlassDialog {
        val v = Glass.text(ctx, text, 12.5f, ink)
        v.typeface = Typeface.MONOSPACE
        v.setLineSpacing(0f, 1.1f)
        v.setTextIsSelectable(true)
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = true
        sv.addView(v, FrameLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()
        box.addView(sv, LinearLayout.LayoutParams(Glass.MATCH, maxH).apply { topMargin = ctx.dp(8) })
        return this
    }

    fun button(label: CharSequence, kind: Kind = Kind.NEUTRAL, onClick: (() -> Unit)? = null): GlassDialog {
        var row = buttons
        if (row == null) {
            row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            box.addView(row, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP).apply { topMargin = ctx.dp(16) })
            buttons = row
        }
        val b = GlassButton(
            ctx,
            label,
            if (kind == Kind.ACCENT) GlassPanel.Tone.ACCENT else GlassPanel.Tone.NEUTRAL
        )
        if (kind == Kind.DANGER) b.labelView.setTextColor(Glass.danger(ctx))
        b.setOnClickListener {
            dismiss()
            onClick?.invoke()
        }
        val lp = LinearLayout.LayoutParams(0, ctx.dp(48), 1f)
        if (row.childCount > 0) lp.marginStart = ctx.dp(10)
        row.addView(b, lp)
        return this
    }

    fun button(res: Int, kind: Kind = Kind.NEUTRAL, onClick: (() -> Unit)? = null): GlassDialog =
        button(ctx.getString(res), kind, onClick)

    // ------------------------------------------------------------------ bantu

    private fun row(icon: Int, label: CharSequence, color: Int): LinearLayout {
        if (itemCount > 0) {
            val line = View(ctx)
            line.setBackgroundColor(Glass.hairline(ctx))
            box.addView(line, LinearLayout.LayoutParams(Glass.MATCH, 1).apply { marginStart = ctx.dp(38) })
        }
        itemCount++
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = ctx.dp(52)
        row.background = Glass.rippleRect(ctx)
        row.isClickable = true
        row.isFocusable = true
        if (icon != 0) {
            row.addView(Glass.icon(ctx, icon, 22, color))
        } else {
            row.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        }
        val t = Glass.text(ctx, label, 16f, color, Glass.Weight.MEDIUM)
        row.addView(t, LinearLayout.LayoutParams(0, Glass.WRAP, 1f).apply { marginStart = ctx.dp(16) })
        box.addView(row, LinearLayout.LayoutParams(Glass.MATCH, Glass.WRAP))
        return row
    }

    // ------------------------------------------------------------------ jendela

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0.10f)
        if (withInput) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val root = FrameLayout(ctx)
        root.setOnClickListener { cancel() }
        snapshot?.let { shot ->
            val backdrop = GlassBackgroundView(ctx)
            backdrop.useSnapshot(shot)
            root.addView(backdrop, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))
        }
        val lp = FrameLayout.LayoutParams(
            Glass.MATCH, Glass.WRAP, if (sheet) Gravity.BOTTOM else Gravity.CENTER
        )
        lp.setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        root.addView(panel, lp)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
    }

    /**
     * Mengambil potret jendela aktivitas yang sedang tampil (PixelCopy, termasuk isi WebView),
     * mengecilkannya ke 1/6, mengaburkan, dan mencampurnya sedikit dengan warna tema supaya
     * teks tetap terbaca di atas halaman terang maupun gelap. Hasilnya menjadi bahan bias
     * shader kaca, jadi kartu menu benar-benar "tembus" ke halaman di bawahnya.
     * Jika potret gagal atau lambat (>350 ms), dialog tampil dengan latar foto seperti biasa.
     */
    override fun show() {
        if (presenting) return
        presenting = true
        val act = activityOf(ctx)
        if (act == null || act.isFinishing || act.isDestroyed || Build.VERSION.SDK_INT < 26) {
            present()
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var settled = false
        val settle: (Bitmap?) -> Unit = { bmp ->
            if (!settled) {
                settled = true
                snapshot = bmp
                present()
            }
        }
        handler.postDelayed({ settle(null) }, 350L)
        try {
            val decor = act.window.decorView
            val bmp = Bitmap.createBitmap(
                maxOf(2, decor.width / 6), maxOf(2, decor.height / 6), Bitmap.Config.ARGB_8888
            )
            PixelCopy.request(act.window, bmp, PixelCopy.OnPixelCopyFinishedListener { result ->
                if (result == PixelCopy.SUCCESS) {
                    try {
                        Blur.box(bmp, 2, 2)
                        if (Glass.night(ctx)) Blur.blend(bmp, 0xFF111A3A.toInt(), 0.46f)
                        else Blur.blend(bmp, 0xFFFFFFFF.toInt(), 0.50f)
                        settle(bmp)
                    } catch (e: Throwable) {
                        settle(null)
                    }
                } else {
                    settle(null)
                }
            }, handler)
        } catch (e: Throwable) {
            settle(null)
        }
    }

    private fun activityOf(c: Context): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) {
            if (x is Activity) return x
            x = x.baseContext
        }
        return null
    }

    private fun present() {
        if (closing) return
        super.show()
        panel.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.translationY = if (sheet) ctx.dpf(36) else 0f
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
        // Isi muncul bertahap, seperti menu iOS.
        val count = minOf(box.childCount, 9)
        for (i in 0 until count) {
            val child = box.getChildAt(i)
            child.alpha = 0f
            child.translationY = ctx.dpf(10)
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(70L + i * 35L)
                .setDuration(280L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    override fun dismiss() {
        if (closing) return
        closing = true
        if (!isShowing) {
            try {
                super.dismiss()
            } catch (e: Exception) {
                // jendela sudah hilang
            }
            return
        }
        panel.animate()
            .alpha(0f).scaleX(0.96f).scaleY(0.96f)
            .setDuration(120L)
            .withEndAction {
                try {
                    super@GlassDialog.dismiss()
                } catch (e: Exception) {
                    // Activity sudah selesai; tidak ada yang perlu ditutup.
                }
            }
            .start()
    }
}

/** Latar kolom teks: kapsul kaca tipis dengan garis tepi. */
private class FieldBackground(ctx: Context) : android.graphics.drawable.GradientDrawable() {
    init {
        val night = Glass.night(ctx)
        cornerRadius = ctx.dpf(16)
        setColor(if (night) 0x26FFFFFF else 0x99FFFFFF.toInt())
        setStroke(ctx.dp(1), if (night) 0x40FFFFFF else 0x330B2F7A)
    }
}
