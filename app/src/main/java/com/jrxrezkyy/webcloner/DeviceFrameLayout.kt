package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** Hasil hitung ukuran bingkai (semua dalam px). Koordinat "tegak" = sebelum diputar. */
class FrameGeo(
    val rot: Boolean,
    val cx: Float,
    val cy: Float,
    val f: Float,
    val body: RectF,
    val screen: RectF,
    val bodyR: Float,
    val screenR: Float,
    val rim: Float,
    val bezelTop: Float,
    val bezelBottom: Float,
    val extraTop: Float,
    val extraH: Float,
    val totalW: Float,
    val screenW: Int,
    val screenH: Int
)

object FrameMath {

    /**
     * Menghitung bingkai agar seluruh perangkat (termasuk dudukan) muat di area [aw] x [ah]
     * dengan bagian layar berbentuk persis seperti layar perangkat aslinya.
     */
    fun compute(p: DevicePreset, landscape: Boolean, aw: Int, ah: Int, margin: Float): FrameGeo {
        val rot = landscape && p.rotatable
        val fitW = (if (rot) ah else aw) - 2 * margin
        val fitH = (if (rot) aw else ah) - 2 * margin
        val pw = p.w.toFloat()
        val ph = p.h.toFloat()
        val u = minOf(pw, ph)

        var bezelSide: Float
        var bezelTop: Float
        var bezelBottom: Float
        var rim: Float
        var screenR: Float
        var bodyR: Float
        var extraH = 0f
        var baseW = 0f
        when (p.kind) {
            DeviceKind.PHONE -> {
                bezelSide = 0.035f * u; bezelTop = bezelSide; bezelBottom = bezelSide
                rim = 0.012f * u; screenR = 0.10f * u; bodyR = screenR + bezelSide + rim
            }
            DeviceKind.PHONE_HOME -> {
                bezelSide = 0.05f * u; bezelTop = 0.17f * u; bezelBottom = 0.19f * u
                rim = 0.012f * u; screenR = 0f; bodyR = 0.13f * u
            }
            DeviceKind.TABLET -> {
                bezelSide = 0.05f * u; bezelTop = bezelSide; bezelBottom = bezelSide
                rim = 0.010f * u; screenR = 0.025f * u; bodyR = 0.06f * u
            }
            DeviceKind.LAPTOP -> {
                bezelSide = 0.022f * pw; bezelTop = bezelSide; bezelBottom = 0.03f * pw
                rim = 0.004f * pw; screenR = 0f; bodyR = 0.014f * pw
                extraH = 0.03f * pw
            }
            DeviceKind.MONITOR -> {
                bezelSide = 0.008f * pw; bezelTop = bezelSide; bezelBottom = 0.03f * pw
                rim = 0.003f * pw; screenR = 0f; bodyR = 0.01f * pw
                extraH = 0.112f * pw
            }
        }
        val bodyWu = pw + 2 * (bezelSide + rim)
        val bodyHu = ph + bezelTop + bezelBottom + 2 * rim
        if (p.kind == DeviceKind.LAPTOP) baseW = bodyWu * 1.10f
        val totalWu = maxOf(bodyWu, baseW)
        val totalHu = bodyHu + extraH

        val f = minOf(fitW / totalWu, fitH / totalHu).coerceAtLeast(0.0001f)
        val cx = aw / 2f
        val cy = ah / 2f
        val bw = bodyWu * f
        val bh = bodyHu * f
        val th = totalHu * f
        val top = cy - th / 2f
        val body = RectF(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        val screen = RectF(
            body.left + (rim + bezelSide) * f,
            body.top + (rim + bezelTop) * f,
            body.right - (rim + bezelSide) * f,
            body.bottom - (rim + bezelBottom) * f
        )
        val sw = Math.round(screen.width())
        val sh = Math.round(screen.height())
        return FrameGeo(
            rot, cx, cy, f, body, screen,
            bodyR * f, screenR * f, rim * f, bezelTop * f, bezelBottom * f,
            body.bottom, extraH * f, totalWu * f,
            if (rot) sh else sw, if (rot) sw else sh
        )
    }
}

/**
 * Pembungkus halaman yang menggambar perangkat di sekelilingnya. Tanpa perangkat, isinya
 * mengisi seluruh area seperti biasa. Dengan perangkat, isi (anak pertama) dipasang tepat di
 * layar perangkat, dipotong mengikuti sudut layarnya, dan diberi bezel, poni, tombol, dll.
 */
class DeviceFrameLayout(ctx: Context) : FrameLayout(ctx) {

