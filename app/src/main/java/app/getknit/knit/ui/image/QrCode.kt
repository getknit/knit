package app.getknit.knit.ui.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/** Renders text (e.g. an identity [app.getknit.knit.mesh.crypto.VerifyPayload]) into a QR [ImageBitmap]. */
object QrCode {
    fun render(
        content: String,
        sizePx: Int,
    ): ImageBitmap? =
        runCatching {
            val matrix =
                MultiFormatWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    mapOf(EncodeHintType.MARGIN to 1),
                )
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap.asImageBitmap()
        }.getOrNull()
}
