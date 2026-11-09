package com.jrxrezkyy.webcloner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * Panel kaca.
 *
 * Android 13+ : digambar oleh shader GPU ([GpuGlass]) dengan lensing tepi, sorot specular,
 * tepi gelap tipis, dan cahaya dari titik sentuh.
 * Android lama / jika shader gagal: cadangan kanvas (potongan latar + tint + kilap + garis tepi).
 *
 * Mode [clear]: kaca bening untuk dialog. Panel hanya memberi tint tipis dan sorot tepi; yang
 * mengaburkan isi layar di belakangnya adalah sistem (blur-behind Android 12+), jadi yang
 * tampak adalah blur dari tampilan SUNGGUHAN di belakang dialog, bukan gambar tiruan.
 *
 * Posisi layar panel dipantau tiap frame supaya potongan latar tetap "menempel" saat daftar
 * digulir atau panel beranimasi.
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

    /** true = kaca bening (tanpa gambar latar sendiri); lihat dokumentasi kelas. */
    var clear: Boolean = false
        set(value) {
            field = value
            rebuildShaders()
            invalidate()
        }

    /** Pengali tint. Dialog tanpa blur sistem memakai nilai > 1 agar teks tetap terbaca. */
    var tintStrength: Float = 1f
        set(value) {
            field = value
            rebuildShaders()
            invalidate()
        }

    var elevationDp: Float = 6f
        set(value) {
            field = value
            elevation = context.dpf(value)
        }

    /** Jika true, panel memantul saat ditekan dan menyala dari titik sentuh. */
    var pressable: Boolean = false

    private val night = Glass.night(context)
    private val density = context.resources.displayMetrics.density
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    private var gpu: Any? = null
    private var gpuFailed = false

    private var touchX = 0f
    private var touchY = 0f
    private var pressAmt = 0f
    private var pressAnim: ValueAnimator? = null

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
            outlineSpotShadowColor = if (night) Color.BLACK else 0xFF22407A.toInt()
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

    // ------------------------------------------------------------------ cadangan kanvas

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

        val k = tintStrength
        val topColor: Int
        val bottomColor: Int
        if (tone == Tone.ACCENT) {
            topColor = if (night) 0xE6527FFF.toInt() else 0xE64A7DFF.toInt()
            bottomColor = if (night) 0xE62350D6.toInt() else 0xE61F5EE8.toInt()
        } else if (clear) {
            topColor = if (night) argb(0.46f, 0x1B, 0x23, 0x50) else argb(0.52f, 0xFF, 0xFF, 0xFF)
            bottomColor = if (night) argb(0.36f, 0x1B, 0x23, 0x50) else argb(0.38f, 0xFF, 0xFF, 0xFF)
        } else if (night) {
            topColor = argb(0.14f * k, 0xFF, 0xFF, 0xFF)
            bottomColor = argb(0.06f * k, 0xFF, 0xFF, 0xFF)
        } else {
            topColor = argb(0.40f * k, 0xFF, 0xFF, 0xFF)
            bottomColor = argb(0.26f * k, 0xFF, 0xFF, 0xFF)
        }
        tintPaint.shader = LinearGradient(0f, 0f, 0f, h, topColor, bottomColor, Shader.TileMode.CLAMP)

        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.5f,
            if (night) 0x22FFFFFF else 0x55FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )

        val edge = when {
            tone == Tone.ACCENT -> intArrayOf(0xCCFFFFFF.toInt(), 0x40FFFFFF, 0x80FFFFFF.toInt())
            night -> intArrayOf(0x99FFFFFF.toInt(), 0x14FFFFFF, 0x55FFFFFF)
            else -> intArrayOf(0xF2FFFFFF.toInt(), 0x30FFFFFF, 0xAAFFFFFF.toInt())
        }
        edgePaint.shader = LinearGradient(
            0f, 0f, w, h, edge, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun argb(alpha: Float, r: Int, g: Int, b: Int): Int =
        Color.argb((alpha * 255f).toInt().coerceIn(0, 255), r, g, b)

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
        val bg = backdropView()

        var drawn = false
        if (Build.VERSION.SDK_INT >= 33 && !gpuFailed) {
            try {
                drawGpu(canvas, w, h, bg)
                drawn = true
            } catch (e: Throwable) {
                // Shader ditolak perangkat: pakai cadangan kanvas untuk seterusnya.
                gpuFailed = true
                gpu = null
            }
        }
        if (!drawn) drawFallback(canvas, w, h, bg)
    }

    @RequiresApi(33)
    private fun drawGpu(canvas: Canvas, w: Float, h: Float, bg: GlassBackgroundView?) {
        var g = gpu as? GpuGlass
        if (g == null) {
            g = GpuGlass()
            gpu = g
        }
        var art: android.graphics.Bitmap? = null
        var ox = 0f
        var oy = 0f
        var sc = 1f
        if (bg != null && !clear) {
            art = bg.art
            if (art != null) {
                getLocationOnScreen(loc)
                bg.getLocationOnScreen(bgLoc)
                ox = (bgLoc[0] - loc[0]).toFloat() + bg.coverDx
                oy = (bgLoc[1] - loc[1]).toFloat() + bg.coverDy
                sc = bg.coverScale
            }
        }
        val clearAlpha = if (night) 0.40f else 0.46f
        g.draw(
            canvas, w, h, effectiveRadius(), night, art, tintStrength, clearAlpha,
            tone == Tone.ACCENT, touchX, touchY, pressAmt, density, ox, oy, sc
        )
    }

    private fun drawFallback(canvas: Canvas, w: Float, h: Float, bg: GlassBackgroundView?) {
        canvas.save()
        canvas.clipPath(clip)

        val art = bg?.art
        if (bg != null && art != null && !clear) {
            getLocationOnScreen(loc)
            bg.getLocationOnScreen(bgLoc)
            val left = (bgLoc[0] - loc[0]).toFloat() + bg.coverDx
            val top = (bgLoc[1] - loc[1]).toFloat() + bg.coverDy
            dst.set(left, top, left + art.width * bg.coverScale, top + art.height * bg.coverScale)
            canvas.drawBitmap(art, null, dst, blurPaint)
        }

        canvas.drawRect(body, tintPaint)
        canvas.drawRect(0f, 0f, w, h * 0.5f, highlightPaint)
        if (pressAmt > 0f) {
            val a = (pressAmt * 0.30f * 255f).toInt().coerceIn(0, 255)
            glowPaint.shader = RadialGradient(
                touchX, touchY, maxOf(w, h) * 0.55f,
                Color.argb(a, 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(body, glowPaint)
        }
        canvas.restore()

        val r = effectiveRadius()
        canvas.drawRoundRect(edgeRect, r, r, edgePaint)
    }

    // ------------------------------------------------------------------ sentuhan

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (pressable) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = ev.x
                    touchY = ev.y
                    animatePress(1f, 140L)
                }
                MotionEvent.ACTION_MOVE -> {
                    touchX = ev.x
                    touchY = ev.y
                    if (pressAmt > 0f) invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(0f, 420L)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun animatePress(target: Float, duration: Long) {
        pressAnim?.cancel()
        val a = ValueAnimator.ofFloat(pressAmt, target)
        a.duration = duration
        a.addUpdateListener {
            pressAmt = it.animatedValue as Float
            invalidate()
        }
        pressAnim = a
        a.start()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (!pressable) return
        if (pressed) {
            // Gaya iOS: saat disentuh, elemen MEMBESAR (bukan mengecil). Panel lebar tumbuh
            // lebih sedikit, tombol kecil lebih banyak, supaya selisih tepinya kira-kira sama.
            val grow = (context.dpf(9f) / width.coerceAtLeast(1)).coerceIn(0.025f, 0.085f)
            val sc = 1f + grow
            (parent as? android.view.ViewGroup)?.clipChildren = false
            ((parent as? View)?.parent as? android.view.ViewGroup)?.clipChildren = false
            animate().scaleX(sc).scaleY(sc)
                .setStartDelay(0L)
                .setDuration(260L)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        } else {
            animate().scaleX(1f).scaleY(1f)
                .setStartDelay(0L)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastX = Int.MIN_VALUE
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        pressAnim?.cancel()
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
            LayoutParams(Glass.WRAP, Glass.WRAP, Gravity.CENTER)
        )
    }
}
