package dev.immichtv.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 生成二维码并显示（用于在电视屏幕上展示内置服务器地址）。
 * text 为空时不绘制，由调用方显示占位提示。
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, sizePx: Int = 640) {
    if (text.isEmpty()) return
    val bitmap = remember(text, sizePx) { qrcodeBitmap(text, sizePx) }
    Image(bitmap.asImageBitmap(), contentDescription = text, modifier = modifier)
}

private fun qrcodeBitmap(text: String, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val matrix = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints())
    } catch (e: Exception) {
        // 生成失败时给一块浅灰占位，避免屏幕上一块黑
        bitmap.setPixels(
            IntArray(sizePx * sizePx) { AndroidColor.parseColor("#F2F2F2") },
            0, sizePx, 0, 0, sizePx, sizePx,
        )
        return bitmap
    }

    val black = AndroidColor.BLACK
    val white = AndroidColor.WHITE
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[y * sizePx + x] = if (matrix.get(x, y)) black else white
        }
    }
    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    return bitmap
}

private fun hints(): Map<EncodeHintType, *> = mapOf(
    EncodeHintType.MARGIN to 1,
    EncodeHintType.CHARACTER_SET to "UTF-8",
)
