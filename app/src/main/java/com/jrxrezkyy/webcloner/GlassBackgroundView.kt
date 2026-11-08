package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.lang.ref.WeakReference

/**
 * Latar layar: foto yang sudah diburamkan kuat (res/drawable-nodpi dan drawable-night-nodpi),
 * digambar "cover" (memenuhi layar, dipotong di tepi). Bitmap yang sama dibaca oleh tiap
 * [GlassPanel] sebagai sumber kaca, sehingga panel melihat latar yang tepat di belakangnya
 * (termasuk dibelokkan di tepi oleh shader lensing).
 */
class GlassBackgroundView(context: Context) : View(context) {

    /** Bitmap latar dalam resolusi mendekati layar (supaya sampling GPU tidak berblok). */
    var art: Bitmap? = null
        private set

    /** Posisi kiri-atas bitmap di dalam view ini, dan skala (px view per px bitmap). */
    var coverDx = 0f
        private set
    var coverDy = 0f
        private set
    var coverScale = 1f
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    init {
        id = R.id.glass_bg
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private class Entry(val bitmap: Bitmap, val dx: Float, val dy: Float, val scale: Float)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val key = "${w}x${h}x${Glass.night(context)}"
        var entry = cache[key]
        if (entry == null) {
            val opts = BitmapFactory.Options()
            opts.inScaled = false
            val photo = BitmapFactory.decodeResource(resources, R.drawable.bg_photo, opts) ?: return
            val s = maxOf(w / photo.width.toFloat(), h / photo.height.toFloat())
            val dw = photo.width * s
            val dh = photo.height * s
            val tw = maxOf(2, (dw * 0.75f).toInt())
            val th = maxOf(2, (dh * 0.75f).toInt())
            val scaled = Bitmap.createScaledBitmap(photo, tw, th, true)
            if (scaled !== photo) photo.recycle()
            entry = Entry(scaled, (w - dw) / 2f, (h - dh) / 2f, dw / tw)
            if (cache.size >= 2) cache.clear()
            cache[key] = entry
        }
        art = entry.bitmap
        coverDx = entry.dx
        coverDy = entry.dy
        coverScale = entry.scale
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = WeakReference(this)
    }

    override fun onDraw(canvas: Canvas) {
        val a = art ?: return
        dst.set(coverDx, coverDy, coverDx + a.width * coverScale, coverDy + a.height * coverScale)
        canvas.drawBitmap(a, null, dst, paint)
        last = WeakReference(this)
    }

    companion object {
        /** Latar terakhir yang tampil; dipakai dialog (jendela terpisah) untuk menyalin kaca. */
        var last: WeakReference<GlassBackgroundView>? = null

        private val cache = HashMap<String, Entry>()
    }
}
