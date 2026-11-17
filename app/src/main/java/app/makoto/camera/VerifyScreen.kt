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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
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
import app.makoto.camera.core.QrInfo
import app.makoto.camera.core.Spec
import app.makoto.camera.core.Verdict
import app.makoto.camera.core.Verifier
import app.makoto.camera.core.VerifyResult
import app.makoto.camera.core.sha256
import app.makoto.camera.core.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Checked(val result: VerifyResult, val preview: Bitmap?, val ownKeyId: String?, val qr: QrInfo?)

/** 署名した端末をどこまで信頼できるか */
private enum class Trust { OWN, REGISTERED, UNREGISTERED }

private fun trustOf(context: Context, c: Checked): Trust = when {
    c.result.keyId != null && c.result.keyId == c.ownKeyId -> Trust.OWN
    TrustStore.nameFor(context, c.result.keyId) != null -> Trust.REGISTERED
    else -> Trust.UNREGISTERED
}

private fun inspect(context: Context, uri: Uri): Checked {
    val file = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    // 全画素を読んで、透かしと変化マップを調べる
    var analyzer: ImageAnalyzer? = null
    var fullQr: String? = null
    val full = try {
        BitmapFactory.decodeByteArray(file, 0, file.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    } catch (e: Throwable) { null }
    if (full != null) {
        val w = full.width
        val h = full.height
        val a = ImageAnalyzer(w, h)
        val buf = IntArray(w * PhotoPipeline.STRIP)
        PhotoPipeline.forStrips(h) { y, n -> full.getPixels(buf, 0, w, 0, y, w, n); a.feed(buf, y, n) }
        // QR は下端の帯の右側にあるので、その周辺を元の解像度のまま読む
        fullQr = try {
            val x0 = w * 4 / 10
            val y0 = h * 5 / 10
            val crop = Bitmap.createBitmap(full, x0, y0, w - x0, h - y0)
            val t = QrCode.read(crop)
            if (crop !== full) crop.recycle()
            t
        } catch (e: Throwable) { null }
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

    val result = Verifier.verify(file, analyzer, { RecordStore.load(context, it) }, { Base64.decode(it, Base64.DEFAULT) }, { RecordStore.all(context) })
    // 写真の中に見える QR があれば読む(縮小画像で足りる)
    val qr = QrCode.parse(fullQr) ?: preview?.let { p -> try { QrCode.parse(QrCode.read(p)) } catch (e: Throwable) { null } }
    return Checked(result, preview, ownDeviceId(), qr)
}

@Composable
fun VerifyScreen(shared: Uri?, onBack: () -> Unit, onOpenDevices: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Checked?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start(uri: Uri) {
        busy = true; error = null; checked = null
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { inspect(context.applicationContext, uri) } }
            busy = false
            if (r.isSuccess) checked = r.getOrNull() else error = "読み込めませんでした: " + (r.exceptionOrNull()?.message ?: "")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) start(uri) }

    // 他のアプリの「共有」から渡された写真は、すぐに判定する
    LaunchedEffect(shared) { if (shared != null) start(shared) }

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
                c == null -> {
                    Text(
                        "写真を選ぶと、このアプリで撮影されたままかどうかを調べます。\n\n" +
                            "メールや LINE で届いた写真は、その写真の「共有」から「マコトカメラで判定」を選んでも調べられます。\n\n" +
                            "LINE の通常の写真送信や SNS では再圧縮されるため、原本とは判定されません。原本を渡すときは、メールの添付などファイルとして送ってください。",
                        color = Palette.Sub, fontSize = 14.sp, lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Chip("端末の登録と、この端末の ID", onClick = onOpenDevices)
                }
                else -> ResultView(c, onOpenDevices)
            }
        }
    }
}

