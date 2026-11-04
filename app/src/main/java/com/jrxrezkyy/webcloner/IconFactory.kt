package com.jrxrezkyy.webcloner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Membuat ikon bulat untuk daftar situs dan pintasan layar utama. */
object IconFactory {

    private val palette = intArrayOf(
        0xFF2563EB.toInt(),
        0xFF7C3AED.toInt(),
        0xFFDB2777.toInt(),
        0xFFEA580C.toInt(),
        0xFF059669.toInt(),
        0xFF0891B2.toInt()
    )

    /**
     * Jika [favicon] ada: favicon di atas lingkaran putih.
     * Jika tidak: lingkaran berwarna dengan huruf pertama host.
     */
    fun create(host: String, favicon: Bitmap?, size: Int = 192): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f

        if (favicon != null) {
            paint.color = Color.WHITE
            canvas.drawCircle(r, r, r, paint)
            val target = size * 0.55f
            val dst = RectF(r - target / 2, r - target / 2, r + target / 2, r + target / 2)
            paint.isFilterBitmap = true
            canvas.drawBitmap(favicon, null, dst, paint)
        } else {
            paint.color = palette[(host.hashCode() and 0x7fffffff) % palette.size]
            canvas.drawCircle(r, r, r, paint)
            paint.color = Color.WHITE
            paint.textSize = size * 0.5f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            val letter = host.firstOrNull { it.isLetterOrDigit() }
                ?.uppercaseChar()?.toString() ?: "W"
            val y = r - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(letter, r, y, paint)
        }
        return bitmap
    }
}
