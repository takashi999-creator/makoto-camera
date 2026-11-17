package app.makoto.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    /** 他のアプリから「共有」や「アプリで開く」で渡された写真 */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming.value = sharedImage(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Accent, background = Palette.Ink, surface = Palette.Panel, onSurface = Palette.Text, onBackground = Palette.Text)) {
                App(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedImage(intent)?.let { incoming.value = it }
    }

    private fun sharedImage(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_VIEW -> i.data
        else -> null
    }
}

@Composable
private fun App(incoming: MutableState<Uri?>) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var cameraOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var locationOk by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var asked by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(incoming.value != null) }
    var devices by remember { mutableStateOf(false) }
    LaunchedEffect(incoming.value) { if (incoming.value != null) { verifying = true; devices = false } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraOk = result[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
        locationOk = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!cameraOk || !locationOk) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    when {
        devices -> {
            BackHandler { devices = false }
            DevicesScreen(onBack = { devices = false })
        }
        verifying -> {
            BackHandler { verifying = false; incoming.value = null }
            VerifyScreen(shared = incoming.value, onBack = { verifying = false; incoming.value = null }, onOpenDevices = { devices = true })
        }
        cameraOk -> CameraScreen(locationOk = locationOk, onOpenVerify = { verifying = true })
        else -> Column(
            modifier = Modifier.fillMaxSize().background(Palette.Ink).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("カメラの許可が必要です", color = Palette.Text, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                if (asked) "設定アプリでこのアプリにカメラを許可してください。" else "許可の確認を表示しています。",
                color = Palette.Sub, fontSize = 14.sp
            )
            Spacer(Modifier.height(24.dp))
            Chip("写真の判定だけ使う") { verifying = true }
        }
    }
}
