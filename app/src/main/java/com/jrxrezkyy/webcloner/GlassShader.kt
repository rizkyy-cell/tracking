package com.jrxrezkyy.webcloner

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/**
 * Kaca cair sungguhan di GPU (Android 13+, AGSL). Meniru sifat yang dijelaskan Apple untuk
 * Liquid Glass:
 *  - lensing: di dekat tepi, warna latar dibelokkan keluar (bayangan tepi seperti lensa tebal)
 *    dengan sedikit pemisahan warna (chromatic aberration),
 *  - sorot specular tipis yang mengikuti bentuk, terang di sisi yang menghadap cahaya (kiri atas),
 *  - tepi gelap tipis di sisi sebaliknya (perubahan iOS 27 untuk kedalaman),
 *  - tint adaptif ringan + saturasi sedikit dinaikkan,
 *  - cahaya dari bawah yang menyebar dari titik sentuh.
 * Rumus yang sama dipakai di pratinjau numpy saat menyetel tampilan.
 */
object GlassShaderSource {
    const val AGSL = """
uniform shader bg;
uniform float2 size;
uniform float radius;
uniform float night;
uniform float useBg;
uniform float tint;
uniform float clearAlpha;
uniform float3 accent;
uniform float accentAmt;
uniform float2 touch;
uniform float press;
uniform float dp;
uniform float2 bgOrigin;
uniform float bgScale;

float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float3 sampleBg(float2 q) {
    return float3(bg.eval((q - bgOrigin) / bgScale).rgb);
}

half4 main(float2 fc) {
    float2 hs = size * 0.5;
    float2 p = fc - hs;
    float r = min(radius, min(hs.x, hs.y));
    float d = sdRound(p, hs, r);
    float cover = 1.0 - smoothstep(-0.75, 0.75, d);
    if (cover <= 0.0) {
        return half4(0.0);
    }
    float depth = -d;
    float2 g = float2(
        sdRound(p + float2(1.0, 0.0), hs, r) - sdRound(p - float2(1.0, 0.0), hs, r),
        sdRound(p + float2(0.0, 1.0), hs, r) - sdRound(p - float2(0.0, 1.0), hs, r));
    float2 n = g / max(length(g), 0.0001);

    float rimW = min(14.0 * dp, min(hs.x, hs.y) * 0.9);
    float t = clamp(1.0 - depth / rimW, 0.0, 1.0);
    float off = rimW * 0.85 * pow(t, 2.2);

    float3 col = float3(1.0);
    float aBase = clearAlpha;
    if (night > 0.5) {
        col = float3(0.12, 0.15, 0.30);
    }
    if (useBg > 0.5) {
        float cr = sampleBg(fc + n * off).r;
        float cg = sampleBg(fc + n * off * 1.1).g;
        float cb = sampleBg(fc + n * off * 1.2).b;
        float3 c = float3(cr, cg, cb);
        float lum = dot(c, float3(0.299, 0.587, 0.114));
        c = float3(lum) + (c - float3(lum)) * 1.15;
        float v = fc.y / size.y;
        if (night < 0.5) {
            c = c + (float3(1.0) - c) * (0.32 * tint);
            c = c + (float3(1.0) - c) * (0.10 * (1.0 - v));
        } else {
            c = c + (float3(0.10, 0.13, 0.26) - c) * (0.35 * tint) + float3(0.03);
        }
        col = c;
        aBase = 1.0;
    }

    col = col + (accent - col) * accentAmt;
    aBase = max(aBase, accentAmt);

    float2 dt = fc - touch;
    float sig = max(size.x, size.y) * 0.35;
    float gl = press * exp(-dot(dt, dt) / (2.0 * sig * sig));
    col = col + (float3(1.0) - col) * (0.28 * gl);

    float2 L = normalize(float2(-0.6, -0.8));
    float facing = dot(n, L);
    float hair = 1.0 - smoothstep(0.0, 1.6 * dp, depth);
    float specTop = pow(max(facing, 0.0), 1.6);
    float specBot = pow(max(-facing, 0.0), 2.0) * 0.45;
    float spec = (specTop + specBot) * hair * 0.95;
    float glow = (1.0 - smoothstep(0.0, 9.0 * dp, depth)) * (0.10 + 0.22 * specTop);
    float hl = clamp(spec + glow, 0.0, 1.0);

    float darkK = 0.10;
    if (night > 0.5) {
        darkK = 0.25;
    }
    float rimDark = (1.0 - smoothstep(0.0, 2.2 * dp, depth)) * max(-facing, 0.0) * darkK;
    col = col * (1.0 - rimDark);

    float3 rgb = col * aBase * (1.0 - hl) + float3(hl);
    float a = aBase + hl * (1.0 - aBase);
    return half4(half3(rgb * cover), half(a * cover));
}
"""
}

/** Pembungkus RuntimeShader. Hanya boleh dibuat di Android 13+ (dijaga oleh pemanggil). */
@RequiresApi(33)
class GpuGlass {

    private val shader = RuntimeShader(GlassShaderSource.AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bound: Bitmap? = null
    private var boundShader: BitmapShader? = null

    init {
        paint.shader = shader
        // RuntimeShader mewajibkan semua shader anak terpasang, walau tidak dipakai (kaca bening).
        val dummy = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        shader.setInputShader("bg", BitmapShader(dummy, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        radius: Float,
        night: Boolean,
        art: Bitmap?,
        tint: Float,
        clearAlpha: Float,
        accent: Boolean,
        touchX: Float,
        touchY: Float,
        press: Float,
        dp: Float,
        originX: Float,
        originY: Float,
        scale: Float
    ) {
        if (art != null && art !== bound) {
            val s = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            boundShader = s
            bound = art
            shader.setInputShader("bg", s)
        }
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("night", if (night) 1f else 0f)
        shader.setFloatUniform("useBg", if (art != null) 1f else 0f)
        shader.setFloatUniform("tint", tint)
        shader.setFloatUniform("clearAlpha", clearAlpha)
        if (night) {
            shader.setFloatUniform("accent", 0.22f, 0.45f, 0.98f)
        } else {
            shader.setFloatUniform("accent", 0.145f, 0.388f, 0.922f)
        }
        shader.setFloatUniform("accentAmt", if (accent) 0.86f else 0f)
        shader.setFloatUniform("touch", touchX, touchY)
        shader.setFloatUniform("press", press)
        shader.setFloatUniform("dp", dp)
        shader.setFloatUniform("bgOrigin", originX, originY)
        shader.setFloatUniform("bgScale", if (scale > 0f) scale else 1f)
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}
