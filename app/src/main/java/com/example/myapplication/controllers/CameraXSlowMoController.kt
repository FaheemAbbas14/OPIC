package com.opic3d.Spatial.trendingvideos.controllers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.opic3d.Spatial.trendingvideos.model.SlowMoOption
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Wraps CameraX VideoCapture API for normal speed recording (<= 60fps).
 */
class CameraXSlowMoController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null

    suspend fun bind(option: SlowMoOption) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = providerFuture.get()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val qualitySelector = QualitySelector.from(
            Quality.FHD,
            FallbackStrategy.lowerQualityThan(Quality.FHD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        cameraProvider!!.unbindAll()
        cameraProvider!!.bindToLifecycle(lifecycleOwner, option.selector, preview, videoCapture)
        Log.d("CameraxController", "Bound CameraX @ ${option.label}")
    }

    fun startRecording(
        withAudio: Boolean = true,
        onStarted: () -> Unit = {},
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val vc = videoCapture ?: return onError(IllegalStateException("VideoCapture not bound"))

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val outFile = File(context.externalCacheDir, "CAMX_${name}.mp4")

        val output = FileOutputOptions.Builder(outFile).build()
        val builder = vc.output.prepareRecording(context, output)
        val recordingSetup = if (withAudio) builder.withAudioEnabled() else builder

        recording = recordingSetup.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    onStarted()
                    Log.d("CameraxController", "Recording started")
                }
                is VideoRecordEvent.Finalize -> {
                    if (!event.hasError()) {
                        onSaved(Uri.fromFile(outFile))
                        Log.d("CameraxController", "Saved ${outFile.absolutePath}")
                    } else {
                        onError(event.cause ?: Exception("Recording failed"))
                    }
                }
            }
        }
    }

    fun stopRecording() {
        try {
            recording?.stop()
        } catch (e: Exception) {
            Log.e("CameraxController", "Stop error", e)
        }
        recording = null
    }

    fun release() {
        try {
            stopRecording()
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        videoCapture = null
        cameraProvider = null
    }
}
