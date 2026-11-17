package app.makoto.camera

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.makoto.camera.core.sha256
import app.makoto.camera.core.toHex

/** この端末の鍵ID(公開鍵の SHA-256 の先頭 8 バイト)。取得できなければ null。 */
fun ownDeviceId(): String? = try { sha256(DeviceKey.publicKey()).copyOf(8).toHex() } catch (e: Exception) { null }

/** 読みやすいように 4 桁ごとに区切る */
fun prettyId(id: String): String = id.chunked(4).joinToString(" ")

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val own = remember { ownDeviceId() }
    var version by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val devices = remember(version) { TrustStore.all(context) }

    Column(modifier = Modifier.fillMaxSize().background(Palette.Ink).systemBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("戻る", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("端末の登録", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // この端末のID
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).padding(16.dp)) {
                Text("この端末のID", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(own?.let { prettyId(it) } ?: "取得できません", color = Palette.Text, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("写真を確認する相手にこの ID を伝え、登録してもらってください。登録された端末の写真だけが「登録済み」と表示されます。", color = Palette.Sub, fontSize = 12.sp, lineHeight = 18.sp)
                if (own != null) {
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Chip("コピー") { clipboard.setText(AnnotatedString(own)); note = "ID をコピーしました" }
                        Spacer(Modifier.width(8.dp))
                        Chip("送る") {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "マコトカメラの端末ID: $own")
                            }
                            context.startActivity(Intent.createChooser(send, "端末ID を送る"))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 登録
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).padding(16.dp)) {
                Text("相手の端末を登録", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名前(例: 田中さん)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("端末ID(16 桁)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Chip("登録する", selected = true) {
                    // 「マコトカメラの端末ID: xxxx」をそのまま貼り付けても読めるようにする
                    val norm = TrustStore.normalize(id.substringAfterLast(":"))
                    if (norm == null) note = "端末ID は 16 桁の英数字(0-9, a-f)です"
                    else {
                        TrustStore.add(context, norm, name)
                        name = ""; id = ""; version++
                        note = "登録しました"
                    }
                }
                note?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Palette.Sub, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(16.dp))

            // 一覧
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).padding(16.dp)) {
                Text("登録済みの端末", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) Text("まだありません", color = Palette.Sub, fontSize = 13.sp)
                for ((deviceId, deviceName) in devices) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(deviceName, color = Palette.Text, fontSize = 14.sp)
                            Text(prettyId(deviceId), color = Palette.Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Chip("削除") { TrustStore.remove(context, deviceId); version++ }
                    }
                }
            }
        }
    }
}
