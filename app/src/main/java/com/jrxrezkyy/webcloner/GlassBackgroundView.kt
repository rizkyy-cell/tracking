package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import java.lang.ref.WeakReference

/**
 * Latar layar. Selain menggambar seni cairan, ia menyimpan versi KABUR-nya ([blur]) yang
 * dipakai tiap [GlassPanel] sebagai "kaca": panel menggambar potongan blur yang tepat berada
 * di belakangnya. Cara ini jauh lebih murah daripada mengaburkan isi layar secara langsung
 * dan berjalan di semua versi Android.
 */
class GlassBackgroundView(context: Context) : View(context) {

    var art: Bitmap? = null
        private set
    var blur: Bitmap? = null
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    init {
        id = R.id.glass_bg
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val night = Glass.night(context)
        val key = "${w}x${h}x$night"
        val hit = cache[key]
        if (hit != null) {
            art = hit.first
            blur = hit.second
        } else {
            val a = GlassArt.render(maxOf(1, w / 2), maxOf(1, h / 2), night)
            val small = GlassArt.render(maxOf(8, w / 8), maxOf(8, h / 8), night)
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            BoxBlurCore.blur(px, small.width, small.height, 4, 3)
            small.setPixels(px, 0, small.width, 0, 0, small.width, small.height)
            if (cache.size >= 4) cache.clear()
            cache[key] = Pair(a, small)
            art = a
            blur = small
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = WeakReference(this)
    }

    override fun onDraw(canvas: Canvas) {
        val a = art ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(a, null, dst, paint)
        last = WeakReference(this)
    }

    companion object {
        /** Latar terakhir yang tampil; dipakai dialog (jendela terpisah) untuk menyalin kaca. */
        var last: WeakReference<GlassBackgroundView>? = null

        private val cache = HashMap<String, Pair<Bitmap, Bitmap>>()
    }
}
