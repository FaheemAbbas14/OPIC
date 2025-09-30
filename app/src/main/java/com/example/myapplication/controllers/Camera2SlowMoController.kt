package com.example.myapplication.controllers

import android.Manifest
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.camera.core.AspectRatio
import androidx.camera.view.PreviewView
import com.example.myapplication.model.SlowMoOption
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class Camera2SlowMoController(
    private val context: Context,
    private val previewView: PreviewView
) {
    private val TAG = "Camera2SlowMo"

    // Threads
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    // Camera state
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Recording
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    // Options
    private var option: SlowMoOption? = null
    @Volatile private var ready = false
    fun isReady() = ready

    // Latches
    private var deviceClosedLatch: CountDownLatch? = null
    private var sessionClosedLatch: CountDownLatch? = null

    // Surfaces
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    // AE/EV
    private var aeCompRange: Range<Int>? = null
    private var currentEvSteps: Int = 0
    var manager: CameraManager? = null

    // UI hook
    var onTooDark: (() -> Unit)? = null

    // Pipelines
    private enum class Pipeline { HFR, STD60, ULL15_30 }
    private var pipeline: Pipeline = Pipeline.HFR

    // Behavior
    private var useTemplateRecordForPreview = true
    private var currentAntibanding = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO

    // AutoEV (HFR only)
    private var autoEvEnabled = true
    private var lastAdjustNs = 0L
    private var targetFpsForBudget = 120
    private var evStep = 1
    private var adjustCooldownNs = 200_000_000L
    private val brightThresh = 0.20
    private val darkThresh   = 0.80
    private val isoHigh      = 800
    private val isoNearMin   = 120

    // Auto environment (disabled by forceIndoorBrightMode)
    private var autoEnvironmentMode = true
    private enum class Env { OUTDOOR, INDOOR, VERY_DARK }
    private var envState = Env.OUTDOOR
    private var dwellIndoor = 0
    private var dwellVeryDark = 0
    private var dwellOutdoor = 0
    private val dwellThreshold = 6

    // Heuristics vs HFR budget
    private val indoorFracThresh   = 0.75
    private val indoorIsoThresh    = 500
    private val veryDarkFracThresh = 0.90
    private val veryDarkIsoThresh  = 1000
    private val outdoorFracThresh  = 0.40
    private val outdoorIsoThresh   = 200

    // Encode at 30 fps (STD60 ≈2x slow; ULL brighter/normal)
    private val encodeFpsForOutput = 30

    // --- Indoor tuning knobs ---
    private var indoorEvBiasSteps = -3    // run slightly below EV max indoors (tune with setIndoorBrightnessBias)
    private var ullPreferredMaxUpper = 30 // prefer <=30fps upper bound for brightness
    private var ullMinLowerTarget  = 20   // prefer >=20fps lower bound to avoid over-bright blur

    // Remember indoor request made before camera opened
    private var forceIndoorRequested = false

    // ---------- Lifecycle ----------
    fun start() {
        if (camThread != null) return
        camThread = HandlerThread("Camera2SlowMo").also { it.start() }
        camHandler = Handler(camThread!!.looper)
    }

    fun stop() {
        camThread?.quitSafely()
        camThread = null
        camHandler = null
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun bind(opt: SlowMoOption, onError: (Throwable) -> Unit = {}) {
        start()
        ready = false
        option = opt

        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager?.getCameraCharacteristics(opt.cameraId)?.let { chars ->
                aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            }
            currentEvSteps = clampEv(0) // neutral; indoor modes will set biased EV upper

            targetFpsForBudget = (getSupportedHighSpeedRange(opt)?.upper ?: opt.fpsRange.upper).coerceAtLeast(60)

            previewSurface = waitForPreviewSurface(opt.size, 1200)
                ?: return onError(IllegalStateException("Preview surface not ready"))

            manager?.openCamera(opt.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    if (forceIndoorRequested) {
                        closeSessionSync()
                        configurePreviewUll15_30 { e ->
                            Log.e(TAG, "onOpened → ULL failed", e)
                            onError(e)
                        }
                        pipeline = Pipeline.ULL15_30
                        Log.d(TAG, "onOpened: honoring pending indoor ULL request")
                    } else {
                        pipeline = Pipeline.HFR
                        configurePreviewHfr(opt, onError)
                    }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null; ready = false }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null; ready = false
                    onError(RuntimeException("Camera2 error $error"))
                }
                override fun onClosed(device: CameraDevice) { deviceClosedLatch?.countDown() }
            }, camHandler)
        } catch (e: Exception) {
            onError(e)
        }
    }

    // ---------- Force Indoor Bright ----------
    /** Force brightest indoor (low AE FPS range + EV near max with bias). Call false to return to HFR. */
    fun forceIndoorBrightMode(enable: Boolean, mainsHz: Int = 50) {
        forceIndoorRequested = enable
        if (enable) {
            autoEnvironmentMode = false
            autoEvEnabled = false
            currentAntibanding = if (mainsHz == 60)
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ

            setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps)) // EV max with bias

            if (cameraDevice != null) {
                closeSessionSync()
                configurePreviewUll15_30 { e -> Log.e(TAG, "forceIndoorBrightMode → ULL failed", e) }
                Log.d(TAG, "forceIndoorBrightMode: ENABLED (ULL, EV=${clampEv(aeUpper() + indoorEvBiasSteps)})")
            } else {
                Log.d(TAG, "forceIndoorBrightMode: requested (will apply on open)")
            }
        } else {
            val opt = option ?: return
            autoEnvironmentMode = true
            autoEvEnabled = true
            setCurrentEv(0)
            if (cameraDevice != null) {
                closeSessionSync()
                configurePreviewHfr(opt) { e -> Log.e(TAG, "forceIndoorBrightMode → HFR failed", e) }
            }
            Log.d(TAG, "forceIndoorBrightMode: DISABLED (HFR)")
        }
    }

    // ---------- Surface ----------
    private fun waitForPreviewSurface(size: Size, timeoutMs: Long): Surface? {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            val child = previewView.getChildAt(0) as? android.view.TextureView
            val st: SurfaceTexture? = child?.surfaceTexture
            if (child != null && st != null) {
                st.setDefaultBufferSize(size.width, size.height)
                return Surface(st)
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
        }
        return null
    }

    // ---------- Helpers ----------
    private fun clampEv(ev: Int): Int = aeCompRange?.let { ev.coerceIn(it.lower, it.upper) } ?: 0
    private fun aeLower(): Int = aeCompRange?.lower ?: 0
    private fun aeUpper(): Int = aeCompRange?.upper ?: 0

    private fun getSupportedHighSpeedRange(opt: SlowMoOption): Range<Int>? {
        val chars = manager?.getCameraCharacteristics(opt.cameraId) ?: return null
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val supported = map.getHighSpeedVideoFpsRangesFor(opt.size) ?: return null
        val fixed = supported.filter { it.lower == it.upper }
        val best120 = fixed.filter { it.upper >= 120 }.maxByOrNull { it.upper }
        val bestFixed = best120 ?: fixed.maxByOrNull { it.upper }
        return bestFixed ?: supported.maxByOrNull { it.upper }
    }

    // ---- Query & pick normal AE ranges for indoor brightness ----
    private fun getAvailableNormalFpsRanges(): Array<Range<Int>> {
        val chars = manager?.getCameraCharacteristics(option?.cameraId ?: return emptyArray())
            ?: return emptyArray()
        return chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
    }

    /** Low-light friendly AE range: prefer upper <= 30 and the smallest lower >= target; fallback to smallest-lower. */
    private fun pickLowLightFpsRange(
        preferredMaxUpper: Int = ullPreferredMaxUpper,
        minLowerTarget: Int = ullMinLowerTarget
    ): Range<Int>? {
        val ranges = getAvailableNormalFpsRanges()
        if (ranges.isEmpty()) return null

        val good = ranges.filter { it.upper <= preferredMaxUpper && it.lower >= minLowerTarget }
        if (good.isNotEmpty()) return good.minByOrNull { it.lower }

        val candidates = ranges.filter { it.upper <= preferredMaxUpper }
        if (candidates.isNotEmpty()) return candidates.minByOrNull { it.lower }

        return ranges.minByOrNull { it.lower }
    }

    /** Brighter-but-smooth range for STD60 (prefer 24–60, else 30–60, else any with upper>=60). */
    private fun pickStd60FpsRange(): Range<Int>? {
        val ranges = getAvailableNormalFpsRanges()
        if (ranges.isEmpty()) return null
        val exact24_60 = ranges.firstOrNull { it.lower == 24 && it.upper == 60 }
        if (exact24_60 != null) return exact24_60
        val exact30_60 = ranges.firstOrNull { it.lower == 30 && it.upper == 60 }
        if (exact30_60 != null) return exact30_60
        val any60 = ranges.filter { it.upper >= 60 }.minByOrNull { it.lower }
        if (any60 != null) return any60
        return ranges.minByOrNull { it.lower }
    }

    // ---------- Requests ----------
    private fun buildHfrRequest(template: Int, includeRecorder: Boolean): CaptureRequest.Builder {
        val dev = cameraDevice ?: throw IllegalStateException("Camera not ready")
        val opt = option ?: throw IllegalStateException("Option missing")
        return dev.createCaptureRequest(template).apply {
            previewSurface?.let { addTarget(it) }
            if (includeRecorder) recorderSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(currentEvSteps))
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)
            getSupportedHighSpeedRange(opt)?.let { r -> if (r.lower == r.upper) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r) }
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }

    private fun buildStd60Request(template: Int, includeRecorder: Boolean): CaptureRequest.Builder {
        val dev = cameraDevice ?: throw IllegalStateException("Camera not ready")
        return dev.createCaptureRequest(template).apply {
            previewSurface?.let { addTarget(it) }
            if (includeRecorder) recorderSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(currentEvSteps))
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)
            val std = pickStd60FpsRange()
            if (std != null) {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, std)
                Log.d(TAG, "STD60 AE range: ${std.lower}-${std.upper} fps")
            } else {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(24, 60))
                Log.d(TAG, "STD60 AE range fallback: 24-60 fps")
            }
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }

    private fun buildUll15_30Request(template: Int, includeRecorder: Boolean): CaptureRequest.Builder {
        val dev = cameraDevice ?: throw IllegalStateException("Camera not ready")
        return dev.createCaptureRequest(template).apply {
            previewSurface?.let { addTarget(it) }
            if (includeRecorder) recorderSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(currentEvSteps))
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)
            val low = pickLowLightFpsRange(preferredMaxUpper = ullPreferredMaxUpper, minLowerTarget = ullMinLowerTarget)
            if (low != null) {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, low)
                Log.d(TAG, "ULL AE range: ${low.lower}-${low.upper} fps")
            } else {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
                Log.d(TAG, "ULL AE range fallback: 15-30 fps")
            }
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_LOCK, false)
            set(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
    }

    // ---------- Configure PREVIEW ----------
    private fun configurePreviewHfr(opt: SlowMoOption, onError: (Throwable) -> Unit) {
        val dev = cameraDevice ?: return
        val pSurf = previewSurface ?: return
        try {
            dev.createConstrainedHighSpeedCaptureSession(
                listOf(pSurf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val template = if (useTemplateRecordForPreview) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                            val builder = buildHfrRequest(template, includeRecorder = false)
                            val hs = session as CameraConstrainedHighSpeedCaptureSession
                            val burst = hs.createHighSpeedRequestList(builder.build())
                            hs.setRepeatingBurst(burst, captureCallback, camHandler)
                            pipeline = Pipeline.HFR
                            ready = true
                            Log.d(TAG, "Preview configured: HFR")
                        } catch (e: Exception) { onError(e) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { onError(IllegalStateException("HFR preview configure failed")) }
                    override fun onClosed(session: CameraCaptureSession) { sessionClosedLatch?.countDown() }
                }, camHandler
            )
        } catch (e: Exception) { onError(e) }
    }

    private fun configurePreviewStd60(onError: (Throwable) -> Unit) {
        val dev = cameraDevice ?: return
        val pSurf = previewSurface ?: return
        try {
            dev.createCaptureSession(
                listOf(pSurf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = buildStd60Request(CameraDevice.TEMPLATE_PREVIEW, includeRecorder = false)
                            session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                            pipeline = Pipeline.STD60
                            ready = true
                            Log.d(TAG, "Preview configured: STD60")
                        } catch (e: Exception) { onError(e) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { onError(IllegalStateException("STD60 preview configure failed")) }
                    override fun onClosed(session: CameraCaptureSession) { sessionClosedLatch?.countDown() }
                }, camHandler
            )
        } catch (e: Exception) { onError(e) }
    }

    private fun configurePreviewUll15_30(onError: (Throwable) -> Unit) {
        val dev = cameraDevice ?: return
        val pSurf = previewSurface ?: return
        try {
            dev.createCaptureSession(
                listOf(pSurf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = buildUll15_30Request(CameraDevice.TEMPLATE_PREVIEW, includeRecorder = false)
                            session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                            pipeline = Pipeline.ULL15_30
                            ready = true
                            Log.d(TAG, "Preview configured: ULL (low AE range)")
                        } catch (e: Exception) { onError(e) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { onError(IllegalStateException("ULL preview configure failed")) }
                    override fun onClosed(session: CameraCaptureSession) { sessionClosedLatch?.countDown() }
                }, camHandler
            )
        } catch (e: Exception) { onError(e) }
    }

    // ---------- RECORD ----------
    fun startRecording(onStarted: () -> Unit, onSaved: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        val dev = cameraDevice ?: return onError(IllegalStateException("Camera not ready"))
        val opt = option ?: return onError(IllegalStateException("No option bound"))

        val file = createOutputFile()
        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoFrameRate(encodeFpsForOutput)
            setVideoSize(opt.size.width, opt.size.height)
            val targetBitrate = max(AspectRatio.RATIO_16_9, opt.size.width * opt.size.height * encodeFpsForOutput)
            setVideoEncodingBitRate(targetBitrate)
            prepare()
        }

        recorderSurface = mediaRecorder!!.surface
        closeSessionSync()

        when (pipeline) {
            Pipeline.HFR -> {
                dev.createConstrainedHighSpeedCaptureSession(
                    listOfNotNull(previewSurface, recorderSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val builder = buildHfrRequest(CameraDevice.TEMPLATE_RECORD, includeRecorder = true)
                                val hs = session as CameraConstrainedHighSpeedCaptureSession
                                val reqList = hs.createHighSpeedRequestList(builder.build())
                                mediaRecorder?.start()
                                hs.setRepeatingBurst(reqList, captureCallback, camHandler)
                                Log.d(TAG, "Recording HFR → encoded @ $encodeFpsForOutput fps")
                                onStarted()
                            } catch (e: Exception) { onError(e) }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { onError(IllegalStateException("Failed HFR record")) }
                        override fun onClosed(session: CameraCaptureSession) { sessionClosedLatch?.countDown() }
                    }, camHandler
                )
            }
            Pipeline.STD60 -> {
                dev.createCaptureSession(
                    listOfNotNull(previewSurface, recorderSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val builder = buildStd60Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = true)
                                mediaRecorder?.start()
                                session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                                Log.d(TAG, "Recording STD60 → encoded @ $encodeFpsForOutput fps (~2× slow)")
                                onStarted()
                            } catch (e: Exception) { onError(e) }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { onError(IllegalStateException("Failed STD60 record")) }
                        override fun onClosed(session: CameraCaptureSession) { sessionClosedLatch?.countDown() }
                    }, camHandler
                )
            }
            Pipeline.ULL15_30 -> {
                dev.createCaptureSession(
                    listOfNotNull(previewSurface, recorderSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val builder = buildUll15_30Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = true)
                                mediaRecorder?.start()
                                session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                                Log.d(TAG, "Recording ULL (low AE range) → encoded @ $encodeFpsForOutput fps")
                                onStarted()
                            } catch (e: Exception) { onError(e) }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { onError(IllegalStateException("Failed ULL record")) }
                        override fun onClosed(session: CameraCaptureSession) { sessionClosedLatch?.countDown() }
                    }, camHandler
                )
            }
        }
        outputFile = file
    }

    fun stopRecording(onSaved: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        var err: Throwable? = null
        try { mediaRecorder?.stop() } catch (e: Exception) { err = e }
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        val f = outputFile
        if (err != null) onError(err!!)
        else if (f != null && f.exists()) onSaved(Uri.fromFile(f))
        else onError(IllegalStateException("No output file"))
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        option?.let { runCatching {
            when (pipeline) {
                Pipeline.HFR      -> configurePreviewHfr(it) {}
                Pipeline.STD60    -> configurePreviewStd60 {}
                Pipeline.ULL15_30 -> configurePreviewUll15_30 {}
            }
        } }
    }

    fun release() {
        ready = false
        try { closeSessionSync() } catch (_: Exception) {}
        cameraDevice?.let { dev ->
            deviceClosedLatch = CountDownLatch(1)
            runCatching { dev.close() }
            cameraDevice = null
            try { deviceClosedLatch?.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            deviceClosedLatch = null
        }
        mediaRecorder?.release(); mediaRecorder = null
        previewSurface = null; recorderSurface = null
        stop()
    }

    private fun closeSessionSync(timeoutMs: Long = 1000) {
        val s = captureSession ?: return
        sessionClosedLatch = CountDownLatch(1)
        runCatching { s.stopRepeating() }
        runCatching { s.abortCaptures() }
        runCatching { s.close() }
        captureSession = null
        try { sessionClosedLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        sessionClosedLatch = null
    }

    private fun createOutputFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val folder = "MySlowMoVideos"
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder)
        if (!dir.exists()) { dir.mkdirs() }
        val file = File(dir, "SLOWMO_${ts}.mp4")
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        return file
    }

    // ---------- EV reapply ----------
    private fun setCurrentEv(evSteps: Int) {
        currentEvSteps = clampEv(evSteps)
        val session = captureSession ?: return
        try {
            when (pipeline) {
                Pipeline.HFR -> {
                    val hs = session as? CameraConstrainedHighSpeedCaptureSession ?: return
                    val builder = buildHfrRequest(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null)
                    val burst = hs.createHighSpeedRequestList(builder.build())
                    hs.setRepeatingBurst(burst, captureCallback, camHandler)
                }
                Pipeline.STD60 -> {
                    val builder = buildStd60Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null)
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
                Pipeline.ULL15_30 -> {
                    val builder = buildUll15_30Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null)
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
            }
            Log.d(TAG, "EV applied: $currentEvSteps")
        } catch (e: Exception) {
            Log.e(TAG, "setCurrentEv failed", e)
        }
    }

    // ---------- Auto callbacks ----------
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            // Diagnostics
            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val frameDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val frac = if (expNs != null && frameDurNs != null && frameDurNs > 0)
                expNs.toDouble() / frameDurNs.toDouble() else -1.0
