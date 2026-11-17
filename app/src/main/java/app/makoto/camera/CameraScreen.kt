package app.makoto.camera

import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(locationOk: Boolean, onOpenVerify: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
        }
    }
    val tracker = remember { LocationTracker(context) }

    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        onDispose { controller.unbind() }
    }
    DisposableEffect(locationOk) {
        if (locationOk) tracker.start()
        onDispose { tracker.stop() }
    }

    val zoomState by controller.zoomState.observeAsState()
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf("off") }
    var stamp by remember { mutableStateOf(true) }
    var ratio by remember { mutableStateOf("4:3") }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 明るさ(露出補正)
    var evIndex by remember { mutableIntStateOf(0) }
    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var evStep by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    // 比率。1:1 は 4:3 で撮って中央を切り出す
    LaunchedEffect(ratio) {
        val strategy = if (ratio == "16:9") AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val selector = ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()
        controller.imageCaptureResolutionSelector = selector
        controller.previewResolutionSelector = selector
    }

    // カメラが準備できたら、露出補正の範囲を読む
    LaunchedEffect(zoomState != null, front) {
        delay(300)
        val es = controller.cameraInfo?.exposureState
        if (es != null && es.isExposureCompensationSupported) {
            evMin = es.exposureCompensationRange.lower
            evMax = es.exposureCompensationRange.upper
            evStep = es.exposureCompensationStep.toFloat()
            evIndex = es.exposureCompensationIndex
        } else {
            evMin = 0; evMax = 0; evStep = 0f; evIndex = 0
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun shoot() {
        if (busy) return
        busy = true
        val loc = tracker.last
        val settings = ShotSettings(stamp, front, zoomState?.zoomRatio ?: 1f, evIndex * evStep, flash, ratio)
        controller.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching { PhotoPipeline.process(context.applicationContext, bytes, rotation, loc, settings) }
                    }
                    busy = false
                    if (result.isSuccess) toast("保存しました(撮影ID " + result.getOrThrow().idHex + ")")
                    else toast("保存に失敗しました: " + (result.exceptionOrNull()?.message ?: ""))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                busy = false
                toast("撮影に失敗しました")
            }
        })
    }

    Column(modifier = Modifier.fillMaxSize().background(Palette.Ink).systemBarsPadding()) {
        // 上段: アプリ名と設定
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("マコトカメラ", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Chip("フラッシュ " + when (flash) { "auto" -> "自動"; "on" -> "入"; else -> "切" }, selected = flash != "off", enabled = !front) {
                flash = when (flash) { "off" -> "auto"; "auto" -> "on"; else -> "off" }
                controller.imageCaptureFlashMode = when (flash) {
                    "auto" -> ImageCapture.FLASH_MODE_AUTO
                    "on" -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }
            Spacer(Modifier.width(8.dp))
            Chip(if (stamp) "表示 入" else "表示 切", selected = stamp) { stamp = !stamp }
        }

        // 位置の状態
        val loc = tracker.last
        val status = when {
            !locationOk -> "位置情報: 許可なし(位置なしで記録します)"
            loc == null -> "位置情報: 取得中"
            else -> String.format(Locale.US, "位置情報: 取得済み(誤差 約%.0f m)", loc.accuracy)
        }
        Text(status, color = if (loc != null) Palette.Sub else Palette.Warn, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.size(8.dp))

        // プレビュー
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
          Box(modifier = Modifier.aspectRatio(when (ratio) { "16:9" -> 9f / 16f; "1:1" -> 1f; else -> 3f / 4f }).clip(androidx.compose.foundation.shape.RoundedCornerShape(0.dp))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        this.controller = controller
                    }
                }
            )
            if (stamp) {
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x990E141B)).padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("マコトカメラ", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "AI編集不可", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.border(1.dp, Color.White, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    for (line in Stamp.lines(now, loc)) Text(line, color = Color.White, fontSize = 11.sp)
                }
            }
            if (busy) {
                Row(
                    modifier = Modifier.align(Alignment.Center).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color(0xCC0E141B)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("署名と透かしを記録中", color = Palette.Text, fontSize = 13.sp)
                }
            }
          }
        }

        // 比率
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("比率", color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            for (r in listOf("4:3", "16:9", "1:1")) {
                Chip(r, selected = ratio == r, enabled = !busy) { ratio = r }
                Spacer(Modifier.width(8.dp))
            }
        }

        // 明るさ
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("明るさ", color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            Slider(
                value = evIndex.toFloat(),
                onValueChange = {
                    val i = Math.round(it)
                    if (i != evIndex) { evIndex = i; controller.cameraControl?.setExposureCompensationIndex(i) }
                },
                valueRange = evMin.toFloat()..Math.max(evMax, evMin + 1).toFloat(),
                enabled = evMax > evMin,
                colors = SliderDefaults.colors(thumbColor = Palette.Text, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Line),
                modifier = Modifier.weight(1f)
            )
            Text(String.format(Locale.US, "%+.1f", evIndex * evStep), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.width(40.dp).padding(start = 8.dp))
        }

        // ズーム(1 未満は広角レンズ)
        val zs = zoomState
        val minZ = zs?.minZoomRatio ?: 1f
        val maxZ = zs?.maxZoomRatio ?: 1f
        val current = zs?.zoomRatio ?: 1f
        val presets = ArrayList<Float>()
        if (minZ < 0.95f) presets.add(minZ)
        for (z in listOf(1f, 2f, 5f, 10f)) if (z <= maxZ + 0.01f) presets.add(z)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            for (z in presets) {
                val label = (if (z < 0.95f) String.format(Locale.US, "広角 %.1fx", z) else String.format(Locale.US, "%.0fx", z))
                Chip(label, selected = Math.abs(current - z) < 0.05f) { controller.setZoomRatio(z) }
                Spacer(Modifier.width(8.dp))
            }
            Text(String.format(Locale.US, "現在 %.1fx", current), color = Palette.Sub, fontSize = 12.sp)
        }

        // 下段: 判定、シャッター、カメラ切替
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { Chip("写真を判定", onClick = onOpenVerify) }
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).border(3.dp, Palette.Text, CircleShape).clickable(enabled = !busy) { shoot() },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(if (busy) Palette.Sub else Palette.Text))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Chip(if (front) "外カメラへ" else "内カメラへ") {
                    val next = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    try {
                        if (controller.hasCamera(next)) {
                            controller.cameraSelector = next
                            front = !front
                            if (front) { flash = "off"; controller.imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF }
                        }
                    } catch (e: Exception) {
                        toast("カメラを切り替えられません")
                    }
                }
            }
        }
    }
}
