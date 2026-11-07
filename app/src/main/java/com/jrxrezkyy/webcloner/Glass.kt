package com.jrxrezkyy.webcloner

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Identitas pembuat, dipakai di Pengaturan dan teks lisensi. */
object Brand {
    const val HANDLE = "@rizky._kurniawan25"
}

fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

/** Palet dan pembuat tampilan kecil untuk gaya "liquid glass". */
object Glass {

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    enum class Weight { REGULAR, MEDIUM, BOLD }

    fun night(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Teks utama: biru tua di mode terang, putih kebiruan di mode gelap. */
    fun ink(ctx: Context): Int = if (night(ctx)) 0xFFEAF0FF.toInt() else 0xFF0B2F7A.toInt()

    /** Teks pendukung. Kontras terhadap panel kaca >= 4.5:1. */
    fun soft(ctx: Context): Int = if (night(ctx)) 0xFFB3C1E6.toInt() else 0xFF3F5C96.toInt()

    fun danger(ctx: Context): Int = if (night(ctx)) 0xFFFF9AA8.toInt() else 0xFFC4203F.toInt()

    fun accent(ctx: Context): Int = if (night(ctx)) 0xFF6C93FF.toInt() else 0xFF2563EB.toInt()

    fun surface(ctx: Context): Int = if (night(ctx)) 0xFF0E1638.toInt() else 0xFFEAF1FF.toInt()

    fun hairline(ctx: Context): Int = if (night(ctx)) 0x26FFFFFF else 0x2A0B2F7A

    private fun rippleColor(ctx: Context): Int =
        if (night(ctx)) 0x33FFFFFF else 0x220B2F7A

    fun rippleRect(ctx: Context): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor(ctx)), null, ColorDrawable(Color.WHITE))

    fun rippleOval(ctx: Context): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor(ctx)),
            null,
            ShapeDrawable(OvalShape()).apply { paint.color = Color.WHITE }
        )

    fun text(
        ctx: Context,
        value: CharSequence?,
        sp: Float,
        color: Int,
        weight: Weight = Weight.REGULAR
    ): TextView = TextView(ctx).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = when (weight) {
            Weight.REGULAR -> Typeface.DEFAULT
            Weight.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            Weight.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    fun icon(ctx: Context, res: Int, sizeDp: Int, color: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Tombol ikon bulat berukuran minimal 44dp (area sentuh) dengan riak. */
    fun iconButton(
        ctx: Context,
        res: Int,
        description: String,
        color: Int,
        sizeDp: Int = 44,
        iconDp: Int = 22,
        onClick: (() -> Unit)? = null
    ): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = ctx.dp((sizeDp - iconDp) / 2)
        setPadding(pad, pad, pad, pad)
        background = rippleOval(ctx)
        contentDescription = description
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun centered(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER }
}