@Composable
private fun ResultView(c: Checked, onOpenDevices: () -> Unit) {
    val context = LocalContext.current
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }
    val trust = remember(c, version) { trustOf(context, c) }
    val trustedName = remember(c, version) { TrustStore.nameFor(context, c.result.keyId) }
    val r = c.result
    val changed = r.changed
    val (color, title, body) = when (r.verdict) {
        Verdict.ORIGINAL -> when (trust) {
            Trust.OWN -> Triple(Palette.Ok, "原本です", "この端末で撮影されたままです。画像は 1 画素も変わっていません。")
            Trust.REGISTERED -> Triple(Palette.Ok, "原本です", "登録済みの端末「" + (trustedName ?: "") + "」で撮影されたままです。画像は 1 画素も変わっていません。")
            Trust.UNREGISTERED -> Triple(Palette.Warn, "原本ですが、未登録の端末です", "撮影後に変わっていないことは確認できました。ただし、署名した端末が登録されていないため、誰の端末で撮ったものかは確認できていません。")
        }
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
        Verdict.SIMILAR_LOCAL -> Triple(
            Palette.Bad, "この端末で撮った写真を加工したものとみられます",
            "記録も透かしも消えていますが、この端末の控えにある撮影ID " + (r.fields["shotId"] ?: "") + " の写真と構図が一致します。" +
                (if (r.changedCount > 0) "赤い部分が元の写真と違う場所です。" else "") + "下は元の写真の記録です。"
        )
        Verdict.UNKNOWN -> Triple(Palette.Bad, "原本とは認められません", "このアプリの記録も透かしも見つかりません。他のカメラの写真か、加工で記録と透かしが消えた写真です。撮影時のままであることは確認できません。")
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

    // 見える QR があれば、写真本体(記録または透かし)の撮影ID と一致するか確かめる
    val q = c.qr
    if (q != null) {
        val bodyId = r.fields["shotId"] ?: r.watermark?.takeIf { it.found }?.idHex
        val (qColor, qText) = when {
            !q.valid -> Palette.Bad to "QR の署名が合いません。QR が偽造されているか、壊れています。"
            bodyId == null -> Palette.Bad to "写真に QR(撮影ID " + q.shotId + ")がありますが、写真本体からは撮影ID を読めません。QR が他の写真から複製された可能性があります。"
            bodyId != q.shotId -> Palette.Bad to "QR は他の写真から複製されたものです。QR の撮影ID(" + q.shotId + ")が、写真本体の撮影ID(" + bodyId + ")と一致しません。"
            else -> Palette.Ok to "QR の撮影ID は、写真本体の撮影ID と一致しています。"
        }
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel)) {
            Box(Modifier.width(4.dp).height(64.dp).background(qColor))
            Text(qText, color = Palette.Text, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.height(16.dp))
    }

    // 未登録の端末の署名なら、その場で登録できるようにする
    val keyId = r.keyId
    if (trust == Trust.UNREGISTERED && keyId != null && !r.fromLocalCopy && r.verdict != Verdict.FORGED_RECORD) {
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).padding(16.dp)) {
            Text("この端末を登録する", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("端末ID " + prettyId(keyId) + "\n相手から聞いた ID と同じであることを確かめてから登録してください。", color = Palette.Sub, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名前(例: 田中さん)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row {
                Chip("登録する", selected = true) { TrustStore.add(context, keyId, newName); newName = ""; version++ }
                Spacer(Modifier.width(8.dp))
                Chip("登録済みの一覧", onClick = onOpenDevices)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    val rows = detailRows(r, c.ownKeyId, trustedName)
    if (rows.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).padding(16.dp)) {
            Text(if (r.fromLocalCopy) "元の写真の記録(この端末の控え)" else "撮影時の記録(署名付き)", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
            "注意: 未登録の端末の署名は、正規のアプリのものかどうかを確認できません。相手の端末ID を登録して使ってください。" +
                "時刻は撮影した端末の時計、位置は端末の測位結果です。",
            color = Palette.Sub, fontSize = 12.sp, lineHeight = 18.sp
        )
    }
}

private fun detailRows(r: VerifyResult, ownKeyId: String?, trustedName: String?): List<Pair<String, String>> {
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
    f["ratio"]?.let { out.add("比率" to it) }
    out.add("画面の表示" to if (f["stamp"] == "true") "あり" else "なし")
    f["shotId"]?.let { out.add("撮影ID" to it) }
    r.keyId?.let {
        val who = when {
            ownKeyId != null && ownKeyId == it -> "この端末"
            trustedName != null -> "登録済み: $trustedName"
            else -> "未登録の端末"
        }
        out.add("署名した端末" to prettyId(it) + "($who)")
    }
    return out
}