//            Log.d(TAG, "pipe=$pipeline EV=$currentEvSteps " +
//                    "exp=${expNs?.let { "%.2f".format(it / 1_000_000.0) } ?: "?"}ms " +
//                    "frame=${frameDurNs?.let { "%.2f".format(it / 1_000_000.0) } ?: "?"}ms " +
//                    "iso=${iso ?: "?"} frac=${if (frac >= 0) "%.2f".format(frac) else "?"}")

            if (autoEnvironmentMode) handleEnvironmentHeuristics(result)

            // AutoEV only in HFR (to avoid fighting indoor brightness)
            if (pipeline != Pipeline.HFR) return
            if (!autoEvEnabled) return

            val now = System.nanoTime()
            if (now - lastAdjustNs < adjustCooldownNs) return

            if (expNs == null) return
            val frameTimeNs = frameDurNs ?: (1_000_000_000.0 / targetFpsForBudget).toLong()
            if (frameTimeNs <= 0) return
            val ratio = expNs.toDouble() / frameTimeNs.toDouble()
            val sensitivity = iso ?: return

            val tooBright = ratio < brightThresh && sensitivity <= isoNearMin
            val tooDark   = ratio > darkThresh   && sensitivity >= isoHigh

            if (ratio > 0.9 && sensitivity >= 1600) onTooDark?.invoke()
            if (!tooBright && !tooDark) return

            val delta = if (tooBright) -evStep else +evStep
            val newEv = clampEv(currentEvSteps + delta)
            if (newEv == currentEvSteps) return

            lastAdjustNs = now
            setCurrentEv(newEv)
            Log.d(TAG, "AutoEV -> EV=$newEv")
        }
    }

    private fun handleEnvironmentHeuristics(result: TotalCaptureResult) {
        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val hfrFrameTimeNs = (1_000_000_000.0 / targetFpsForBudget).toLong().coerceAtLeast(1)
        val frac = expNs.toDouble() / hfrFrameTimeNs.toDouble()

        when (envState) {
            Env.OUTDOOR -> {
                when {
                    frac > veryDarkFracThresh && iso >= veryDarkIsoThresh -> {
                        dwellVeryDark++; dwellIndoor = 0
                        if (dwellVeryDark >= dwellThreshold) {
                            switchToUll15_30()
                            envState = Env.VERY_DARK
                            dwellVeryDark = 0
                        }
                    }
                    frac > indoorFracThresh && iso >= indoorIsoThresh -> {
                        dwellIndoor++; dwellVeryDark = 0
                        if (dwellIndoor >= dwellThreshold) {
                            switchToStd60()
                            envState = Env.INDOOR
                            dwellIndoor = 0
                        }
                    }
                    else -> { dwellIndoor = 0; dwellVeryDark = 0 }
                }
            }
            Env.INDOOR -> {
                when {
                    frac > veryDarkFracThresh && iso >= veryDarkIsoThresh -> {
                        dwellVeryDark++
                        if (dwellVeryDark >= dwellThreshold) {
                            switchToUll15_30()
                            envState = Env.VERY_DARK
                            dwellVeryDark = 0
                        }
                    }
                    frac < outdoorFracThresh && iso <= outdoorIsoThresh -> {
                        dwellOutdoor++
                        if (dwellOutdoor >= dwellThreshold) {
                            switchToHfr()
                            envState = Env.OUTDOOR
                            dwellOutdoor = 0
                        }
                    }
                    else -> dwellOutdoor = 0
                }
            }
            Env.VERY_DARK -> {
                if (frac < indoorFracThresh && iso <= indoorIsoThresh) {
                    dwellIndoor++
                    if (dwellIndoor >= dwellThreshold) {
                        switchToStd60()
                        envState = Env.INDOOR
                        dwellIndoor = 0
                    }
                } else dwellIndoor = 0
            }
        }
    }

    // ---------- Auto switches (EV near max indoors + auto-torch) ----------
    private fun switchToStd60() {
        closeSessionSync()
        configurePreviewStd60 { e -> Log.e(TAG, "switchToStd60 failed", e) }
        currentAntibanding = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps)) // biased EV
        autoEvEnabled = false
        evStep = 2; adjustCooldownNs = 120_000_000L
        Log.d(TAG, "Switched → STD60 (EV=${clampEv(aeUpper() + indoorEvBiasSteps)})")
    }

    private fun switchToUll15_30() {
        closeSessionSync()
        configurePreviewUll15_30 { e -> Log.e(TAG, "switchToUll15_30 failed", e) }
        currentAntibanding = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps)) // biased EV (bright but not max)
        autoEvEnabled = false
        evStep = 3; adjustCooldownNs = 90_000_000L
        setTorch(true)            // 🔦 auto-torch in very dark rooms
        onTooDark?.invoke()
        Log.d(TAG, "Switched → ULL (EV=${clampEv(aeUpper() + indoorEvBiasSteps)}) + Torch ON")
    }

    private fun switchToHfr() {
        val opt = option ?: return
        closeSessionSync()
        configurePreviewHfr(opt) { e -> Log.e(TAG, "switchToHfr failed", e) }
        setCurrentEv(0)
        autoEvEnabled = true
        evStep = 1; adjustCooldownNs = 200_000_000L
        setTorch(false)           // ensure torch is off outdoors
        Log.d(TAG, "Switched → HFR (EV=0) + Torch OFF")
    }

    // ---------- Public toggles & tuning ----------
    fun setExposureCompensation(evSteps: Int) {
        autoEvEnabled = false
        setCurrentEv(evSteps)
        Log.d(TAG, "Manual EV set to $evSteps (auto EV disabled)")
    }

    fun setAutoExposureBias(enabled: Boolean) {
        autoEvEnabled = enabled
        Log.d(TAG, "Auto EV ${if (enabled) "enabled" else "disabled"}")
    }

    fun setAutoEnvironmentMode(enabled: Boolean) {
        autoEnvironmentMode = enabled
        Log.d(TAG, "Auto environment ${if (enabled) "enabled" else "disabled"}")
    }

    /** Reduce or increase indoor brightness relative to EV max (negative = dimmer). */
    fun setIndoorBrightnessBias(evStepsBelowMax: Int) {
        indoorEvBiasSteps = evStepsBelowMax
        if (pipeline != Pipeline.HFR) {
            setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps))
        }
        Log.d(TAG, "Indoor EV bias set to $indoorEvBiasSteps")
    }

    /** Adjust ULL FPS preferences (e.g., 24–30) to avoid over-bright long shutters. */
    fun setUllRangePreferences(minLower: Int = 20, maxUpper: Int = 30) {
        ullMinLowerTarget = minLower
        ullPreferredMaxUpper = maxUpper
        if (pipeline == Pipeline.ULL15_30) {
            val builder = buildUll15_30Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null)
            captureSession?.setRepeatingRequest(builder.build(), captureCallback, camHandler)
        }
        Log.d(TAG, "ULL range prefs set: minLower=$ullMinLowerTarget, maxUpper=$ullPreferredMaxUpper")
    }

    // ---------- Focus & Zoom ----------
    fun enableAutoFocus() {
        val session = captureSession ?: return
        try {
            when (pipeline) {
                Pipeline.HFR -> {
                    val hs = session as? CameraConstrainedHighSpeedCaptureSession ?: return
                    val builder = buildHfrRequest(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null)
                    val burst = hs.createHighSpeedRequestList(builder.build())
                    hs.setRepeatingBurst(burst, captureCallback, camHandler)
                }
                Pipeline.STD60 -> {
                    val builder = buildStd60Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null)
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
                Pipeline.ULL15_30 -> {
                    val builder = buildUll15_30Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null)
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Enable AF failed", e) }
    }

    fun setManualFocus(distance: Float) {
        val session = captureSession ?: return
        try {
            when (pipeline) {
                Pipeline.HFR -> {
                    val hs = session as? CameraConstrainedHighSpeedCaptureSession ?: return
                    val builder = buildHfrRequest(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null).apply {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    }
                    val burst = hs.createHighSpeedRequestList(builder.build())
                    hs.setRepeatingBurst(burst, captureCallback, camHandler)
                }
                Pipeline.STD60 -> {
                    val builder = buildStd60Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null).apply {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    }
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
                Pipeline.ULL15_30 -> {
                    val builder = buildUll15_30Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null).apply {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    }
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Set MF failed", e) }
    }

    fun setZoomLevel(zoom: Float) {
        val session = captureSession ?: return
        val opt = option ?: return
        try {
            val chars = manager?.getCameraCharacteristics(opt.cameraId) ?: return
            val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val clampedZoom = zoom.coerceIn(1f, maxZoom)
            val cx = activeRect.width() / 2
            val cy = activeRect.height() / 2
            val dX = (0.5f * activeRect.width() / clampedZoom).toInt()
            val dY = (0.5f * activeRect.height() / clampedZoom).toInt()
            val crop = Rect(cx - dX, cy - dY, cx + dX, cy + dY)

            when (pipeline) {
                Pipeline.HFR -> {
                    val hs = session as? CameraConstrainedHighSpeedCaptureSession ?: return
                    val builder = buildHfrRequest(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null).apply {
                        set(CaptureRequest.SCALER_CROP_REGION, crop)
                    }
                    val burst = hs.createHighSpeedRequestList(builder.build())
                    hs.setRepeatingBurst(burst, captureCallback, camHandler)
                }
                Pipeline.STD60 -> {
                    val builder = buildStd60Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null).apply {
                        set(CaptureRequest.SCALER_CROP_REGION, crop)
                    }
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
                Pipeline.ULL15_30 -> {
                    val builder = buildUll15_30Request(CameraDevice.TEMPLATE_RECORD, includeRecorder = recorderSurface != null).apply {
                        set(CaptureRequest.SCALER_CROP_REGION, crop)
                    }
                    session.setRepeatingRequest(builder.build(), captureCallback, camHandler)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Zoom failed", e) }
    }

    /** Torch is continuous light; flash firing isn’t supported in HFR. */
    fun setTorch(enabled: Boolean) {
        try { manager?.setTorchMode(option?.cameraId ?: return, enabled) }
        catch (e: Exception) { Log.e(TAG, "Torch control failed", e) }
    }
}
