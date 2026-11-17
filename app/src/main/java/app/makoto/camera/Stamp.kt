package app.makoto.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.os.Build
import app.makoto.camera.core.Spec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Format {
    private val tokyo: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")

    fun jst(ms: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = tokyo }.format(Date(ms))

    fun latLon(lat: Double, lon: Double): String =
        String.format(Locale.US, "%s %.6f°  %s %.6f°", if (lat >= 0) "北緯" else "南緯", Math.abs(lat), if (lon >= 0) "東経" else "西経", Math.abs(lon))

    /** 標高(m)と種類。API 34 以降で平均海面からの高さが取れればそれを使う。 */
    fun altitude(loc: Location): Pair<Double, String>? {
        if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) return Pair(loc.mslAltitudeMeters, "msl")
        if (loc.hasAltitude()) return Pair(loc.altitude, "ellipsoid")
        return null
    }
}

/** 写真の下端に入れる表示(場所、時刻、標高、アプリ名、AI編集不可ロゴ)。非表示にもできる。 */
object Stamp {
    fun lines(timeMs: Long, loc: Location?): List<String> {
        val out = ArrayList<String>()
        out.add(Format.jst(timeMs, "yyyy/MM/dd HH:mm:ss") + " 日本時間")
        if (loc != null) {
            out.add(Format.latLon(loc.latitude, loc.longitude))
            Format.altitude(loc)?.let { out.add(String.format(Locale.US, "標高 %.1f m", it.first)) }
        } else {
            out.add("位置情報なし")
        }
        return out
    }

    fun draw(bitmap: Bitmap, timeMs: Long, loc: Location?) {
        val c = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val u = Math.min(w, h) / 36f              // 基準の文字サイズ
        val pad = u * 0.8f
        val ls = lines(timeMs, loc)
        val bandH = pad * 2 + u * 1.35f * (ls.size + 1)

        val band = Paint().apply { color = 0x990E141B.toInt() }
        c.drawRect(0f, h - bandH, w, h, band)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = u; typeface = Typeface.DEFAULT }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = u * 1.05f; typeface = Typeface.DEFAULT_BOLD }

        // 1 行目: 盾マーク + アプリ名 + AI編集不可バッジ
        var y = h - bandH + pad + u
        val shield = u * 1.1f
        drawShield(c, pad, y - u * 0.95f, shield)
        var x = pad + shield + u * 0.4f
        c.drawText(Spec.APP_NAME, x, y, bold)
        x += bold.measureText(Spec.APP_NAME) + u * 0.7f
        val badge = "AI編集不可"
        val bw = bold.measureText(badge) + u * 0.8f
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = u * 0.08f }
        c.drawRoundRect(RectF(x, y - u * 1.0f, x + bw, y + u * 0.3f), u * 0.2f, u * 0.2f, frame)
        c.drawText(badge, x + u * 0.4f, y, bold)

        for (line in ls) {
            y += u * 1.35f
            c.drawText(line, pad, y, text)
        }
    }

    private fun drawShield(c: Canvas, x: Float, y: Float, s: Float) {
        val p = Path()
        p.moveTo(x + s * 0.5f, y)
        p.lineTo(x + s, y + s * 0.18f)
        p.lineTo(x + s, y + s * 0.55f)
        p.cubicTo(x + s, y + s * 0.85f, x + s * 0.75f, y + s * 1.05f, x + s * 0.5f, y + s * 1.15f)
        p.cubicTo(x + s * 0.25f, y + s * 1.05f, x, y + s * 0.85f, x, y + s * 0.55f)
        p.lineTo(x, y + s * 0.18f)
        p.close()
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        val check = Path()
        check.moveTo(x + s * 0.27f, y + s * 0.58f)
        check.lineTo(x + s * 0.45f, y + s * 0.76f)
        check.lineTo(x + s * 0.75f, y + s * 0.40f)
        c.drawPath(check, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2F80C2.toInt(); style = Paint.Style.STROKE; strokeWidth = s * 0.13f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
    }
}
