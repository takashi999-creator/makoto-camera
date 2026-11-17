package app.makoto.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import app.makoto.camera.core.ImageAnalyzer
import app.makoto.camera.core.QrPayload
import app.makoto.camera.core.Sealer
import app.makoto.camera.core.Spec
import app.makoto.camera.core.WatermarkEmbedder
import app.makoto.camera.core.WmMath
import app.makoto.camera.core.toHex
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

class ShotSettings(
    val stamp: Boolean,      // 写真に場所や時刻を表示するか
    val qr: Boolean,         // 表示の帯に、簡易確認用の QR を入れるか
    val frontLens: Boolean,
    val zoom: Float,
    val ev: Float,           // 露出補正(EV)
    val flash: String,       // off / auto / on
    val ratio: String        // 4:3 / 16:9 / 1:1
)

class SavedShot(val uri: Uri, val idHex: String)

object PhotoPipeline {
    const val APP_VERSION = "0.1.0"
    const val STRIP = 128

    /** カメラから受け取った JPEG を、表示入れ → 透かし → Exif → 署名 → 保存 の順に処理する。 */
    fun process(context: Context, captured: ByteArray, rotationDegrees: Int, loc: Location?, s: ShotSettings): SavedShot {
        val timeMs = System.currentTimeMillis()
        val id = SecureRandom().nextLong() and 0xFFFFFFFFFFFFL
        val idHex = WmMath.idBytes(id).toHex()

        // 1. デコードして正しい向きに回す(向きを画素に焼き込むと、再保存後も透かしが読める)
        var bmp = decodeUpright(captured, rotationDegrees)
        if (s.ratio == "1:1" && bmp.width != bmp.height) {
            // 1:1 は 4:3 で撮って中央を正方形に切り出す
            val side = Math.min(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            if (sq !== bmp) bmp.recycle()
            bmp = if (sq.isMutable) sq else sq.copy(Bitmap.Config.ARGB_8888, true).also { sq.recycle() }
        }
        val w = bmp.width
        val h = bmp.height

        // 2. 表示(非表示にできる)
        val timeJst = Format.jst(timeMs, "yyyy-MM-dd'T'HH:mm:ss") + "+09:00"
        if (s.stamp) {
            val qrText = if (!s.qr) null else QrPayload.build(
                WmMath.idBytes(id), timeMs, loc?.latitude, loc?.longitude, loc?.let { Format.altitude(it)?.first },
                { DeviceKey.sign(it) }, DeviceKey.publicKey(), { QrCode.enc(it) })
            Stamp.draw(bmp, timeMs, loc, qrText)
        }

        // 3. 透かし。先に絵柄を調べて、ビットごとの強さを決める
        val buf = IntArray(w * STRIP)
        val host = ImageAnalyzer(w, h)
        forStrips(h) { y, n -> bmp.getPixels(buf, 0, w, 0, y, w, n); host.feed(buf, y, n) }
        val embedder = WatermarkEmbedder(w, h, WmMath.planCells(host.cellEstimates(), host.cellActivity(), id))
        val marked = ImageAnalyzer(w, h)
        forStrips(h) { y, n ->
            bmp.getPixels(buf, 0, w, 0, y, w, n)
            embedder.apply(buf, y, n)
            bmp.setPixels(buf, 0, w, 0, y, w, n)
            marked.feed(buf, y, n)
        }

        // 4. JPEG にして Exif を書く
        val bo = ByteArrayOutputStream(w * h / 4)
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, bo)
        bmp.recycle()
        val device = (Build.MANUFACTURER + " " + Build.MODEL).trim()
        val jpeg = writeExif(context, bo.toByteArray(), timeMs, loc, idHex, w, h)

        // 5. 記録を作って署名し、JPEG に入れる
        val f = LinkedHashMap<String, Any?>()
        f["v"] = 1
        f["app"] = Spec.APP_NAME
        f["appVersion"] = APP_VERSION
        f["platform"] = "android"
        f["shotId"] = idHex
        f["timeJst"] = timeJst
        f["timeMs"] = timeMs
        if (loc != null) {
            f["lat"] = loc.latitude
            f["lon"] = loc.longitude
            Format.altitude(loc)?.let { f["alt"] = it.first; f["altType"] = it.second }
            if (loc.hasAccuracy()) f["acc"] = loc.accuracy
            f["gpsTimeMs"] = loc.time
            f["mock"] = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider
        }
        f["device"] = device
        f["os"] = "Android " + Build.VERSION.RELEASE
        f["lens"] = if (s.frontLens) "front" else "back"
        f["zoom"] = s.zoom
        f["ev"] = s.ev
        f["flash"] = s.flash
        f["stamp"] = s.stamp
        f["qr"] = s.stamp && s.qr
        f["ratio"] = s.ratio
        f["w"] = w
        f["h"] = h
        f["map"] = Base64.encodeToString(marked.map(), Base64.NO_WRAP)

        val (sealed, record) = Sealer.seal(jpeg, f, { DeviceKey.sign(it) }, DeviceKey.publicKey())

        // 6. 保存(バイト列をそのまま書く)と、端末内の控え
        val uri = saveToGallery(context, sealed, "MAKOTO_" + Format.jst(timeMs, "yyyyMMdd_HHmmss") + "_" + idHex.take(4) + ".jpg")
        RecordStore.save(context, idHex, record.pack())
        return SavedShot(uri, idHex)
    }

    inline fun forStrips(h: Int, body: (Int, Int) -> Unit) {
        var y = 0
        while (y < h) {
            val n = Math.min(STRIP, h - y)
            body(y, n)
            y += n
        }
    }

    private fun decodeUpright(jpeg: ByteArray, rotationDegrees: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: throw IllegalStateException("decode failed")
        if (rotationDegrees % 360 != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        if (!bmp.isMutable || bmp.config != Bitmap.Config.ARGB_8888) {
            val c = bmp.copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle()
            bmp = c
        }
        return bmp
    }

    private fun writeExif(context: Context, jpeg: ByteArray, timeMs: Long, loc: Location?, idHex: String, w: Int, h: Int): ByteArray {
        val tmp = File.createTempFile("shot", ".jpg", context.cacheDir)
        try {
            tmp.writeBytes(jpeg)
            val exif = ExifInterface(tmp.absolutePath)
            val dt = Format.jst(timeMs, "yyyy:MM:dd HH:mm:ss")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dt)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+09:00")
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, "+09:00")
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, "+09:00")
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "MakotoCamera " + APP_VERSION)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "MakotoCamera shot " + idHex)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            if (loc != null) {
                exif.setLatLong(loc.latitude, loc.longitude)
                Format.altitude(loc)?.let { exif.setAltitude(it.first) }
            }
            exif.saveAttributes()
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    private fun saveToGallery(context: Context, bytes: ByteArray, name: String): Uri {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MakotoCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("insert failed")
        try {
            cr.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
