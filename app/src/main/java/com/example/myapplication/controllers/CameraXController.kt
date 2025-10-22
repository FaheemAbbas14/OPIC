package com.opic3d.Spatial.trendingvideos.controllers

import android.Manifest
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresPermission
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.opic3d.Spatial.trendingvideos.helper.retime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX controller for Photo, normal Video, and Time-lapse.
 * - FHD across preview/photo/video
 * - AE/AF/FPS via Camera2CameraControl (runtime)
 * - Time-lapse uses post retime on a background thread
 */
class CameraXController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private var desiredFps: Int = 30
) {

    private val appContext: Context = context.applicationContext
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(appContext) }

    // Lazily-created background executor for retime work. Recreated if terminated.
    @Volatile
    private var retimeExecutor: ExecutorService? = null
    private fun ensureRetimeExecutor(): ExecutorService {
        val ex = retimeExecutor
        return if (ex == null || ex.isShutdown || ex.isTerminated) {
            Executors.newSingleThreadExecutor().also { retimeExecutor = it }
        } else ex
    }

    private fun postRetime(task: () -> Unit) {
        ensureRetimeExecutor().execute(task)
    }

    private var processProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Manual-focus helpers
    private var minFocusDistance: Float = 0f
    private var lastManualFocusNormalized: Float = 0.0f

    // Common FHD target
    private val TARGET_SIZE = Size(1920, 1080)

    // === Time-lapse session state ===
    private var pendingOutFile: File? = null
    private var pendingCaptureFps: Double = 2.0
    private var pendingPlaybackFps: Int = 30
    private var pendingNormalizePlaybackFps: Boolean = false

    // === Stop callback (fires on Finalize) ===
    private var pendingOnResult: ((Result<Uri>) -> Unit)? = null

    /** Quick check to see if VideoCapture is bound and usable. */
    fun isVideoReady(): Boolean = videoCapture != null
    var isVideo = false

    /** Bind once for any combination of photo/video. */
    fun bind(photo: Boolean, video: Boolean) {
        isVideo = video
        val provider = ProcessCameraProvider.getInstance(appContext).get()
        processProvider = provider
        runCatching { provider.unbindAll() }

        // Preview
        preview = Preview.Builder()
            .setTargetResolution(TARGET_SIZE)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Photo
        imageCapture = if (photo) {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(TARGET_SIZE)
                .build()
        } else null

        // Video
        videoCapture = if (video) {
            val qualitySelector = QualitySelector.from(
                Quality.FHD,
                FallbackStrategy.lowerQualityThan(Quality.FHD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            VideoCapture.withOutput(recorder)
        } else null

        // Bind
        val useCases = mutableListOf<UseCase>().apply {
            add(preview!!)
            imageCapture?.let { add(it) }
            videoCapture?.let { add(it) }
        }.toTypedArray()

        camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases)

        // Apply runtime AE/AF/FPS
        applyCommonRuntimeOptions(afContinuous = true)

        // Cache min focus
        cacheMinFocusDistance()

        checkZoomValues()
        Log.d(
            "CameraXController",
            "Bound @FHD. photo=$photo, video=$video, minFocus=$minFocusDistance, fps=$desiredFps"
        )
    }

    /** Adjust desired FPS (applies to active session). */
    fun setDesiredFps(fps: Int) {
        desiredFps = fps.coerceIn(15, 60)
        applyCommonRuntimeOptions(afContinuous = true)
    }

    /** Start a normal-speed recording using CameraX. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(
        withAudio: Boolean = true,
        onStarted: () -> Unit = {},
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val vc = videoCapture ?: return onError(IllegalStateException("VideoCapture not bound"))
        if (recording != null) return onError(IllegalStateException("A recording is already in progress"))
        val outFile = createOutputFile()
        val opts = FileOutputOptions.Builder(outFile).build()

        pendingOnResult = null

        val base = vc.output.prepareRecording(appContext, opts)
        val rec = if (withAudio) base.withAudioEnabled() else base

        recording = rec.start(mainExecutor) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> onStarted()
                is VideoRecordEvent.Finalize -> {
                    val result = if (!ev.hasError())
                        Result.success(Uri.fromFile(outFile))
                    else
                        Result.failure(ev.cause ?: Exception("Recording failed"))

                    val cb = pendingOnResult
                    if (cb != null) {
                        cb(result); pendingOnResult = null
                    } else {
                        result.onSuccess(onSaved).onFailure(onError)
                    }
                    recording = null
                }
            }
        }
    }

    private fun createOutputFile(
        prefix: String = when (isVideo) {
            true -> "Vid"
            false -> "IMG"
        }
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "OPIC"
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${prefix}_$ts.mp4")
    }

    /**
     * Start a TIME-LAPSE recording.
     * Records at [desiredFps], then retimes on finalize to [playbackFps] with a speed of (desiredFps / captureFps).
     * Audio is disabled for timelapse.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTimeLapseRecording(
        captureFps: Double = 2.0,
        playbackFps: Int = 30,
        onStarted: () -> Unit = {},
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val vc = videoCapture ?: return onError(IllegalStateException("VideoCapture not bound"))
        if (recording != null) return onError(IllegalStateException("A recording is already in progress"))

        val safeCaptureFps = captureFps.coerceAtLeast(0.1)
        val safePlaybackFps = playbackFps.coerceIn(1, 120)
        val outFile = createOutputFile("TL")
        val opts = FileOutputOptions.Builder(outFile).build()

        pendingOutFile = outFile
        pendingCaptureFps = safeCaptureFps
        pendingPlaybackFps = safePlaybackFps
        pendingNormalizePlaybackFps = false
        pendingOnResult = null

        val base = vc.output.prepareRecording(appContext, opts)
        val rec = base // timelapse: no audio

        recording = rec.start(mainExecutor) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> onStarted()
                is VideoRecordEvent.Finalize -> {
                    if (ev.hasError()) {
                        val err = ev.cause ?: Exception("Time-lapse recording failed")
                        val cb = pendingOnResult
                        if (cb != null) cb(Result.failure(err)) else onError(err)
                        pendingOnResult = null
                        recording = null
                        return@start
                    }

                    val file = pendingOutFile
                    pendingOutFile = null
                    if (file == null) {
                        val err = IllegalStateException("Output file missing")
                        val cb = pendingOnResult
                        if (cb != null) cb(Result.failure(err)) else onError(err)
                        pendingOnResult = null
                        recording = null
                        return@start
                    }

                    // Heavy retime off main thread — using a resilient executor
                    postRetime {
                        val result: Result<Uri> = runCatching {
                            val speedMultiplier =
                                (desiredFps.toDouble() / pendingCaptureFps.coerceAtLeast(0.1))
                            val effectiveTargetFps = if (pendingNormalizePlaybackFps)
                                normalizeFps(pendingPlaybackFps) else pendingPlaybackFps

                            retime(
                                inputPath = file.absolutePath,
                                targetFps = effectiveTargetFps,
                                speedMultiplier = speedMultiplier,
                                removeAudio = true
                            )
                            Uri.fromFile(file)
                        }

                        // Post completion back to main
                        mainExecutor.execute {
                            val cb = pendingOnResult
                            if (cb != null) {
                                cb(result); pendingOnResult = null
                            } else {
                                result.onSuccess(onSaved).onFailure(onError)
                            }
                            recording = null
                        }
                    }
                }
            }
        }
    }

    /** Fire-and-forget stop (doesn't null 'recording' until finalize). */
    fun stopRecording() {
        runCatching { recording?.stop() }
    }

    /** Stop (normal or timelapse) and get the result when finalize completes. */
    fun stopRecording(onResult: (Result<Uri>) -> Unit) {
        val current = recording ?: run {
            onResult(Result.failure(IllegalStateException("No active recording"))); return
        }
        pendingOnResult = { res ->
            onResult(res)
            pendingOnResult = null
            pendingNormalizePlaybackFps = false
        }
        runCatching { current.stop() }
    }

    /** Stop only time-lapse with optional playback FPS normalization. */
    fun stopTimeLapse(
        normalizePlaybackFps: Boolean = false,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val current = recording ?: run {
            onError(IllegalStateException("No active recording")); return
        }
        pendingNormalizePlaybackFps = normalizePlaybackFps
        pendingOnResult = { res ->
            res.onSuccess(onSaved).onFailure(onError)
            pendingOnResult = null
            pendingNormalizePlaybackFps = false
        }
        runCatching { current.stop() }
    }

    /** Take a photo using CameraX. */
    fun takePhoto(
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val cap = imageCapture ?: return onError(IllegalStateException("ImageCapture not bound"))
        val outFile = createOutputFile()
        val output = ImageCapture.OutputFileOptions.Builder(outFile).build()
        cap.takePicture(
            output,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSaved(Uri.fromFile(outFile))
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    fun checkZoomValues(): MutableList<Float> {
        val definedZoomLevels: MutableList<Float> = mutableListOf(5f, 4f, 3f, 2f, 1.2f, 1f)
        val finalize: MutableList<Float> = mutableListOf()
        val zoomStateLiveData = camera?.cameraInfo?.zoomState
        zoomStateLiveData?.observe(lifecycleOwner) { zoomState ->
            val minZoom = zoomState.minZoomRatio
            val maxZoom = zoomState.maxZoomRatio
            Log.d("CameraX", "Zoom range: $minZoom - $maxZoom")
            for (zoom in definedZoomLevels) {
                if (zoom >= minZoom && zoom <= maxZoom) {
                    if (!finalize.contains(zoom)) {
                        finalize.add(zoom)
                    }
                }

            }
        }
        return finalize
    }

    /** Zoom */
    fun setZoomLevel(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /** Torch */
    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    /** Enable AF-C (video-like AF) */
    fun enableAutoFocus() {
        applyCommonRuntimeOptions(afContinuous = true)
    }

    /**
     * Manual focus using normalized value [0..1]:
     * 0 = infinity (focusDistance=0), 1 = macro (focusDistance=minFocusDistance).
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun setManualFocusNormalized(normalized: Float) {
        lastManualFocusNormalized = normalized.coerceIn(0f, 1f)
        val min = minFocusDistance
        if (min <= 0f) return
        val focusDistance = (1f - lastManualFocusNormalized) * min

        camera?.let { cam ->
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            val opts = commonRequestOptionsBuilder(afContinuous = false)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                .build()
            c2.setCaptureRequestOptions(opts)
        }
    }

    fun getMinFocusDistance(): Float = minFocusDistance
    fun getLastManualFocusNormalized(): Float = lastManualFocusNormalized

    /** Optional: switch cameras later */
    fun setCameraSelector(selector: CameraSelector) {
        cameraSelector = selector
    }

    fun release() {
        // If released mid-recording, fail any waiting stop-callback
        pendingOnResult?.invoke(Result.failure(IllegalStateException("Controller released during recording")))
        pendingOnResult = null
        pendingNormalizePlaybackFps = false

        runCatching { stopRecording() }
        runCatching { processProvider?.unbindAll() }
        preview = null
        imageCapture = null
        videoCapture = null
        camera = null
        processProvider = null

        // IMPORTANT: do NOT shutdown the retime executor here; it may be reused later.
        // If you truly want to dispose permanently, add an explicit close() API to shut it down.
    }

    // ===== Internals =====

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun cacheMinFocusDistance() {
        minFocusDistance = 0f
        camera?.cameraInfo?.let { info ->
            runCatching {
                val c2info = Camera2CameraInfo.from(info)
                val v = c2info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                )
                if (v != null && v > 0f) minFocusDistance = v
            }
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCommonRuntimeOptions(afContinuous: Boolean) {
        camera?.let { cam ->
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            val opts = commonRequestOptionsBuilder(afContinuous).build()
            c2.setCaptureRequestOptions(opts)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalCamera2Interop::class)
    private fun commonRequestOptionsBuilder(afContinuous: Boolean): CaptureRequestOptions.Builder {
        val clamped = desiredFps.coerceIn(15, 60)
        return CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                if (afContinuous) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(clamped, clamped)
            )
    }

    private fun normalizeFps(fps: Int): Int {
        val standards = intArrayOf(24, 25, 30, 48, 50, 60, 120)
        return standards.minByOrNull { kotlin.math.abs(it - fps) } ?: fps
    }
}
