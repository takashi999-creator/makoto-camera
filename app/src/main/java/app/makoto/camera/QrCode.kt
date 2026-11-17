package app.makoto.camera

import android.graphics.Bitmap
import android.util.Base64
import app.makoto.camera.core.QrInfo
import app.makoto.camera.core.QrPayload
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 見える QR の作成と読み取り */
object QrCode {
    private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun enc(b: ByteArray): String = Base64.encodeToString(b, B64)
    fun dec(s: String): ByteArray = Base64.decode(s, B64)

    /** QR の白黒の升目(余白なし、最小サイズ) */
    fun matrix(text: String): BitMatrix {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0)
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    /** 画像から QR を探して文字列を返す。なければ null。 */
    fun read(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: Exception) { null }

    fun read(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return read(RGBLuminanceSource(w, h, px))
    }

    /** マコトカメラの QR なら内容を返す(署名の検証つき)。 */
    fun parse(text: String?): QrInfo? = if (text == null) null else QrPayload.parse(text) { dec(it) }
}
