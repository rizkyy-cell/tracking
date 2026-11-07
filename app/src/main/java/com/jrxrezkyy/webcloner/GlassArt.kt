package com.jrxrezkyy.webcloner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Latar "cairan": gradien biru lembut + pita berkilau yang melengkung, mengikuti gambar
 * referensi. Semua koordinat berupa pecahan lebar/tinggi sehingga hasilnya sama di layar
 * berukuran berapa pun (termasuk jendela mengambang).
 */
object GlassArt {

    private class Ribbon(
        val p: FloatArray,
        val width: Float,
        val light: IntArray,
        val dark: IntArray
    )

    private val ribbons = listOf(
        Ribbon(
            floatArrayOf(-0.15f, 0.30f, 0.20f, 0.06f, 0.55f, 0.56f, 1.15f, 0.26f), 0.15f,
            intArrayOf(0xFF1D4DFF.toInt(), 0xFF5C86FF.toInt(), 0xFFA9B8FF.toInt()),
            intArrayOf(0xFF2A4DFF.toInt(), 0xFF5A66FF.toInt(), 0xFF7B6DFF.toInt())
        ),
        Ribbon(
            floatArrayOf(-0.15f, 0.80f, 0.25f, 0.60f, 0.65f, 1.02f, 1.15f, 0.70f), 0.11f,
            intArrayOf(0xFF8A6CFF.toInt(), 0xFF5AAEFF.toInt(), 0xFF9ADBFF.toInt()),
            intArrayOf(0xFF6A4DFF.toInt(), 0xFF2F8CFF.toInt(), 0xFF28C0FF.toInt())
        ),
        Ribbon(
            floatArrayOf(0.50f, -0.12f, 0.42f, 0.10f, 0.92f, 0.14f, 1.15f, -0.04f), 0.06f,
            intArrayOf(0xFF6FA0FF.toInt(), 0xFFC6D2FF.toInt()),
            intArrayOf(0xFF4C7DFF.toInt(), 0xFF9AA8FF.toInt())
        )
    )

    fun render(w: Int, h: Int, night: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(1, w), maxOf(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = bmp.width.toFloat()
        val hf = bmp.height.toFloat()

        val base = if (night) {
            intArrayOf(0xFF0A1230.toInt(), 0xFF101C48.toInt(), 0xFF1B1946.toInt())
        } else {
            intArrayOf(0xFFDCE8FF.toInt(), 0xFFC6D8FF.toInt(), 0xFFE4E0FF.toInt())
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 0f, hf, base, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, p)

        if (night) {
            glow(c, wf, hf, 0.92f, 0.06f, 0.85f, 70, 110, 255, 0.35f)
            glow(c, wf, hf, 0.05f, 0.95f, 0.80f, 120, 80, 255, 0.30f)
        } else {
            glow(c, wf, hf, 0.92f, 0.04f, 0.85f, 255, 255, 255, 0.75f)
            glow(c, wf, hf, 0.05f, 0.96f, 0.80f, 206, 190, 255, 0.60f)
        }

        for (r in ribbons) drawRibbon(c, wf, hf, r, night)
        return bmp
    }

    /** Cahaya radial; jatuhnya kecerahan dibuat mirip kurva (1 - d)^1.6. */
    private fun glow(
        c: Canvas, w: Float, h: Float,
        cx: Float, cy: Float, r: Float,
        red: Int, green: Int, blue: Int, a: Float
    ) {
        fun col(f: Float): Int =
            Color.argb((255f * a * f).toInt().coerceIn(0, 255), red, green, blue)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            cx * w, cy * h, r * w,
            intArrayOf(col(1f), col(0.44f), col(0.146f), col(0f)),
            floatArrayOf(0f, 0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
    }

    private fun drawRibbon(c: Canvas, w: Float, h: Float, r: Ribbon, night: Boolean) {
        val q = r.p
        val path = Path()
        path.moveTo(q[0] * w, q[1] * h)
        path.cubicTo(q[2] * w, q[3] * h, q[4] * w, q[5] * h, q[6] * w, q[7] * h)

        val colors = if (night) r.dark else r.light
        val wpx = r.width * w

        fun stroke(width: Float, alpha: Int, dx: Float, dy: Float, white: Boolean) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = width
            if (white) {
                p.color = Color.WHITE
            } else {
                p.shader = LinearGradient(
                    q[0] * w, q[1] * h, q[6] * w, q[7] * h, colors, null, Shader.TileMode.CLAMP
                )
            }
            p.alpha = alpha
            c.save()
            c.translate(dx, dy)
            c.drawPath(path, p)
            c.restore()
        }

        stroke(wpx * 1.7f, if (night) 38 else 46, 0f, 0f, false)   // halo lembut
        stroke(wpx, 255, 0f, 0f, false)                              // badan pita
        stroke(wpx * 0.50f, if (night) 54 else 78, -0.10f * wpx, -0.08f * wpx, true) // inti terang
        stroke(wpx * 0.07f, if (night) 150 else 200, -0.26f * wpx, -0.20f * wpx, true) // kilap tipis
    }
}
