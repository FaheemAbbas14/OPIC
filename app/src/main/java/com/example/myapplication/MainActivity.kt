@file:Suppress("DEPRECATION")
package com.example.myapplication
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import java.io.File
import java.util.concurrent.TimeUnit
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.view.View.GONE
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var camera: Camera
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo
    private lateinit var imageCapture: ImageCapture
    private lateinit var outputDirectory: File
    private lateinit var zoomSlider: SeekBar
    private lateinit var focusSlider: SeekBar
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
        }

    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_acitivity)
        previewView = findViewById(R.id.previewView)
        zoomSlider = findViewById(R.id.zoomSlider)
        focusSlider = findViewById(R.id.focusSlider)
        val captureButton: Button = findViewById(R.id.captureButton)
        checkPermissionAndOpenCamera()
        captureButton.setOnClickListener {
            takePhoto()
        }

    }

    private fun checkPermissionAndOpenCamera() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(this, "Camera permission is needed to take photos", Toast.LENGTH_LONG).show()
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = buildPreviewWithAutoFocus().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageCapture = buildImageCapture()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                setupZoomSlider()
                setupTapToFocus()
                setupFocusSlider()

            } catch (e: Exception) {
                Log.e("CameraX", "Use case binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildPreviewWithAutoFocus(): Preview {
        val builder = Preview.Builder()
        val ext = Camera2Interop.Extender(builder)
        ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        return builder.build()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildImageCapture(): ImageCapture {
        val builder = ImageCapture.Builder()
        val ext = Camera2Interop.Extender(builder)
        ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        return builder.build()
    }

    private fun setupTapToFocus() {
        val focusRing = findViewById<View>(R.id.focusRing)

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)

                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .addPoint(point, FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()

                cameraControl.startFocusAndMetering(action)

                focusRing.translationX = event.x - focusRing.width / 2
                focusRing.translationY = event.y - focusRing.height / 2
                focusRing.isVisible = true
                focusRing.alpha = 1f
                focusRing.scaleX = 1f
                focusRing.scaleY = 1f

                focusRing.animate()
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .alpha(0f)
                    .setDuration(800)
                    .withEndAction { focusRing.isVisible = false }
                    .start()
            }
            true
        }
    }

    private fun setupZoomSlider() {
        val zoomLabel = findViewById<TextView>(R.id.zoomLabel)

        val minZoom = cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        val maxZoom = 5f

        zoomSlider.max = ((maxZoom - minZoom) * 10).toInt()

        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.d("CameraX", "Zoom progress: $progress")
                val zoomRatio = minZoom + (progress / 10f)
                cameraControl.setZoomRatio(zoomRatio.coerceAtMost(maxZoom))

                zoomLabel.text = String.format("%.1fx", zoomRatio)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalCamera2Interop::class)
    private fun isManualFocusSupported(): Boolean {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val afModes = camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        return afModes?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) == true
    }

    private fun setupFocusSlider() {
        if (!isManualFocusSupported()) {
            focusSlider.visibility = View.GONE
            Toast.makeText(this, "Manual focus not supported.", Toast.LENGTH_SHORT).show()
            return
        }

        focusSlider.visibility = View.VISIBLE
        focusSlider.max = 100

        focusSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val factory = SurfaceOrientedMeteringPointFactory(
                    previewView.width.toFloat(), previewView.height.toFloat()
                )
                val yFraction = progress / 100f
                val x = previewView.width / 2f
                val y = previewView.height * yFraction

                val point = factory.createPoint(x, y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                try {
                    cameraControl.startFocusAndMetering(action)
                } catch (e: Exception) {
                    Log.e("CameraX", "Manual focus failed: ${e.message}")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        focusSlider.visibility=GONE
    }

    private fun takePhoto() {
        val photoFile = File(getOutputDirectory(), "IMG_${System.currentTimeMillis()}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(applicationContext, "Photo saved", Toast.LENGTH_SHORT).show()
                    Log.d("CameraX", "Saved to: ${photoFile.absolutePath}")
                    zoomSlider.progress = 0
                    restartCameraPreview()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(applicationContext, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    Log.e("CameraX", "Capture failed", exception)
                    restartCameraPreview()
                }
            }
        )
    }

    private fun restartCameraPreview() {
        Handler(Looper.getMainLooper()).postDelayed({
            startCamera()
        }, 500)
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "CameraXPhotos").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
