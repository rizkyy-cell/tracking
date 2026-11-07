package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Panel kaca. Lapisan dari bawah ke atas:
 *  1. potongan latar yang sudah kabur (efek "frosted"),
 *  2. lapisan warna tipis (putih bening / biru pada tombol utama),
 *  3. kilap di setengah atas,
 *  4. garis tepi terang bergradien (terang di kiri-atas, redup di tengah, agak terang di kanan-bawah).
 * Posisi layar panel dipantau tiap frame supaya potongan kaca tetap "menempel" ke latar saat
 * daftar digulir atau panel beranimasi.
 */
open class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Tone { NEUTRAL, ACCENT }

    var tone: Tone = Tone.NEUTRAL
        set(value) {
            field = value
            rebuildShaders()
            invalidate()
        }

    /** Jari-jari sudut dalam piksel. Diabaikan jika [pill] true. */
    var radius: Float = context.dpf(24)
        set(value) {
            field = value
            invalidateOutline()
            rebuildShaders()
            invalidate()
        }

    /** true = sudut penuh (bentuk kapsul), mengikuti tinggi view. */
    var pill: Boolean = false
        set(value) {
            field = value
            invalidateOutline()
            rebuildShaders()
            invalidate()
        }

    /** 0..1: seberapa pekat lapisan dasar di bawah kaca (dialog butuh lebih pekat agar terbaca). */
    var solidity: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var elevationDp: Float = 6f
        set(value) {
            field = value
            elevation = context.dpf(value)
        }

    /** Jika true, panel mengecil sedikit saat ditekan. */
    var pressable: Boolean = false

    private val night = Glass.night(context)
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1.3f)
    }
    private val clip = Path()
    private val body = RectF()
    private val edgeRect = RectF()
    private val dst = RectF()
    private val loc = IntArray(2)
    private val bgLoc = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var bgRef: GlassBackgroundView? = null

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        getLocationOnScreen(loc)
        if (loc[0] != lastX || loc[1] != lastY) {
            lastX = loc[0]
            lastY = loc[1]
            invalidate()
        }
        true
    }

    init {
        setWillNotDraw(false)
        clipChildren = true
        clipToOutline = true
        elevation = context.dpf(elevationDp)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, effectiveRadius())
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = if (night) Color.BLACK else 0xFF1F5EE8.toInt()
            outlineAmbientShadowColor = if (night) Color.BLACK else 0xFF3B5BDB.toInt()
        }
    }

    private fun effectiveRadius(): Float =
        if (pill) height / 2f else minOf(radius, minOf(width, height) / 2f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = effectiveRadius()

        body.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(body, r, r, Path.Direction.CW)

        val half = edgePaint.strokeWidth / 2f
        edgeRect.set(half, half, w - half, h - half)

        val topColor: Int
        val bottomColor: Int
        if (tone == Tone.ACCENT) {
            topColor = if (night) 0xF2527FFF.toInt() else 0xF24A7DFF.toInt()
            bottomColor = if (night) 0xF22350D6.toInt() else 0xF21F5EE8.toInt()
        } else {
            topColor = if (night) 0x26FFFFFF else 0x75FFFFFF
            bottomColor = if (night) 0x0FFFFFFF else 0x38FFFFFF
        }
        tintPaint.shader = LinearGradient(0f, 0f, 0f, h, topColor, bottomColor, Shader.TileMode.CLAMP)

        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.5f,
            if (night) 0x22FFFFFF else 0x55FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )

        val edge = when {
            tone == Tone.ACCENT -> intArrayOf(0xCCFFFFFF.toInt(), 0x40FFFFFF, 0x80FFFFFF.toInt())
            night -> intArrayOf(0x80FFFFFF.toInt(), 0x14FFFFFF, 0x45FFFFFF)
            else -> intArrayOf(0xF2FFFFFF.toInt(), 0x30FFFFFF, 0x99FFFFFF.toInt())
        }
        edgePaint.shader = LinearGradient(
            0f, 0f, w, h, edge, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )

        basePaint.color = if (night) 0xFF121A3C.toInt() else 0xFFF2F6FF.toInt()
    }

    private fun backdropView(): GlassBackgroundView? {
        val cached = bgRef
        if (cached != null && cached.isAttachedToWindow) return cached
        val own = rootView.findViewById<View>(R.id.glass_bg) as? GlassBackgroundView
        val found = own ?: GlassBackgroundView.last?.get()
        bgRef = found
        return found
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.save()
        canvas.clipPath(clip)

        val bg = backdropView()
        val blur = bg?.blur
        if (bg != null && blur != null) {
            getLocationOnScreen(loc)
            bg.getLocationOnScreen(bgLoc)
            dst.set(0f, 0f, bg.width.toFloat(), bg.height.toFloat())
            canvas.save()
            canvas.translate((bgLoc[0] - loc[0]).toFloat(), (bgLoc[1] - loc[1]).toFloat())
            canvas.drawBitmap(blur, null, dst, blurPaint)
            canvas.restore()
        }

        if (solidity > 0f) {
            basePaint.alpha = (solidity * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(body, basePaint)
        }
        canvas.drawRect(body, tintPaint)
        canvas.drawRect(0f, 0f, w, h * 0.5f, highlightPaint)
        canvas.restore()

        val r = effectiveRadius()
        canvas.drawRoundRect(edgeRect, r, r, edgePaint)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (!pressable) return
        val s = if (pressed) 0.97f else 1f
        animate().scaleX(s).scaleY(s)
            .setStartDelay(0L)
            .setDuration(if (pressed) 90L else 220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastX = Int.MIN_VALUE
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        bgRef = null
        super.onDetachedFromWindow()
    }
}

/** Tombol kaca berbentuk kapsul. [GlassPanel.Tone.ACCENT] = tombol utama berwarna biru. */
class GlassButton(
    context: Context,
    label: CharSequence,
    tone: GlassPanel.Tone = GlassPanel.Tone.ACCENT
) : GlassPanel(context) {

    val labelView = Glass.text(
        context,
        label,
        15f,
        if (tone == GlassPanel.Tone.ACCENT) Color.WHITE else Glass.ink(context),
        Glass.Weight.BOLD
    )

    init {
        this.tone = tone
        pill = true
        elevationDp = if (tone == GlassPanel.Tone.ACCENT) 4f else 2f
        isClickable = true
        isFocusable = true
        pressable = true
        minimumHeight = context.dp(44)
        setPadding(context.dp(20), 0, context.dp(20), 0)
        addView(
            labelView,
            LayoutParams(Glass.WRAP, Glass.WRAP, android.view.Gravity.CENTER)
        )
    }
}
