package app.ghostly.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import app.ghostly.i18n.tr

/** Full-screen camera with a rounded viewfinder; returns the first QR text via [EXTRA_RESULT]. */
class QrScanActivity : ComponentActivity() {

    private val done = AtomicBoolean(false)
    private val analyzer = Executors.newSingleThreadExecutor()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun showCamera() {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        val view = PreviewView(ctx)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(analyzer) { proxy ->
                                val media = proxy.image
                                if (media == null || done.get()) {
                                    proxy.close(); return@setAnalyzer
                                }
                                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                    .addOnSuccessListener { codes ->
                                        codes.firstNotNullOfOrNull { it.rawValue }?.let(::deliver)
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(this@QrScanActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }, ContextCompat.getMainExecutor(ctx))
                        view
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Dimmed overlay with a clear rounded window.
                Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                    drawRect(Color.Black.copy(alpha = 0.55f))
                    val side = size.minDimension * 0.68f
                    val tl = Offset((size.width - side) / 2, (size.height - side) / 2.3f)
                    drawRoundRect(Color.Transparent, tl, Size(side, side), CornerRadius(40f, 40f), blendMode = BlendMode.Clear)
                    drawRoundRect(Color(0xFFA88DFF), tl, Size(side, side), CornerRadius(40f, 40f), style = Stroke(5f))
                }
                Text(
                    tr("Наведи камеру на QR-код подписки"),
                    color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp),
                )
                Box(
                    Modifier.statusBarsPadding().padding(16.dp).size(44.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f)).clickable { finish() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 18.sp) }
            }
        }
    }

    private fun deliver(text: String) {
        if (!done.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
        finish()
    }

    override fun onDestroy() {
        analyzer.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT = "qr"
    }
}
