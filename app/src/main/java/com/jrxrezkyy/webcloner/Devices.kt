package com.jrxrezkyy.webcloner

import android.content.Context

enum class DeviceKind { PHONE, PHONE_HOME, TABLET, LAPTOP, MONITOR }

enum class DeviceOs { ANDROID, IOS, DESKTOP }

enum class DeviceCamera { NONE, NOTCH, ISLAND, PUNCH, HOME_BUTTON, WEBCAM }

/**
 * Satu perangkat tiruan. [w] x [h] adalah ukuran layar dalam px CSS, posisi tegak untuk
 * HP/tablet (w < h) dan posisi mendatar untuk laptop/monitor (w > h).
 */
class DevicePreset(
    val id: String,
    val name: String,
    val group: Int,
    val kind: DeviceKind,
    val os: DeviceOs,
    val w: Int,
    val h: Int,
    val camera: DeviceCamera
) {
    /** HP dan tablet boleh diputar; laptop dan monitor tidak. */
    val rotatable: Boolean get() = kind == DeviceKind.PHONE || kind == DeviceKind.PHONE_HOME || kind == DeviceKind.TABLET
}

object Devices {

    const val GROUP_ANDROID = 0
    const val GROUP_IOS = 1
    const val GROUP_DESKTOP = 2

    const val CUSTOM_ID = "custom"

    val all: List<DevicePreset> = listOf(
        // Android
        DevicePreset("pixel7", "Pixel 7", GROUP_ANDROID, DeviceKind.PHONE, DeviceOs.ANDROID, 412, 915, DeviceCamera.PUNCH),
        DevicePreset("s20u", "Galaxy S20 Ultra", GROUP_ANDROID, DeviceKind.PHONE, DeviceOs.ANDROID, 412, 915, DeviceCamera.PUNCH),
        DevicePreset("a51", "Galaxy A51", GROUP_ANDROID, DeviceKind.PHONE, DeviceOs.ANDROID, 412, 914, DeviceCamera.PUNCH),
        DevicePreset("s8p", "Galaxy S8+", GROUP_ANDROID, DeviceKind.PHONE, DeviceOs.ANDROID, 360, 740, DeviceCamera.NONE),
        DevicePreset("nexus10", "Tablet Android (Nexus 10)", GROUP_ANDROID, DeviceKind.TABLET, DeviceOs.ANDROID, 800, 1280, DeviceCamera.NONE),
        // iOS
        DevicePreset("se", "iPhone SE", GROUP_IOS, DeviceKind.PHONE_HOME, DeviceOs.IOS, 375, 667, DeviceCamera.HOME_BUTTON),
        DevicePreset("i8", "iPhone 8", GROUP_IOS, DeviceKind.PHONE_HOME, DeviceOs.IOS, 375, 667, DeviceCamera.HOME_BUTTON),
        DevicePreset("i8p", "iPhone 8 Plus", GROUP_IOS, DeviceKind.PHONE_HOME, DeviceOs.IOS, 414, 736, DeviceCamera.HOME_BUTTON),
        DevicePreset("ix", "iPhone X", GROUP_IOS, DeviceKind.PHONE, DeviceOs.IOS, 375, 812, DeviceCamera.NOTCH),
        DevicePreset("i12p", "iPhone 12 Pro", GROUP_IOS, DeviceKind.PHONE, DeviceOs.IOS, 390, 844, DeviceCamera.NOTCH),
        DevicePreset("i15p", "iPhone 15 Pro", GROUP_IOS, DeviceKind.PHONE, DeviceOs.IOS, 393, 852, DeviceCamera.ISLAND),
        DevicePreset("i14pm", "iPhone 14 Pro Max", GROUP_IOS, DeviceKind.PHONE, DeviceOs.IOS, 430, 932, DeviceCamera.ISLAND),
        DevicePreset("ipadmini", "iPad Mini", GROUP_IOS, DeviceKind.TABLET, DeviceOs.IOS, 768, 1024, DeviceCamera.NONE),
        DevicePreset("ipadair", "iPad Air", GROUP_IOS, DeviceKind.TABLET, DeviceOs.IOS, 820, 1180, DeviceCamera.NONE),
        DevicePreset("ipadpro", "iPad Pro 12.9", GROUP_IOS, DeviceKind.TABLET, DeviceOs.IOS, 1024, 1366, DeviceCamera.NONE),
        // Laptop dan monitor
        DevicePreset("lap", "Laptop", GROUP_DESKTOP, DeviceKind.LAPTOP, DeviceOs.DESKTOP, 1366, 768, DeviceCamera.WEBCAM),
        DevicePreset("lapl", "Laptop L", GROUP_DESKTOP, DeviceKind.LAPTOP, DeviceOs.DESKTOP, 1440, 900, DeviceCamera.WEBCAM),
        DevicePreset("lapxl", "Laptop XL", GROUP_DESKTOP, DeviceKind.LAPTOP, DeviceOs.DESKTOP, 1920, 1080, DeviceCamera.WEBCAM),
        DevicePreset("monfhd", "Monitor PC Full HD", GROUP_DESKTOP, DeviceKind.MONITOR, DeviceOs.DESKTOP, 1920, 1080, DeviceCamera.NONE),
        DevicePreset("monqhd", "Monitor PC QHD", GROUP_DESKTOP, DeviceKind.MONITOR, DeviceOs.DESKTOP, 2560, 1440, DeviceCamera.NONE),
        DevicePreset("uhd", "Monitor UHD 4K", GROUP_DESKTOP, DeviceKind.MONITOR, DeviceOs.DESKTOP, 3840, 2160, DeviceCamera.NONE)
    )

    fun byId(id: String?): DevicePreset? = all.firstOrNull { it.id == id }

    fun inGroup(group: Int): List<DevicePreset> = all.filter { it.group == group }

    /** Perangkat kustom: bentuknya mengikuti ukuran yang dipilih pengguna. */
    fun custom(w: Int, h: Int): DevicePreset {
        val cw = w.coerceIn(240, 3840)
        val ch = h.coerceIn(320, 2160)
        val kind = when {
            cw >= 1000 && cw > ch -> DeviceKind.LAPTOP
            minOf(cw, ch) < 560 -> DeviceKind.PHONE
            else -> DeviceKind.TABLET
        }
        val os = if (kind == DeviceKind.LAPTOP) DeviceOs.DESKTOP else DeviceOs.ANDROID
        val camera = when (kind) {
            DeviceKind.LAPTOP -> DeviceCamera.WEBCAM
            DeviceKind.PHONE -> DeviceCamera.PUNCH
            else -> DeviceCamera.NONE
        }
        return DevicePreset(CUSTOM_ID, "Kustom", GROUP_DESKTOP, kind, os, cw, ch, camera)
    }

    /** Perangkat yang dipilih untuk situs ini, atau null (tanpa bingkai). */
    fun forSite(c: Context, siteId: String): DevicePreset? {
        val id = SiteStore.device(c, siteId) ?: return null
        if (id == CUSTOM_ID) {
            return custom(SiteStore.customW(c, siteId), SiteStore.customH(c, siteId))
        }
        return byId(id)
    }
}
