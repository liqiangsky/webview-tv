package com.tvlive.web

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 本地画二维码：配对页和操作面板右栏都要用同一份逻辑。
 * 走 zxing 直接算 Bitmap，不调任何出图接口，配置地址不会离开内网。
 */
object Qr {

    fun bitmap(text: String, px: Int): Bitmap? = runCatching {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, px, px,
            mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 1,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
        )
        // 逐像素 setPixel 要十几万次调用，先在数组里拼好再一次刷进 Bitmap
        val w = m.width
        val h = m.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