    private var preset: DevicePreset? = null
    private var landscape = false
    private var geo: FrameGeo? = null
    private var lastW = 0
    private var lastH = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()
    private val d = ctx.resources.displayMetrics.density

    /** Dipanggil setiap ukuran layar perangkat berubah (px). */
    var onScreenChanged: ((Int, Int) -> Unit)? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    fun setDevice(p: DevicePreset?, land: Boolean) {
        preset = p
        landscape = land
        requestLayout()
        invalidate()
    }

    val device: DevicePreset? get() = preset

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val child = getChildAt(0) ?: return
        val p = preset
        if (p == null || w <= 0 || h <= 0) {
            geo = null
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            return
        }
        val g = FrameMath.compute(p, landscape, w, h, 14 * d)
        geo = g
        child.measure(
            MeasureSpec.makeMeasureSpec(g.screenW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(g.screenH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        val g = geo
        if (g == null) {
            child.layout(0, 0, r - l, b - t)
            child.clipToOutline = false
            child.outlineProvider = ViewOutlineProvider.BACKGROUND
            notifyScreen(r - l, b - t)
            return
        }
        val left = Math.round(g.cx - g.screenW / 2f)
        val top = Math.round(g.cy - g.screenH / 2f)
        child.layout(left, top, left + g.screenW, top + g.screenH)
        val radius = g.screenR
        if (radius > 0.5f) {
            child.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            child.clipToOutline = true
            child.invalidateOutline()
        } else {
            child.clipToOutline = false
        }
        notifyScreen(g.screenW, g.screenH)
    }

    private fun notifyScreen(w: Int, h: Int) {
        if (w == lastW && h == lastH) return
        lastW = w
        lastH = h
        onScreenChanged?.invoke(w, h)
    }

    // ------------------------------------------------------------------ gambar

    override fun onDraw(canvas: Canvas) {
        val p = preset ?: return
        val g = geo ?: return
        canvas.save()
        if (g.rot) canvas.rotate(90f, g.cx, g.cy)
        drawBody(canvas, p, g)
        canvas.restore()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val p = preset ?: return
        val g = geo ?: return
        canvas.save()
        if (g.rot) canvas.rotate(90f, g.cx, g.cy)
        drawOverlay(canvas, p, g)
        canvas.restore()
    }

    private fun rimColors(os: DeviceOs, kind: DeviceKind): IntArray = when {
        kind == DeviceKind.LAPTOP || kind == DeviceKind.MONITOR ->
            intArrayOf(0xFFE6E8EC.toInt(), 0xFFA9AEB6.toInt(), 0xFFD9DCE1.toInt())
        os == DeviceOs.IOS -> intArrayOf(0xFFD9D4CB.toInt(), 0xFF8C887F.toInt(), 0xFFCFCAC0.toInt())
        else -> intArrayOf(0xFF5B5F68.toInt(), 0xFF23252A.toInt(), 0xFF4A4D55.toInt())
    }

    private fun drawBody(c: Canvas, p: DevicePreset, g: FrameGeo) {
        val u = minOf(p.w, p.h) * g.f
        val body = g.body
        paint.style = Paint.Style.FILL
        paint.shader = null

        // Bayangan lembut (beberapa lapis tipis, tanpa lapisan perangkat lunak).
        for (i in 6 downTo 1) {
            paint.color = (8 shl 24)
            tmp.set(body.left - i * 2 * d, body.top - i * d, body.right + i * 2 * d, body.bottom + i * 3 * d)
            c.drawRoundRect(tmp, g.bodyR + i * 2 * d, g.bodyR + i * 2 * d, paint)
        }

        val colors = rimColors(p.os, p.kind)

        // Perangkat dudukan di belakang bodi: dasar laptop atau kaki monitor.
        if (p.kind == DeviceKind.LAPTOP) {
            val baseTop = body.bottom - g.f * 0.002f * p.w
            val baseH = g.extraH
            val baseW = g.totalW
            path.reset()
            path.moveTo(g.cx - body.width() / 2f, baseTop)
            path.lineTo(g.cx + body.width() / 2f, baseTop)
            path.lineTo(g.cx + baseW / 2f, baseTop + baseH)
            path.lineTo(g.cx - baseW / 2f, baseTop + baseH)
            path.close()
            paint.shader = LinearGradient(
                0f, baseTop, 0f, baseTop + baseH,
                intArrayOf(0xFFC9CCD2.toInt(), 0xFF9EA3AB.toInt()), null, Shader.TileMode.CLAMP
            )
            c.drawPath(path, paint)
            paint.shader = null
            // Takik pembuka layar
            paint.color = 0x33000000
            tmp.set(g.cx - baseW * 0.06f, baseTop, g.cx + baseW * 0.06f, baseTop + baseH * 0.45f)
            c.drawRoundRect(tmp, baseH * 0.3f, baseH * 0.3f, paint)
        } else if (p.kind == DeviceKind.MONITOR) {
            val neckW = 0.09f * p.w * g.f
            val neckH = 0.10f * p.w * g.f
            val footW = 0.26f * p.w * g.f
            val footH = 0.012f * p.w * g.f
            paint.shader = LinearGradient(
                g.cx - neckW / 2f, 0f, g.cx + neckW / 2f, 0f,
                intArrayOf(0xFFB8BCC3.toInt(), 0xFFE4E6EA.toInt(), 0xFFA3A8B0.toInt()), null, Shader.TileMode.CLAMP
            )
            tmp.set(g.cx - neckW / 2f, body.bottom - 2 * d, g.cx + neckW / 2f, body.bottom + neckH)
            c.drawRect(tmp, paint)
            paint.shader = LinearGradient(
                0f, body.bottom + neckH, 0f, body.bottom + neckH + footH,
                intArrayOf(0xFFD9DCE1.toInt(), 0xFF9AA0A8.toInt()), null, Shader.TileMode.CLAMP
            )
            tmp.set(g.cx - footW / 2f, body.bottom + neckH - footH * 0.3f, g.cx + footW / 2f, body.bottom + neckH + footH)
            c.drawRoundRect(tmp, footH, footH, paint)
            paint.shader = null
        }

        // Tombol samping (daya di kanan, volume di kiri) untuk HP dan tablet.
        if (p.kind == DeviceKind.PHONE || p.kind == DeviceKind.PHONE_HOME || p.kind == DeviceKind.TABLET) {
            val bt = 0.008f * u
            paint.color = colors[1]
            tmp.set(body.right - 1f, body.top + body.height() * 0.20f, body.right + bt, body.top + body.height() * 0.29f)
            c.drawRoundRect(tmp, bt, bt, paint)
            tmp.set(body.left - bt, body.top + body.height() * 0.17f, body.left + 1f, body.top + body.height() * 0.23f)
            c.drawRoundRect(tmp, bt, bt, paint)
            tmp.set(body.left - bt, body.top + body.height() * 0.25f, body.left + 1f, body.top + body.height() * 0.31f)
            c.drawRoundRect(tmp, bt, bt, paint)
        }

        // Rangka logam
        paint.shader = LinearGradient(
            body.left, body.top, body.right, body.bottom, colors, null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(body, g.bodyR, g.bodyR, paint)
        paint.shader = null

        // Bezel hitam
        paint.color = 0xFF0A0A0C.toInt()
        tmp.set(body.left + g.rim, body.top + g.rim, body.right - g.rim, body.bottom - g.rim)
        c.drawRoundRect(tmp, (g.bodyR - g.rim).coerceAtLeast(0f), (g.bodyR - g.rim).coerceAtLeast(0f), paint)

        // Latar layar (terlihat sebentar sebelum halaman tampil)
        paint.color = 0xFF000000.toInt()
        c.drawRoundRect(g.screen, g.screenR, g.screenR, paint)

        // Perangkat pada bezel
        when (p.camera) {
            DeviceCamera.HOME_BUTTON -> {
                val r = g.bezelBottom * 0.30f
                val cy = g.screen.bottom + g.bezelBottom / 2f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f * d
                paint.color = 0xFF5A5A60.toInt()
                c.drawCircle(g.cx, cy, r, paint)
                paint.style = Paint.Style.FILL
                // Kamera dan speaker
                paint.color = 0xFF1E1E22.toInt()
                tmp.set(g.cx - g.screen.width() * 0.09f, g.screen.top - g.bezelTop * 0.45f, g.cx + g.screen.width() * 0.09f, g.screen.top - g.bezelTop * 0.45f + 4 * d)
                c.drawRoundRect(tmp, 2 * d, 2 * d, paint)
                paint.color = 0xFF16202E.toInt()
                c.drawCircle(g.cx - g.screen.width() * 0.17f, g.screen.top - g.bezelTop * 0.45f + 2 * d, 3 * d, paint)
            }
            DeviceCamera.WEBCAM -> {
                paint.color = 0xFF1B2230.toInt()
                c.drawCircle(g.cx, g.screen.top - g.bezelTop / 2f, maxOf(1.6f * d, g.bezelTop * 0.16f), paint)
            }
            else -> {}
        }
        paint.style = Paint.Style.FILL
    }

    /** Elemen yang menimpa layar: poni, pulau dinamis, lubang kamera, indikator beranda. */
    private fun drawOverlay(c: Canvas, p: DevicePreset, g: FrameGeo) {
        val s = g.screen
        val sw = s.width()
        val sh = s.height()
        paint.shader = null
        paint.style = Paint.Style.FILL
        when (p.camera) {
            DeviceCamera.NOTCH -> {
                val w = sw * 0.50f
                val h = sh * 0.038f
                paint.color = 0xFF000000.toInt()
                tmp.set(g.cx - w / 2f, s.top - h, g.cx + w / 2f, s.top + h)
                c.drawRoundRect(tmp, h * 0.9f, h * 0.9f, paint)
                paint.color = 0xFF16202E.toInt()
                c.drawCircle(g.cx + w * 0.26f, s.top + h * 0.38f, h * 0.18f, paint)
                tmp.set(g.cx - w * 0.14f, s.top + h * 0.28f, g.cx + w * 0.14f, s.top + h * 0.48f)
                paint.color = 0xFF1C1C20.toInt()
                c.drawRoundRect(tmp, h * 0.1f, h * 0.1f, paint)
            }
            DeviceCamera.ISLAND -> {
                val w = sw * 0.29f
                val h = sh * 0.039f
                val top = s.top + sh * 0.012f
                paint.color = 0xFF000000.toInt()
                tmp.set(g.cx - w / 2f, top, g.cx + w / 2f, top + h)
                c.drawRoundRect(tmp, h / 2f, h / 2f, paint)
                paint.color = 0xFF121826.toInt()
                c.drawCircle(g.cx + w * 0.30f, top + h / 2f, h * 0.17f, paint)
            }
            DeviceCamera.PUNCH -> {
                val r = sw * 0.017f
                paint.color = 0xFF000000.toInt()
                c.drawCircle(g.cx, s.top + r * 2.6f, r, paint)
                paint.color = 0xFF0F1622.toInt()
                c.drawCircle(g.cx, s.top + r * 2.6f, r * 0.45f, paint)
            }
            else -> {}
        }
        // Indikator beranda (iPhone tanpa tombol, dan Android bergerak)
        if (p.kind == DeviceKind.PHONE) {
            val w = sw * (if (p.os == DeviceOs.IOS) 0.36f else 0.30f)
            val h = maxOf(2f * d, sh * 0.0055f)
            paint.color = 0x99000000.toInt()
            tmp.set(g.cx - w / 2f, s.bottom - h * 2.6f, g.cx + w / 2f, s.bottom - h * 1.6f)
            c.drawRoundRect(tmp, h, h, paint)
        }
    }
}
