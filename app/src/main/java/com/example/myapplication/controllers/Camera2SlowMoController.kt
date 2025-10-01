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
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.camera.view.PreviewView
import com.example.myapplication.model.SlowMoOption
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

    // Auto environment
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

    // Prefer smoother video outdoors in bright light
    private var preferHfrOutdoors = true
    private val outdoorFracThreshAggressive = 0.35
    private val outdoorIsoThreshAggressive  = 180
    private val dwellOutdoorFastThreshold   = 3

    // Encode at 30 fps by default; ULL is hard-locked to 15 fps
    private val encodeFpsForOutput = 30

    // --- Tuning knobs ---
    private var indoorEvBiasSteps  = -3   // (ULL/STD60) slightly below EV max (when used)
    private var outdoorEvBiasSteps = -2   // HFR outdoors slightly dimmer
    private var ullPreferredMaxUpper = 30
    private var ullMinLowerTarget  = 15

    // Quality knobs
    private var useHevcIfAvailable = true // try HEVC for cleaner picture at same bitrate

    // Remember indoor request made before camera opened
    private var forceIndoorRequested = false

    // ---------- Size from SlowMoOption (no hardcoded dims) ----------
    private var activeSize: Size? = null
    private fun currentSize(): Size {
        val opt = option ?: return Size(1280, 720) // harmless fallback
        return activeSize ?: opt.size
    }

    // Match reference brightness in ULL (negative = darker)
    private var targetEvUllSteps = -6

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
                // Use size from SlowMoOption directly
                activeSize = opt.size
            }
            currentEvSteps = clampEv(0)

            // Use chosen size for HFR range lookup
            targetFpsForBudget = (getSupportedHighSpeedRange(opt)?.upper ?: opt.fpsRange.upper).coerceAtLeast(60)

            // Create preview surface with the chosen size
            previewSurface = waitForPreviewSurface(currentSize(), 1200)
                ?: return onError(IllegalStateException("Preview surface not ready"))

            manager?.openCamera(opt.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    logRecorderSizesOnce()
                    if (forceIndoorRequested) {
                        closeSessionSync()
                        configurePreviewUll15_30 { e ->
                            Log.e(TAG, "onOpened → ULL failed", e)
                            onError(e)
                        }
                        pipeline = Pipeline.ULL15_30
                        Log.d(TAG, "onOpened: honoring pending indoor ULL request")
                    } else {
                        // If the chosen size is not HFR-capable, fall back to STD60
                        val hasHfrForSize = getSupportedHighSpeedRange(opt) != null
                        if (hasHfrForSize) {
                            pipeline = Pipeline.HFR
                            configurePreviewHfr(opt, onError)
                        } else {
                            pipeline = Pipeline.STD60
                            configurePreviewStd60(onError)
                        }
                    }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null; ready = false }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null; ready = false
                    onError(RuntimeException("Camera2 error $error"))
                }
                override fun onClosed(device: CameraDevice) { deviceClosedLatch?.countDown() }
            }, camHandler)
        } catch (e: Exception) { onError(e) }
    }

    // ---------- Force Indoor Bright ----------
    fun forceIndoorBrightMode(enable: Boolean, mainsHz: Int = 50) {
        forceIndoorRequested = enable
        if (enable) {
            autoEnvironmentMode = false
            autoEvEnabled = false
            currentAntibanding = if (mainsHz == 60)
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ

            setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps))

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
                // If size doesn’t support HFR, go STD60
                if (getSupportedHighSpeedRange(opt) != null) {
                    configurePreviewHfr(opt) { e -> Log.e(TAG, "forceIndoorBrightMode → HFR failed", e) }
                } else {
                    configurePreviewStd60 { e -> Log.e(TAG, "forceIndoorBrightMode → STD60 failed", e) }
                    pipeline = Pipeline.STD60
                    return
                }
            }
            Log.d(TAG, "forceIndoorBrightMode: DISABLED (HFR)")
        }
    }

    fun setPreferHfrOutdoors(enabled: Boolean) {
        preferHfrOutdoors = enabled
        Log.d(TAG, "Prefer HFR outdoors: $preferHfrOutdoors")
    }

    fun setOutdoorBrightnessBias(evStepsBelowNeutral: Int) {
        outdoorEvBiasSteps = evStepsBelowNeutral
        if (pipeline == Pipeline.HFR && envState == Env.OUTDOOR) {
            setCurrentEv(clampEv(outdoorEvBiasSteps))
        }
        Log.d(TAG, "Outdoor EV bias set to $outdoorEvBiasSteps")
    }

    // ---------- Surface ----------
    private fun waitForPreviewSurface(size: Size, timeoutMs: Long): Surface? {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

        // Hint PreviewView to use TextureView mode if supported
        try { previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE } catch (_: Throwable) {}

        while (System.nanoTime() < end) {
            val childCount = previewView.childCount
            for (i in 0 until childCount) {
                val v = previewView.getChildAt(i)
                when (v) {
                    is android.view.TextureView -> {
                        val st: SurfaceTexture? = v.surfaceTexture
                        if (v.isAvailable && st != null) {
                            st.setDefaultBufferSize(size.width, size.height)
                            return Surface(st)
                        }
                    }
                    is android.view.SurfaceView -> {
                        val s = v.holder?.surface
                        if (s != null && s.isValid) {
                            return s
                        }
                    }
                }
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
        }
        return null
    }

    // ---------- Helpers ----------
    private fun clampEv(ev: Int): Int = aeCompRange?.let { ev.coerceIn(it.lower, it.upper) } ?: 0
    private fun aeUpper(): Int = aeCompRange?.upper ?: 0

    // Use the active (opt) size
    private fun getSupportedHighSpeedRange(@Suppress("UNUSED_PARAMETER") opt: SlowMoOption): Range<Int>? {
        val camId = option?.cameraId ?: return null
        val chars = manager?.getCameraCharacteristics(camId) ?: return null
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val supported = map.getHighSpeedVideoFpsRangesFor(currentSize()) ?: return null
        val fixed = supported.filter { it.lower == it.upper }
        val best120 = fixed.filter { it.upper >= 120 }.maxByOrNull { it.upper }
        val bestFixed = best120 ?: fixed.maxByOrNull { it.upper }
        return bestFixed ?: supported.maxByOrNull { it.upper }
    }

    private fun getAvailableNormalFpsRanges(): Array<Range<Int>> {
        val chars = manager?.getCameraCharacteristics(option?.cameraId ?: return emptyArray())
            ?: return emptyArray()
        return chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
    }

    private fun pickLowLightFpsRange(
        preferredMaxUpper: Int = ullPreferredMaxUpper,
        minLowerTarget: Int = ullMinLowerTarget
    ): Range<Int>? {
        val ranges = getAvailableNormalFpsRanges()
        if (ranges.isEmpty()) return null
        val good = ranges.filter { it.upper <= preferredMaxUpper && it.lower >= minLowerTarget }
        if (good.isNotEmpty()) return good.minByOrNull { it.lower }
        val capped = ranges.filter { it.upper <= preferredMaxUpper }
        if (capped.isNotEmpty()) return capped.minByOrNull { it.lower }
        return ranges.minByOrNull { it.lower }
    }

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

    private fun getCurrentAeFpsRange(): Range<Int>? {
        val opt = option ?: return null
        return when (pipeline) {
            Pipeline.HFR      -> getSupportedHighSpeedRange(opt)
            Pipeline.STD60    -> pickStd60FpsRange()
            Pipeline.ULL15_30 -> pickLowLightFpsRange(ullPreferredMaxUpper, ullMinLowerTarget)
        }
    }

    private fun supportsVideoStab(): Boolean {
        val camId = option?.cameraId ?: return false
        val chars = manager?.getCameraCharacteristics(camId) ?: return false
        val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: return false
        return modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    }

    // ---------- Requests ----------
    private fun buildHfrRequest(template: Int, includeRecorder: Boolean): CaptureRequest.Builder {
        val dev = cameraDevice ?: throw IllegalStateException("Camera not ready")
        return dev.createCaptureRequest(template).apply {
            previewSurface?.let { addTarget(it) }
            if (includeRecorder) recorderSurface?.let { addTarget(it) }

            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

            // Slightly dimmer & sharper outdoors
            val evForHfr = if (envState == Env.OUTDOOR) clampEv(outdoorEvBiasSteps) else 0
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evForHfr)

            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)
            getCurrentAeFpsRange()?.let { r -> if (r.lower == r.upper) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r) }
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // HQ hints (device may ignore)
            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)

            if (envState == Env.OUTDOOR) {
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            } else {
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            }

            if (supportsVideoStab()) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
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
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(aeUpper() + indoorEvBiasSteps))
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)
            val std = pickStd60FpsRange()
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, std ?: Range(24, 60))
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            if (supportsVideoStab()) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
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
            // Match darker ULL brightness
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(targetEvUllSteps))
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)

            val low = pickLowLightFpsRange(ullPreferredMaxUpper, ullMinLowerTarget)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, low ?: Range(15, 30))

            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            if (supportsVideoStab()) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
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
                            Log.d(TAG, "Preview configured: HFR @ size=${currentSize().width}x${currentSize().height}")
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
                            Log.d(TAG, "Preview configured: STD60 @ size=${currentSize().width}x${currentSize().height}")
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
                            Log.d(TAG, "Preview configured: ULL (low AE range) @ size=${currentSize().width}x${currentSize().height}")
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
            // AUDIO first
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)

            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            // Audio
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48_000)
            setAudioEncodingBitRate(128_000)
            setAudioChannels(2)

            setOutputFile(file.absolutePath)

            // Codec: use AVC for ULL; allow HEVC elsewhere
            val usingHevc = if (useHevcIfAvailable && pipeline != Pipeline.ULL15_30) trySetHevc(this) else false
            if (!usingHevc) {
                setVideoEncoder(MediaRecorder.VideoEncoder.H264) // "avc1"
            }

            // FPS
            val outFps = if (pipeline == Pipeline.ULL15_30) 15 else encodeFpsForOutput
            setVideoFrameRate(outFps)

            // SIZE — always use opt.size (via activeSize/currentSize)
            val sz = currentSize()
            val (encW, encH) = ensureEven(sz.width, sz.height)
            setVideoSize(encW, encH)

            // Bitrate per pipeline (scales with size & fps)
            val bpp = when (pipeline) {
                Pipeline.ULL15_30 -> 0.16  // consider 0.18 for a touch more detail
                Pipeline.STD60    -> 0.20
                Pipeline.HFR      ->
                    if (envState == Env.OUTDOOR) 0.24 else 0.20
            }
            val targetBitrate = (encW.toLong() * encH.toLong() * outFps * bpp).toInt().coerceAtLeast(1_200_000)
            setVideoEncodingBitRate(targetBitrate)

            prepare()
            Log.d(TAG, "Recorder outFps=$outFps, pipe=$pipeline, env=$envState, AE=${getCurrentAeFpsRange()}, size=${encW}x${encH}, vbitrate=$targetBitrate, codec=${if (usingHevc) "HEVC" else "H264"}")
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
                                Log.d(TAG, "Recording HFR → encoded @ ${mediaRecorderFrameRate()} fps")
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
                                Log.d(TAG, "Recording STD60 → encoded @ ${mediaRecorderFrameRate()} fps (~2× slow)")
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
                                Log.d(TAG, "Recording ULL → encoded @ ${mediaRecorderFrameRate()} fps")
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

    private fun trySetHevc(rec: MediaRecorder): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "HEVC not available, fallback to H.264", e)
            false
        }
    }

    private fun mediaRecorderFrameRate(): Int {
        return if (pipeline == Pipeline.ULL15_30) 15 else encodeFpsForOutput
    }

    fun stopRecording(onSaved: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        var err: Throwable? = null
        try { mediaRecorder?.stop() } catch (e: Exception) { err = e }
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        val f = outputFile
        if (err != null) onError(err!!)
        else if (f != null && f.exists()) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(f.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            onSaved(Uri.fromFile(f))
        } else onError(IllegalStateException("No output file"))
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
        return File(dir, "SLOWMO_${ts}.mp4")
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
            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val frameDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)

            if (autoEnvironmentMode) handleEnvironmentHeuristics(result)

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
        theLoop@ run {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val hfrFrameTimeNs = (1_000_000_000.0 / targetFpsForBudget).toLong().coerceAtLeast(1)
            val frac = expNs.toDouble() / hfrFrameTimeNs.toDouble()

            when (envState) {
                Env.OUTDOOR -> {
                    if (preferHfrOutdoors &&
                        frac < outdoorFracThreshAggressive &&
                        iso  <= outdoorIsoThreshAggressive) {
                        dwellOutdoor++
                        if (dwellOutdoor >= dwellOutdoorFastThreshold) {
                            if (pipeline != Pipeline.HFR) {
                                switchToHfr()
                                Log.d(TAG, "AutoSun: OUTDOOR bright → HFR (frac=${"%.2f".format(frac)} iso=$iso)")
                            } else {
                                setCurrentEv(clampEv(outdoorEvBiasSteps))
                            }
                            dwellOutdoor = 0
                        }
                    } else dwellOutdoor = 0

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
                    }
                }
                Env.INDOOR -> {
                    if (preferHfrOutdoors &&
                        frac < outdoorFracThreshAggressive &&
                        iso  <= outdoorIsoThreshAggressive) {
                        dwellOutdoor++
                        if (dwellOutdoor >= dwellOutdoorFastThreshold) {
                            switchToHfr()
                            envState = Env.OUTDOOR
                            dwellOutdoor = 0
                            Log.d(TAG, "AutoSun: INDOOR→OUTDOOR bright → HFR (frac=${"%.2f".format(frac)} iso=$iso)")
                            return
                        }
                    } else dwellOutdoor = 0

                    if (frac > veryDarkFracThresh && iso >= veryDarkIsoThresh) {
                        dwellVeryDark++
                        if (dwellVeryDark >= dwellThreshold) {
                            switchToUll15_30()
                            envState = Env.VERY_DARK
                            dwellVeryDark = 0
                        }
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
    }

    // ---------- Auto switches ----------
    private fun switchToStd60() {
        closeSessionSync()
        configurePreviewStd60 { e -> Log.e(TAG, "switchToStd60 failed", e) }
        currentAntibanding = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps))
        autoEvEnabled = false
        evStep = 2; adjustCooldownNs = 120_000_000L
        Log.d(TAG, "Switched → STD60 (EV=${clampEv(aeUpper() + indoorEvBiasSteps)})")
    }

    private fun switchToUll15_30() {
        closeSessionSync()
        configurePreviewUll15_30 { e -> Log.e(TAG, "switchToUll15_30 failed", e) }
        currentAntibanding = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps))
        autoEvEnabled = false
        evStep = 3; adjustCooldownNs = 90_000_000L
        setTorch(true)
        onTooDark?.invoke()
        Log.d(TAG, "Switched → ULL (EV=${clampEv(aeUpper() + indoorEvBiasSteps)}) + Torch ON")
    }

    private fun switchToHfr() {
        val opt = option ?: return
        closeSessionSync()
        configurePreviewHfr(opt) { e -> Log.e(TAG, "switchToHfr failed", e) }
        val ev = if (envState == Env.OUTDOOR) clampEv(outdoorEvBiasSteps) else 0
        setCurrentEv(ev)
        autoEvEnabled = true
        evStep = 1; adjustCooldownNs = 200_000_000L
        setTorch(false)
        Log.d(TAG, "Switched → HFR (EV=$ev) + Torch OFF")
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

    fun setIndoorBrightnessBias(evStepsBelowMax: Int) {
        indoorEvBiasSteps = evStepsBelowMax
        if (pipeline != Pipeline.HFR) {
            setCurrentEv(clampEv(aeUpper() + indoorEvBiasSteps))
        }
        Log.d(TAG, "Indoor EV bias set to $indoorEvBiasSteps")
    }

    fun setUllRangePreferences(minLower: Int = 15, maxUpper: Int = 30) {
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
        Log.d(TAG, "zoom $zoom")
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

    fun setTorch(enabled: Boolean) {
        try {
            val camId = option?.cameraId ?: return
            val chars = manager?.getCameraCharacteristics(camId) ?: return
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) manager?.setTorchMode(camId, enabled)
        } catch (e: Exception) { Log.e(TAG, "Torch control failed", e) }
    }

    // ------ ULL recorder size picker (kept for reference but unused now) ------
    private fun pickUllRecordSizePrefer848x480(): Size {
        val camId = option?.cameraId ?: return Size(720, 480)
        val chars = manager?.getCameraCharacteristics(camId) ?: return Size(720, 480)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(720, 480)
        val supported = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()

        val prefer = listOf(
            Size(848, 480), Size(854, 480), Size(864, 480),
            Size(720, 480), Size(640, 360), Size(640, 480)
        )
        for (c in prefer) if (supported.any { it.width == c.width && it.height == c.height }) return c

        val approx = supported
            .filter { it.height in 400..520 }
            .sortedBy { abs(it.height - 480) + abs((it.width.toFloat()/it.height) - (16f/9f)) }
            .firstOrNull()
        return approx ?: Size(720, 480)
    }

    private fun ensureEven(w: Int, h: Int): Pair<Int, Int> {
        val ew = ((w / 2) * 2).coerceAtLeast(2)
        val eh = ((h / 2) * 2).coerceAtLeast(2)
        return ew to eh
    }

    private fun logRecorderSizesOnce() {
        try {
            val camId = option?.cameraId ?: return
            val chars = manager?.getCameraCharacteristics(camId) ?: return
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(MediaRecorder::class.java)?.joinToString { "${it.width}x${it.height}" }
            Log.d(TAG, "MediaRecorder supported sizes: $sizes")
        } catch (_: Exception) { /* ignore */ }
    }
}
