package app.makoto.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.makoto.camera.core.ImageAnalyzer
import app.makoto.camera.core.Spec
import app.makoto.camera.core.Verdict
import app.makoto.camera.core.Verifier
import app.makoto.camera.core.VerifyResult
import app.makoto.camera.core.sha256
import app.makoto.camera.core.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Checked(val result: VerifyResult, val preview: Bitmap?, val ownKeyId: String?)

private fun inspect(context: Context, uri: Uri): Checked {
    val file = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    // 全画素を読んで、透かしと変化マップを調べる
    var analyzer: ImageAnalyzer? = null
    val full = try {
        BitmapFactory.decodeByteArray(file, 0, file.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    } catch (e: Throwable) { null }
    if (full != null) {
        val w = full.width
        val h = full.height
        val a = ImageAnalyzer(w, h)
        val buf = IntArray(w * PhotoPipeline.STRIP)
        PhotoPipeline.forStrips(h) { y, n -> full.getPixels(buf, 0, w, 0, y, w, n); a.feed(buf, y, n) }
        full.recycle()
        analyzer = a
    }

    // 画面表示用の縮小画像
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(file, 0, file.size, bounds)
    var sample = 1
    while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val preview = try {
        BitmapFactory.decodeByteArray(file, 0, file.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) { null }

    val result = Verifier.verify(file, analyzer, { RecordStore.load(context, it) }, { Base64.decode(it, Base64.DEFAULT) })
    val own = try { sha256(DeviceKey.publicKey()).copyOf(8).toHex() } catch (e: Exception) { null }
    return Checked(result, preview, own)
}

@Composable
fun VerifyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Checked?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true; error = null; checked = null
            scope.launch {
                val r = withContext(Dispatchers.Default) { runCatching { inspect(context.applicationContext, uri) } }
                busy = false
                if (r.isSuccess) checked = r.getOrNull() else error = "読み込めませんでした: " + (r.exceptionOrNull()?.message ?: "")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Palette.Ink).systemBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("戻る", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("写真の判定", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Chip("写真を選ぶ", selected = true, enabled = !busy) { picker.launch("image/*") }
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            val c = checked
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("判定中", color = Palette.Text, fontSize = 14.sp)
                }
                error != null -> Text(error ?: "", color = Palette.Bad, fontSize = 14.sp)
                c == null -> Text(
                    "写真を選ぶと、このアプリで撮影されたままかどうかを調べます。\n\n" +
                        "LINE や SNS で送った写真は再圧縮されるため、原本とは判定されません。原本を渡すときは、ファイルとして送ってください。",
                    color = Palette.Sub, fontSize = 14.sp, lineHeight = 22.sp
                )
                else -> ResultView(c)
            }
        }
    }
}

@Composable
private fun ResultView(c: Checked) {
    val r = c.result
    val changed = r.changed
    val (color, title, body) = when (r.verdict) {
        Verdict.ORIGINAL -> Triple(Palette.Ok, "原本です", "このアプリで撮影されたままです。画像は 1 画素も変わっていません。")
        Verdict.EDITED -> Triple(
            Palette.Bad, "撮影後に変更されています",
            if (changed != null && r.changedCount > 0) "署名付きの記録は本物ですが、画像が撮影時と違います。赤い部分が変わった場所です。"
            else "署名付きの記録は本物ですが、画像が撮影時と違います。見た目の変化は小さく、再保存や再圧縮の可能性があります。"
        )
        Verdict.FORGED_RECORD -> Triple(Palette.Bad, "記録が壊れているか、偽造されています", "記録はありますが、署名が合いません。")
        Verdict.WATERMARK_ONLY -> Triple(
            Palette.Warn, "このアプリの写真ですが、原本ではありません",
            "透かしから撮影ID " + (r.watermark?.idHex ?: "") + " を読み取りました。再保存、SNS 送信、編集のいずれかを経ています。" +
                (if (r.fromLocalCopy) "この端末に残っている控えと照合しました。" else "この端末に控えがないため、撮影時の記録は表示できません。") +
                (if (changed != null && r.changedCount > 0) "赤い部分が変わった場所です。" else "")
        )
        Verdict.UNKNOWN -> Triple(Palette.Sub, "確認できません", "このアプリの記録も透かしも見つかりません。他のカメラの写真か、強い加工で透かしが消えた可能性があります。")
    }

    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(color))
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, color = Palette.Sub, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
    Spacer(Modifier.height(16.dp))

    val p = c.preview
    if (p != null) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(p.width.toFloat() / p.height.toFloat()).clip(RoundedCornerShape(8.dp))) {
            Image(bitmap = p.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (changed != null && r.changedCount > 0) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cw = size.width / Spec.MAP
                    val ch = size.height / Spec.MAP
                    for (i in changed.indices) if (changed[i]) {
                        drawRect(Color(0x88E5484D), topLeft = Offset((i % Spec.MAP) * cw, (i / Spec.MAP) * ch), size = Size(cw, ch))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    val rows = detailRows(r, c.ownKeyId)
    if (rows.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).padding(16.dp)) {
            Text(if (r.fromLocalCopy) "撮影時の記録(この端末の控え)" else "撮影時の記録(署名付き)", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            for ((k, v) in rows) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, color = Palette.Sub, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    Text(v, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "試作版の注意: 他の端末で署名された写真について、その鍵が正規のアプリのものかどうかまでは確認していません。" +
                "時刻は撮影した端末の時計、位置は端末の測位結果です。",
            color = Palette.Sub, fontSize = 12.sp, lineHeight = 18.sp
        )
    }
}

private fun detailRows(r: VerifyResult, ownKeyId: String?): List<Pair<String, String>> {
    val f = r.fields
    val out = ArrayList<Pair<String, String>>()
    if (f.isEmpty()) return out
    f["timeJst"]?.let { out.add("撮影日時" to it.replace("T", " ").replace("+09:00", "") + "(日本時間)") }
    val lat = f["lat"]?.toDoubleOrNull()
    val lon = f["lon"]?.toDoubleOrNull()
    if (lat != null && lon != null) {
        out.add("場所" to Format.latLon(lat, lon) + (f["acc"]?.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "(誤差 約%.0f m)", it) } ?: ""))
    } else out.add("場所" to "記録なし")
    f["alt"]?.toDoubleOrNull()?.let {
        out.add("標高" to String.format(java.util.Locale.US, "%.1f m", it) + if (f["altType"] == "ellipsoid") "(楕円体高)" else "")
    }
    if (f["mock"] == "true") out.add("位置の偽装" to "疑似位置情報が使われていました")
    f["device"]?.let { out.add("機種" to it + (f["os"]?.let { o -> " / $o" } ?: "")) }
    f["app"]?.let { out.add("アプリ" to it + " " + (f["appVersion"] ?: "")) }
    val lens = if (f["lens"] == "front") "内カメラ" else "外カメラ"
    val flash = when (f["flash"]) { "auto" -> "自動"; "on" -> "入"; else -> "切" }
    out.add("撮影設定" to lens + " / ズーム " + (f["zoom"]?.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.1fx", it) } ?: "-") +
        " / 明るさ " + (f["ev"]?.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%+.1f", it) } ?: "-") + " / フラッシュ " + flash)
    out.add("画面の表示" to if (f["stamp"] == "true") "あり" else "なし")
    f["shotId"]?.let { out.add("撮影ID" to it) }
    r.keyId?.let { out.add("署名した端末" to it + if (ownKeyId != null && ownKeyId == it) "(この端末)" else "(他の端末)") }
    return out
}
