package com.pank.vsevodnom.ui.codes

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.common.dialogBars

/**
 * Свой сканер QR и штрихкодов: камера (Camera2) + ZXing на телефоне, без сервисов Google.
 * Гугловский модуль (play-services-code-scanner) ронял приложение при нажатии «Сканировать» (0.44) — он теперь только запасной.
 */
object CodeDecode {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to BarcodeFormat.entries.toList(), DecodeHintType.CHARACTER_SET to "UTF-8")
    private val reader = ThreadLocal.withInitial { MultiFormatReader().apply { setHints(hints) } }

    /** Яркостная плоскость кадра; пробуем как есть и повернув на 90° (штрихкод в вертикальном кадре). */
    fun decode(y: ByteArray, w: Int, h: Int): Result? {
        fun tryOne(d: ByteArray, ww: Int, hh: Int): Result? = runCatching {
            reader.get()!!.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(d, ww, hh, 0, 0, ww, hh, false))))
        }.getOrNull().also { reader.get()!!.reset() }
        tryOne(y, w, h)?.let { return it }
        val r = ByteArray(y.size); for (yy in 0 until h) for (xx in 0 until w) r[xx * h + (h - 1 - yy)] = y[yy * w + xx]
        return tryOne(r, h, w)
    }

    fun decodeBitmap(b: android.graphics.Bitmap): Result? {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return runCatching { reader.get()!!.decodeWithState(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(b.width, b.height, px)))) }.getOrNull().also { reader.get()!!.reset() }
    }

    fun formatName(f: BarcodeFormat) = when (f) { BarcodeFormat.QR_CODE -> "QR"; BarcodeFormat.EAN_13 -> "EAN-13"; BarcodeFormat.EAN_8 -> "EAN-8"; BarcodeFormat.UPC_A -> "UPC-A"
        BarcodeFormat.CODE_128 -> "Code 128"; BarcodeFormat.CODE_39 -> "Code 39"; BarcodeFormat.DATA_MATRIX -> "Data Matrix"; BarcodeFormat.PDF_417 -> "PDF417"; BarcodeFormat.AZTEC -> "Aztec"; else -> f.name }
}

@SuppressLint("MissingPermission")
@Composable
fun OwnScanner(onResult: (String, String) -> Unit, onFallback: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }
    var torch by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u?.let { uri -> runCatching { ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()?.let { b ->
            CodeDecode.decodeBitmap(b)?.let { onResult(it.text, CodeDecode.formatName(it.barcodeFormat)) } ?: run { err = "На фото код не найден" } } }
    }
    val session = remember { mutableStateOf<Pair<CameraCaptureSession, CaptureRequest.Builder>?>(null) }
    LaunchedEffect(torch) { session.value?.let { (s, b) -> runCatching { b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF); s.setRepeatingRequest(b.build(), null, null) } } }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) ScanPreview(session, onCode = { t, f -> onResult(t, f) }, onError = { err = it })
        // рамка прицела
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension * 0.7f; val tl = Offset((size.width - s) / 2, (size.height - s) / 2)
            drawRoundRect(Color.White, tl, Size(s, s), CornerRadius(36f), style = Stroke(6f))
        }
        Row(Modifier.dialogBars().padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIconButton(Icons.Rounded.Close, "Закрыть", onClose)
            Spacer(Modifier.weight(1f))
            RoundIconButton(Icons.Rounded.Image, "Из фото", { gallery.launch("image/*") })
            RoundIconButton(if (torch) Icons.Rounded.FlashlightOff else Icons.Rounded.FlashlightOn, "Фонарик", { torch = !torch })
        }
        Column(Modifier.align(Alignment.BottomCenter).dialogBars().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(err ?: if (granted) "Наведи на QR или штрихкод" else "Нужен доступ к камере", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            PillButton("Сканер Google", onFallback, bg = Color.White.copy(alpha = 0.15f), fg = Color.White)
        }
    }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ScanPreview(session: MutableState<Pair<CameraCaptureSession, CaptureRequest.Builder>?>, onCode: (String, String) -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    val id = remember { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: cm.cameraIdList.firstOrNull() }
    val thread = remember { HandlerThread("scan").apply { start() } }
    val decodeThread = remember { HandlerThread("decode").apply { start() } }
    var device by remember { mutableStateOf<CameraDevice?>(null) }
    var done by remember { mutableStateOf(false) }
    val reader = remember { ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2) }
    DisposableEffect(Unit) {
        var busy = false
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (busy || done) { img.close(); return@setOnImageAvailableListener }
            busy = true
            val p = img.planes[0]; val w = img.width; val h = img.height
            val buf = p.buffer; val rs = p.rowStride
            val y = ByteArray(w * h); for (row in 0 until h) { buf.position(row * rs); buf.get(y, row * w, w) }
            img.close()
            CodeDecode.decode(y, w, h)?.let { res -> if (!done) { done = true; android.os.Handler(android.os.Looper.getMainLooper()).post { onCode(res.text, CodeDecode.formatName(res.barcodeFormat)) } } }
            busy = false
        }, Handler(decodeThread.looper))
        onDispose { runCatching { session.value?.first?.close() }; runCatching { device?.close() }; reader.close(); thread.quitSafely(); decodeThread.quitSafely() }
    }
    if (id == null) { LaunchedEffect(Unit) { onError("Камера не найдена") }; return }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AndroidView({ c -> TextureView(c).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(1280, 720)
                runCatching { cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d; val surf = Surface(st)
                        @Suppress("DEPRECATION") d.createCaptureSession(listOf(surf, reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf); addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) }
                                runCatching { s.setRepeatingRequest(b.build(), null, Handler(thread.looper)); session.value = s to b }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { onError("Камера не запустилась") }
                        }, Handler(thread.looper))
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close() }
                    override fun onError(d: CameraDevice, e: Int) { d.close(); onError("Ошибка камеры $e") }
                }, Handler(thread.looper)) }.onFailure { onError(it.message ?: "ошибка камеры") }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        } } }, Modifier.fillMaxWidth().aspectRatio(720f / 1280f)) }
}
