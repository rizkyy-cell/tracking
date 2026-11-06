package com.jrxrezkyy.webcloner

/**
 * Box blur murni Kotlin (tanpa API Android) supaya bisa diuji di JVM.
 *
 * Mengapa box blur berulang: tiga kali box blur mendekati blur Gaussian, tetapi biayanya
 * O(piksel) tanpa bergantung pada radius (jumlah berjalan / running sum). Ini jalan di semua
 * versi Android, tanpa RenderEffect (Android 12+) yang tidak ada di HP lama.
 */
object BoxBlurCore {

    /** Mengaburkan [px] (format ARGB_8888, lebar [w], tinggi [h]) di tempat. */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int) {
        if (w <= 0 || h <= 0 || radius <= 0 || px.size < w * h) return
        val tmp = IntArray(w * h)
        repeat(passes) {
            pass(px, tmp, w, h, radius, true)
            pass(tmp, px, w, h, radius, false)
        }
    }

    private fun index(line: Int, i: Int, len: Int, w: Int, horizontal: Boolean): Int {
        val c = if (i < 0) 0 else if (i >= len) len - 1 else i
        return if (horizontal) line * w + c else c * w + line
    }

    internal fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[index(line, i, len, w, horizontal)]
                sa += p ushr 24
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (i in 0 until len) {
                dst[index(line, i, len, w, horizontal)] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[index(line, i + r + 1, len, w, horizontal)]
                val sub = src[index(line, i - r, len, w, horizontal)]
                sa += (add ushr 24) - (sub ushr 24)
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }
}
