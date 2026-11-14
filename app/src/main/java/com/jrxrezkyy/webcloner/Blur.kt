package com.jrxrezkyy.webcloner

import android.graphics.Bitmap

/**
 * Pengaburan kecil di CPU untuk potret layar berukuran mini (sekitar 1/6 layar), jadi murah.
 * Box blur diulang beberapa kali mendekati blur Gaussian.
 */
object Blur {

    /** Mengaburkan [bmp] (ARGB_8888, bisa diubah) di tempat. */
    fun box(bmp: Bitmap, radius: Int, passes: Int) {
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2 || radius < 1) return
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        for (i in 0 until passes) {
            horizontal(a, b, w, h, radius)
            vertical(b, a, w, h, radius)
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    /** Mencampur seluruh piksel ke arah [color] sebanyak [amount] (0..1); hasil opak. */
    fun blend(bmp: Bitmap, color: Int, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val k = (amount.coerceIn(0f, 1f) * 256f).toInt()
        val cr = (color shr 16) and 255
        val cg = (color shr 8) and 255
        val cb = color and 255
        for (i in px.indices) {
            val c = px[i]
            val r = (((c shr 16) and 255) * (256 - k) + cr * k) shr 8
            val g = (((c shr 8) and 255) * (256 - k) + cg * k) shr 8
            val bl = ((c and 255) * (256 - k) + cb * k) shr 8
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var rs = 0
            var gs = 0
            var bs = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                rs += (c shr 16) and 255
                gs += (c shr 8) and 255
                bs += c and 255
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val add = src[row + minOf(x + r + 1, w - 1)]
                val sub = src[row + maxOf(x - r, 0)]
                rs += ((add shr 16) and 255) - ((sub shr 16) and 255)
                gs += ((add shr 8) and 255) - ((sub shr 8) and 255)
                bs += (add and 255) - (sub and 255)
            }
        }
    }

    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var rs = 0
            var gs = 0
            var bs = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                rs += (c shr 16) and 255
                gs += (c shr 8) and 255
                bs += c and 255
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val add = src[minOf(y + r + 1, h - 1) * w + x]
                val sub = src[maxOf(y - r, 0) * w + x]
                rs += ((add shr 16) and 255) - ((sub shr 16) and 255)
                gs += ((add shr 8) and 255) - ((sub shr 8) and 255)
                bs += (add and 255) - (sub and 255)
            }
        }
    }
}
