package com.example.myapplication.controllers

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresPermission
import androidx.camera.view.PreviewView
import androidx.core.animation.addListener
import com.example.myapplication.helper.retimeToFixedFps
import com.example.myapplication.model.SlowMoOption
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

class Camera2SlowMoController(
    private val context: Context,
    private val previewView: PreviewView
) {
    private val TAG = "Camera2SlowMo"

    // Threads / handlers
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Camera state
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    var manager: CameraManager? = null

    // Session target bookkeeping (avoid "unconfigured Surface" errors)
    private var sessionTargets: MutableSet<Surface> = mutableSetOf()
    private var sessionHasRecorder: Boolean = false

    // Surfaces
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    // Recording
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    // Option / readiness
    private var option: SlowMoOption? = null

    @Volatile
    private var ready = false
    fun isReady() = ready

    // Latches
    private var deviceClosedLatch: CountDownLatch? = null
    private var sessionClosedLatch: CountDownLatch? = null

    // EV / AE
    private var aeCompRange: Range<Int>? = null
    private var currentEvDelta: Int = 0

    // Env state
    private enum class Pipeline { HFR, STD60, VERY_DARK }
    private enum class Env { OUTDOOR, INDOOR, VERY_DARK }

    private var pipeline: Pipeline = Pipeline.HFR
    private var envState: Env = Env.INDOOR

    // Behavior knobs
    private var useTemplateRecordForPreview = true
    private var currentAntibanding = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
    private var autoEnvironmentMode = true
    private var autoEvEnabled = true
    private var targetFpsForBudget = 120
    private var evStep = 1
    private var lastAdjustNs = 0L
    private var adjustCooldownNs = 200_000_000L

    // AutoEV heuristics
    private val brightThresh = 0.20
    private val darkThresh = 0.80
    private val isoHigh = 800
    private val isoNearMin = 120

    // EV100-based environment classifier
    private var lensAperture: Float = 1.8f
    private val evBuf = DoubleArray(12) { 0.0 }
    private var evBufCount = 0
    private var evBufIdx = 0
    private fun pushEv(ev: Double) {
        evBuf[evBufIdx] = ev
        evBufIdx = (evBufIdx + 1) % evBuf.size
        if (evBufCount < evBuf.size) evBufCount++
    }

    private fun avgEv(): Double =
        if (evBufCount == 0) 0.0 else (0 until evBufCount).sumOf { evBuf[it] } / evBufCount.toDouble()

    // Hysteresis thresholds
    private val OUTDOOR_ENTER = 12.0
    private val OUTDOOR_EXIT = 10.5
    private val VERYDARK_ENTER = 6.0
    private val VERYDARK_EXIT = 7.0

    // Dwell counters to avoid flapping
    private var dwellToOutdoor = 0
    private var dwellToIndoor = 0
    private var dwellToVeryDark = 0
    private val dwellConfirm = 3
    private var isFirstTime = true

    // Size selection
    private var autoSelectBestHfrSize = true
    private var preferAspectFromOption = true
    private var allowDownscaleForHfr = true
    private var activeSize: Size? = null
    private fun currentSize(): Size = activeSize ?: option?.size ?: Size(1280, 720)

    // Bitrate / codec
    private var useHevcIfAvailable = true

    // Focus/zoom
    private enum class FocusState { AUTO, MANUAL }

    private var focusState: FocusState = FocusState.AUTO
    private var focusDistance: Float = 0f
    private var zoomLevel: Float = 1.2f
    private var maxDigitalZoom: Float = 5f
    private var activeArrayRect: Rect? = null

    // Indoor/Very Dark brightness policy
    private var indoorHfrBaseEv = +6
    private var maxAutoEvDelta = +6
    private var autoTorchEnabled = false
    private var torchOn = false
    private var tonemapBoostActive = false
    private var wantTonemapBoost = true
    private var allowPostRawBoost = true
    private var postRawBoostValue = 200

    private var preferHfrOutdoors: Boolean = true

    // Smooth switch overlay
    private var freezeDrawable: Drawable? = null
    private var isSwitching = false
    private var autoSwitching = true

    // UI hook
    var onTooDark: (() -> Unit)? = null

    // Debug helpers
    private fun clampEv(ev: Int): Int = aeCompRange?.let { ev.coerceIn(it.lower, it.upper) } ?: ev
    private fun aeUpper(): Int = aeCompRange?.upper ?: 6
    private fun surfaceUsable(s: Surface?): Boolean = s != null && s.isValid
    private fun sessionContains(surface: Surface?): Boolean =
        surface != null && sessionTargets.contains(surface)

    // ===== Public API =====
    fun setAutoSelectBestHfrSize(enabled: Boolean) {
        autoSelectBestHfrSize = enabled
    }

    fun setPreferHfrOutdoors(enabled: Boolean) {
        preferHfrOutdoors = enabled
    }

    fun setPreviewBoostEnabled(enabled: Boolean) {
        wantTonemapBoost = enabled; reapplyRepeating("togglePreviewBoost")
    }

    fun setPostRawBoost(enabled: Boolean, value: Int = 200) {
        allowPostRawBoost = enabled; postRawBoostValue =
            value; reapplyRepeating("togglePostRawBoost")
    }

    fun setAutoTorchEnabled(enabled: Boolean) {
        autoTorchEnabled = enabled; if (!enabled && torchOn) setTorch(false)
    }

    fun setIndoorHfrBaseBias(evSteps: Int, maxAutoDelta: Int = 6) {
        indoorHfrBaseEv = clampEv(evSteps); maxAutoEvDelta = maxAutoDelta.coerceIn(0, 6)
        Log.d(TAG, "Indoor HFR base EV bias set to $indoorHfrBaseEv")
        if (pipeline == Pipeline.HFR && envState == Env.INDOOR) setCurrentEv(0)
    }

    fun setMainsHz(hz: Int) {
        currentAntibanding = when (hz) {
            50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        reapplyRepeating("setMainsHz=$hz")
    }

    fun setAutoEnvironmentMode(enabled: Boolean) {
        autoEnvironmentMode = enabled
        Log.d(TAG, "Auto environment ${if (enabled) "enabled" else "disabled"}")
    }

    fun setAutoExposureBias(enabled: Boolean) {
        autoEvEnabled = enabled
        Log.d(TAG, "Auto EV ${if (enabled) "enabled" else "disabled"}")
    }

    // Lifecycle
    fun start() {
        if (camThread != null) return
        camThread = HandlerThread("Camera2SlowMo").also { it.start() }
        camHandler = Handler(camThread!!.looper)
    }

    fun stop() {
        camThread?.quitSafely(); camThread = null; camHandler = null
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun bind(opt: SlowMoOption, onError: (Throwable) -> Unit = {}) {
        start()
        ready = false
        option = opt

        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val chars = manager?.getCameraCharacteristics(opt.cameraId)
            if (chars != null) {
                aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                activeArrayRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                maxDigitalZoom =
                    chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                zoomLevel = zoomLevel.coerceIn(1f, maxDigitalZoom.coerceAtMost(5f))
                activeSize =
                    if (autoSelectBestHfrSize) pickBestHfrSize(chars, opt.size) else opt.size

                // lens aperture for EV100 computation
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                if (apertures != null && apertures.isNotEmpty()) {
                    lensAperture = apertures.minOrNull() ?: apertures[0]
                }
                Log.d(TAG, "Aperture set for EV100: f/$lensAperture")
            } else {
                activeSize = opt.size
            }

            currentEvDelta = 0
            targetFpsForBudget =
                max(getSupportedHighSpeedRange(opt)?.upper ?: 0, opt.fpsRange.upper).coerceAtLeast(
                    60
                )

            previewSurface = waitForPreviewSurface(currentSize(), 1200)
                ?: return onError(IllegalStateException("Preview surface not ready"))

            manager?.openCamera(opt.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    logRecorderSizesOnce()
                    if (getSupportedHighSpeedRange(opt) != null) {
                        pipeline = Pipeline.HFR
                        rebuildSession(withRecorder = false) { e ->
                            Log.e(
                                TAG,
                                "HFR preview failed",
                                e
                            ); onError(e)
                        }
                    } else {
                        pipeline = Pipeline.STD60
                        rebuildSession(withRecorder = false) { e ->
                            Log.e(
                                TAG,
                                "STD preview failed",
                                e
                            ); onError(e)
                        }
                    }
                    logMode("onOpened")
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close(); cameraDevice = null; ready = false
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null; ready = false
                    onError(RuntimeException("Camera2 error $error"))
                }

                override fun onClosed(device: CameraDevice) {
                    deviceClosedLatch?.countDown()
                }
            }, camHandler)
        } catch (e: Exception) {
            onError(e)
        }
    }

    // ===== FPS helpers bound to SlowMoOption =====
    private fun desiredFixedFps(): Int {
        return option?.fpsRange?.upper ?: 120
    }

    // ---- Size picker (binds to desiredFixedFps, no hardcoded tiers) ----
    private fun pickBestHfrSize(chars: CameraCharacteristics, fallback: Size): Size {
        val map =
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fallback
        val hsSizes = try {
            map.highSpeedVideoSizes
        } catch (_: Throwable) {
            null
        } ?: return fallback

        val wantFps = desiredFixedFps()
        val desiredAspect = if (preferAspectFromOption) aspect(fallback) else null

        fun bestFixedFor(sz: Size, atLeast: Int): Int? {
            val ranges = map.getHighSpeedVideoFpsRangesFor(sz) ?: return null
            val fixed = ranges.filter { it.lower == it.upper }.map { it.upper }
            if (fixed.isEmpty()) return null
            val ge = fixed.filter { it >= atLeast }
            return ge.minOrNull() ?: fixed.maxOrNull()
        }

        data class Cand(val size: Size, val bestFixed: Int)

        val cands = hsSizes.mapNotNull { sz ->
            val best = bestFixedFor(sz, wantFps) ?: return@mapNotNull null
            Cand(sz, best)
        }
        if (cands.isEmpty()) return fallback

        val (geDesired, ltDesired) = cands.partition { it.bestFixed >= wantFps }
        val ranked = (if (geDesired.isNotEmpty()) geDesired else ltDesired).sortedWith(
            compareBy<Cand> {
                if (desiredAspect == null) 0.0 else abs(aspect(it.size) - desiredAspect)
            }.thenByDescending { it.size.width * it.size.height }
                .thenByDescending { it.bestFixed }
        )

        val pick = ranked.first().size
        Log.d(TAG, "AutoSize pick: ${pick.width}x${pick.height} (wantFixedFps=$wantFps)")
        return pick
    }

    private fun aspect(s: Size) = s.width.toDouble() / s.height.toDouble()

    // ---- Surface wait ----
    private fun waitForPreviewSurface(size: Size, timeoutMs: Long): Surface? {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        } catch (_: Throwable) {
        }
        while (System.nanoTime() < end) {
            for (i in 0 until previewView.childCount) {
                val v = previewView.getChildAt(i)
                when (v) {
                    is android.view.TextureView -> {
                        val st: SurfaceTexture? = v.surfaceTexture
                        if (v.isAvailable && st != null) {
                            st.setDefaultBufferSize(size.width, size.height)
                            return Surface(st)
                        }
                    }

                    is android.view.SurfaceView -> v.holder?.surface?.let { if (it.isValid) return it }
                }
            }
            Thread.sleep(20)
        }
        return null
    }

    // ---- FPS ranges ----
    private fun getSupportedHighSpeedRange(opt: SlowMoOption): Range<Int>? {
        val chars = manager?.getCameraCharacteristics(opt.cameraId) ?: return null
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val supported = map.getHighSpeedVideoFpsRangesFor(currentSize()) ?: return null
        val wantFps = desiredFixedFps()

        val fixed = supported.filter { it.lower == it.upper }
        if (fixed.isNotEmpty()) {
            val ge = fixed.filter { it.upper >= wantFps }
            return ge.minByOrNull { it.upper } ?: fixed.maxByOrNull { it.upper }
        }
        // fallback: closest upper to want
        return supported.minByOrNull { abs(it.upper - wantFps) }
    }

    private fun getAvailableNormalFpsRanges(): Array<Range<Int>> {
        val chars = manager?.getCameraCharacteristics(option?.cameraId ?: return emptyArray())
            ?: return emptyArray()
        return chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
    }

    private fun pickStd60FpsRange(): Range<Int>? {
        val ranges = getAvailableNormalFpsRanges(); if (ranges.isEmpty()) return null
        val exact24_60 = ranges.firstOrNull { it.lower == 24 && it.upper == 60 }
            ?: ranges.firstOrNull { it.lower == 30 && it.upper == 60 }
        return exact24_60 ?: ranges.filter { it.upper >= 60 }.minByOrNull { it.lower }
        ?: ranges.minByOrNull { it.lower }
    }

    private fun getCurrentAeFpsRange(): Range<Int>? = when (pipeline) {
        Pipeline.HFR -> option?.let { getSupportedHighSpeedRange(it) }
        Pipeline.STD60 -> pickStd60FpsRange()
        Pipeline.VERY_DARK -> Range(60, 60)
    }

    private fun supportsVideoStab(): Boolean {
        val camId = option?.cameraId ?: return false
        val chars = manager?.getCameraCharacteristics(camId) ?: return false
        val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?: return false
        return modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    }

    private fun cropRegionForZoom(z: Float): Rect? {
        val sensor = activeArrayRect ?: return null
        val zoom = z.coerceIn(1f, maxDigitalZoom.coerceAtMost(5f))
        if (zoom <= 1f) return sensor
        val cx = sensor.centerX();
        val cy = sensor.centerY()
        val hw = (sensor.width() / (2f * zoom)).toInt()
        val hh = (sensor.height() / (2f * zoom)).toInt()
        return Rect(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    // ---- Tonemap curve (preview lift) ----
    private fun makeGammaCurve(g: Float = 0.45f): TonemapCurve {
        fun p(x: Float) = (x.toDouble().pow(1.0 / g)).toFloat().coerceIn(0f, 1f)
        val steps = 16
        val rgb = FloatArray(steps * 2)
        for (i in 0 until steps) {
            val x = i / (steps - 1f)
            val y = p(x); rgb[2 * i] = x; rgb[2 * i + 1] = y
        }
        return TonemapCurve(rgb, rgb, rgb)
    }

    // ---- Build requests ----
    private fun buildHfrRequest(template: Int, wantRecorder: Boolean): CaptureRequest.Builder {
        val dev = cameraDevice ?: throw IllegalStateException("Camera not ready")
        val includeRecorder = wantRecorder && sessionHasRecorder && sessionContains(recorderSurface)
        val b = dev.createCaptureRequest(template)
        previewSurface?.let { if (sessionContains(it)) b.addTarget(it) }
        if (includeRecorder) recorderSurface?.let { if (sessionContains(it)) b.addTarget(it) }

        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

        when (focusState) {
            FocusState.AUTO -> b.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            FocusState.MANUAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }
        }
        cropRegionForZoom(zoomLevel)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }

        val baseEv = if (envState == Env.OUTDOOR) -2 else indoorHfrBaseEv
        val effEv = clampEv(baseEv + currentEvDelta)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, effEv)
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)

        getCurrentAeFpsRange()?.let { r ->
            if (r.lower == r.upper) b.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                r
            )
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        if (wantTonemapBoost && tonemapBoostActive) {
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, makeGammaCurve(0.45f))
        } else b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)

        if (allowPostRawBoost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                b.set(
                    CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                    postRawBoostValue
                )
            }
        }

        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (supportsVideoStab()) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        b.set(
            CaptureRequest.FLASH_MODE,
            if (autoTorchEnabled && torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )

        return b
    }

    private fun buildStdRequest(
        template: Int,
        wantRecorder: Boolean,
        fpsRange: Range<Int>
    ): CaptureRequest.Builder {
        val dev = cameraDevice ?: throw IllegalStateException("Camera not ready")
        val includeRecorder = wantRecorder && sessionHasRecorder && sessionContains(recorderSurface)
        val b = dev.createCaptureRequest(template)
        previewSurface?.let { if (sessionContains(it)) b.addTarget(it) }
        if (includeRecorder) recorderSurface?.let { if (sessionContains(it)) b.addTarget(it) }

        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

        when (focusState) {
            FocusState.AUTO -> b.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            FocusState.MANUAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }
        }
        cropRegionForZoom(zoomLevel)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }

        val baseEv = if (envState == Env.OUTDOOR) -2 else indoorHfrBaseEv
        val effEv = clampEv(baseEv + currentEvDelta)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, effEv)
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntibanding)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)

        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (supportsVideoStab()) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        b.set(
            CaptureRequest.FLASH_MODE,
            if (autoTorchEnabled && torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )

        return b
    }

    // ---- Session (always configure with EXACT targets we will use) ----
    private fun rebuildSession(withRecorder: Boolean, onError: (Throwable) -> Unit = {}) {
        val dev = cameraDevice ?: return
        if (!surfaceUsable(previewSurface)) {
            previewSurface = waitForPreviewSurface(currentSize(), 1500)
            if (!surfaceUsable(previewSurface)) {
                onError(IllegalStateException("Preview surface unavailable"))
                return
            }
        }

        val outputs = mutableListOf<Surface>()
        previewSurface?.let { outputs.add(it) }
        val needRecorder = withRecorder && mediaRecorder != null && surfaceUsable(recorderSurface)
        if (needRecorder) recorderSurface?.let { outputs.add(it) }

        closeSessionSync()

        try {
            if (pipeline == Pipeline.HFR) {
                // HFR session
                dev.createConstrainedHighSpeedCaptureSession(
                    outputs,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            sessionTargets.clear(); sessionTargets.addAll(outputs)
                            sessionHasRecorder = needRecorder
                            try {
                                val template =
                                    if (useTemplateRecordForPreview && needRecorder) CameraDevice.TEMPLATE_RECORD
                                    else CameraDevice.TEMPLATE_PREVIEW
                                val b = buildHfrRequest(template, wantRecorder = needRecorder)
                                val hs = session as CameraConstrainedHighSpeedCaptureSession
                                val burst = hs.createHighSpeedRequestList(b.build())
                                hs.setRepeatingBurst(burst, captureCallback, camHandler)
                                ready = true
                                Log.d(
                                    TAG,
                                    "Preview HFR; range=${getCurrentAeFpsRange()} size=${currentSize().width}x${currentSize().height}"
                                )
                                onNewSessionConfigured()
                                logMode("Preview HFR")
                            } catch (e: Exception) {
                                onError(e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            onError(IllegalStateException("HFR configure failed"))
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            sessionClosedLatch?.countDown()
                        }
                    }, camHandler
                )
            } else {
                // STD/VERY_DARK session
                dev.createCaptureSession(
                    outputs,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            sessionTargets.clear(); sessionTargets.addAll(outputs)
                            sessionHasRecorder = needRecorder
                            try {
                                val fps = when (pipeline) {
                                    Pipeline.STD60 -> pickStd60FpsRange() ?: Range(60, 60)
                                    Pipeline.VERY_DARK -> Range(60, 60)
                                    else -> Range(60, 60)
                                }
                                val template =
                                    if (needRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                                val b = buildStdRequest(
                                    template,
                                    wantRecorder = needRecorder,
                                    fpsRange = fps
                                )
                                session.setRepeatingRequest(b.build(), captureCallback, camHandler)
                                ready = true
                                Log.d(
                                    TAG,
                                    "Preview ${pipeline.name}; range=$fps size=${currentSize().width}x${currentSize().height}"
                                )
                                onNewSessionConfigured()
                                logMode("Preview ${pipeline.name}")
                            } catch (e: Exception) {
                                onError(e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            onError(IllegalStateException("STD/ULL configure failed"))
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            sessionClosedLatch?.countDown()
                        }
                    }, camHandler
                )
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    // Called when a brand-new repeating request starts; handles overlay fade-out if switching
    private fun onNewSessionConfigured() {
        if (!isSwitching) return
        mainHandler.post {
            freezeDrawable?.let { drawable ->
                // Fade out and then remove the overlay
                ObjectAnimator.ofInt(drawable, "alpha", 255, 0).apply {
                    duration = 180L
                    addListener(onEnd = {
                        previewView.overlay.remove(drawable)
                        freezeDrawable = null
                        isSwitching = false
                    })
                    start()
                }
            } ?: run { isSwitching = false }
        }
    }

    // ---- Smooth mode switch (freeze current frame, rebuild, then fade) ----
    private fun smoothSwitchTo(newPipe: Pipeline, withRecorder: Boolean) {
        if (pipeline == newPipe || isSwitching) {
            // no-op if already in this mode or already switching
            return
        }
        isSwitching = true

        // 1) Capture a bitmap of the current preview (works when TextureView is the child)
        val snapshot = capturePreviewBitmapSafely()
        if (snapshot != null) {
            val drawable = BitmapDrawable(previewView.resources, snapshot).apply { alpha = 255 }
            freezeDrawable = drawable
            // 2) Place the overlay atop the preview
            mainHandler.post { previewView.overlay.add(drawable) }
        } else {
            // If we failed to capture, still proceed; just switch without overlay
            freezeDrawable = null
        }

        // 3) Switch pipeline + rebuild session; overlay will be faded in onNewSessionConfigured()
        pipeline = newPipe
        rebuildSession(withRecorder = withRecorder) { e ->
            Log.e(TAG, "rebuild during smoothSwitch failed", e)
            // Ensure overlay is removed to avoid getting stuck
            mainHandler.post {
                freezeDrawable?.let { previewView.overlay.remove(it) }
                freezeDrawable = null
                isSwitching = false
            }
        }
    }

    private fun capturePreviewBitmapSafely(): Bitmap? {
        // We set PreviewView.ImplementationMode.COMPATIBLE, so child is TextureView or SurfaceView.
        for (i in 0 until previewView.childCount) {
            val v = previewView.getChildAt(i)
            if (v is TextureView && v.isAvailable) {
                return try {
                    v.bitmap
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }

    // ---- RECORD ----
    fun startRecording(
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val dev = cameraDevice ?: return onError(IllegalStateException("Camera not ready"))
        val file = createOutputFile()

        val recordFps = when (pipeline) {
            Pipeline.HFR -> getCurrentAeFpsRange()?.upper ?: desiredFixedFps()
            Pipeline.STD60 -> pickStd60FpsRange()?.upper ?: 60
            Pipeline.VERY_DARK -> 60
        }

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48_000)
            setAudioEncodingBitRate(128_000)
            setAudioChannels(2)

            setOutputFile(file.absolutePath)

            val usingHevc =
                if (useHevcIfAvailable && pipeline != Pipeline.VERY_DARK) trySetHevc(this) else false
            if (!usingHevc) setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            setVideoFrameRate(recordFps)

            val sz = currentSize()
            val encW = (sz.width / 2) * 2
            val encH = (sz.height / 2) * 2
            setVideoSize(encW, encH)

            val bpp = when (pipeline) {
                Pipeline.VERY_DARK -> 0.16
                Pipeline.STD60 -> 0.20
                Pipeline.HFR -> 0.22
            }
            val targetBitrate =
                (encW.toLong() * encH.toLong() * recordFps * bpp).toInt().coerceAtLeast(1_200_000)
            setVideoEncodingBitRate(targetBitrate)

            prepare()
            Log.d(
                TAG,
                "Recorder pipe=$pipeline size=${encW}x${encH} fps=$recordFps vbitrate=$targetBitrate codec=${if (usingHevc) "HEVC" else "H264"}"
            )
        }

        recorderSurface = mediaRecorder!!.surface
        isRecording = true

        // Rebuild a session that INCLUDES the recorder surface
        rebuildSession(withRecorder = true) { e -> onError(e); return@rebuildSession }

        try {
            mediaRecorder?.start()
            onStarted()
        } catch (e: Exception) {
            onError(e)
        }
        outputFile = file
    }

    /** Stop + return original (no re-mux) */
    fun stopRecording(onSaved: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        Log.d(TAG, "Video saved")
        finishRecorder(
            makeOutput = { srcFile -> srcFile },
            onSaved = onSaved,
            onError = onError
        )
    }

    /** Stop + retime to exact playback fps (10..60) — no re-encode; audio scaled to match video; replaces source file */
    fun stopRecordingWithPlaybackFps(
        targetFps: Int,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        finishRecorder(
            makeOutput = { srcFile ->
                retimeToFixedFps(
                    context = context,
                    src = srcFile,
                    targetFps = if (pipeline == Pipeline.VERY_DARK) 15 else targetFps.coerceIn(
                        10,
                        60
                    ),
                    keepAudio = false
                )
            },
            onSaved = onSaved,
            onError = onError
        )
    }

    // Shared stop logic
    private fun finishRecorder(
        makeOutput: (File) -> File,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        var err: Throwable? = null
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            err = e
        }
        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }

        isRecording = false
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null

        val recorded = outputFile
        recorderSurface = null

        if (err != null) {
            rebuildSession(withRecorder = false) {}
            mainHandler.post { onError(err!!) }
            return
        }
        if (recorded == null || !recorded.exists()) {
            rebuildSession(withRecorder = false) {}
            mainHandler.post { onError(IllegalStateException("No output file")) }
            return
        }

        Thread {
            try {
                val out = makeOutput(recorded)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(out.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                mainHandler.post { onSaved(Uri.fromFile(out)) }
            } catch (t: Throwable) {
                Log.e(TAG, "Export failed; returning original", t)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(recorded.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                mainHandler.post { onSaved(Uri.fromFile(recorded)) }
            } finally {
                rebuildSession(withRecorder = false) {}
            }
        }.start()
    }

    private fun trySetHevc(rec: MediaRecorder): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC); true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    // ---- Focus / Zoom / EV ----
    fun enableAutoFocus() {
        focusState = FocusState.AUTO; reapplyRepeating("enableAutoFocus")
    }

    fun setManualFocus(distance: Float) {
        focusState = FocusState.MANUAL; focusDistance = distance; reapplyRepeating("setManualFocus")
    }

    fun setZoomLevel(zoom: Float) {
        val newZ = zoom.coerceIn(1f, maxDigitalZoom.coerceAtMost(5f))
        if (abs(newZ - zoomLevel) < 0.001f) return
        zoomLevel = newZ
        reapplyRepeating("setZoomLevel")
    }

    private fun setCurrentEv(delta: Int) {
        currentEvDelta = clampEv(delta).coerceIn(-6, +6)
        reapplyRepeating("setCurrentEv")
        Log.d(TAG, "EV delta applied: $currentEvDelta")
    }

    // Reapply repeating safely; rebuild session if targets mismatch
    private fun reapplyRepeating(reason: String) {
        val session = captureSession ?: return
        val wantRecorder = isRecording && surfaceUsable(recorderSurface)

        if (!surfaceUsable(previewSurface)) {
            Log.w(TAG, "Preview surface invalid → rebuilding session ($reason)")
            rebuildSession(withRecorder = wantRecorder) {}
            return
        }

        if (wantRecorder != sessionHasRecorder ||
            !sessionContains(previewSurface) ||
            (wantRecorder && !sessionContains(recorderSurface))
        ) {
            Log.d(
                TAG,
                "Session targets mismatch → rebuilding session ($reason) wantRecorder=$wantRecorder hasRecorder=$sessionHasRecorder"
            )
            rebuildSession(withRecorder = wantRecorder) {}
            return
        }

        try {
            when (pipeline) {
                Pipeline.HFR -> {
                    val hs = session as? CameraConstrainedHighSpeedCaptureSession ?: run {
                        rebuildSession(withRecorder = wantRecorder) {}; return
                    }
                    val template =
                        if (wantRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    val b = buildHfrRequest(template, wantRecorder)
                    val burst = hs.createHighSpeedRequestList(b.build())
                    hs.setRepeatingBurst(burst, captureCallback, camHandler)
                }

                Pipeline.STD60, Pipeline.VERY_DARK -> {
                    val fps = when (pipeline) {
                        Pipeline.STD60 -> pickStd60FpsRange() ?: Range(60, 60)
                        Pipeline.VERY_DARK -> Range(60, 60)
                        else -> Range(60, 60)
                    }
                    val template =
                        if (wantRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    val b = buildStdRequest(template, wantRecorder, fps)
                    session.setRepeatingRequest(b.build(), captureCallback, camHandler)
                }
            }
            Log.d(
                TAG,
                "Reapplied ($reason) isRecording=$isRecording zoom=$zoomLevel focus=$focusState"
            )
        } catch (iae: IllegalArgumentException) {
            Log.w(TAG, "setRepeating failed (unconfigured surface) → rebuilding…", iae)
            rebuildSession(withRecorder = wantRecorder) {}
        } catch (e: Exception) {
            Log.e(TAG, "reapplyRepeating failed ($reason)", e)
        }
    }

    // ---- Auto callbacks ----
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val frameDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)

            // Optional auto-torch (in HFR)
            if (autoTorchEnabled && pipeline == Pipeline.HFR) {
                val budget = (1_000_000_000.0 / (getCurrentAeFpsRange()?.upper
                    ?: targetFpsForBudget)).toLong().coerceAtLeast(1)
                val fracTorch =
                    if (expNs != null && budget > 0) expNs.toDouble() / budget.toDouble() else 0.0
                val needTorch = iso != null && fracTorch > 0.80 && iso >= 1200
                if (needTorch != torchOn) {
                    torchOn = needTorch
                    setTorch(torchOn)
                    reapplyRepeating("autoTorch=$torchOn")
                }
            }

            if (autoEnvironmentMode) handleEnvironmentHeuristics(result)

            if (pipeline != Pipeline.HFR) return
            if (!autoEvEnabled) return
            if (expNs == null) return

            val now = System.nanoTime()
            if (now - lastAdjustNs < adjustCooldownNs) return

            val frameTimeNs = frameDurNs ?: (1_000_000_000.0 / targetFpsForBudget).toLong()
            if (frameTimeNs <= 0) return
            val ratio = expNs.toDouble() / frameTimeNs.toDouble()
            val sensitivity = iso ?: return

            val tooBright = ratio < brightThresh && sensitivity <= isoNearMin
            val tooDark = ratio > darkThresh && sensitivity >= isoHigh

            if (ratio > 0.9 && sensitivity >= 1600) onTooDark?.invoke()
            if (!tooBright && !tooDark) return

            val delta =
                (if (tooBright) -evStep else +evStep) + if (envState == Env.INDOOR) +1 else 0
            val newEv = (currentEvDelta + delta).coerceIn(-maxAutoEvDelta, +maxAutoEvDelta)
            if (newEv == currentEvDelta) return

            lastAdjustNs = now
            setCurrentEv(newEv)
            Log.d(TAG, "AutoEV -> EV delta=$newEv (base=$indoorHfrBaseEv)")
        }
    }

    // -------- Environment selection via EV100 average with hysteresis --------
    private fun handleEnvironmentHeuristics(result: TotalCaptureResult) {
        if (autoSwitching || isFirstTime) {
            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return

            val tSec = expNs.toDouble() / 1_000_000_000.0
            val evInstant = if (tSec > 0 && iso > 0) {
                log2((lensAperture.toDouble().pow(2.0) / tSec) * (100.0 / iso.toDouble()))
            } else 0.0

            pushEv(evInstant)
            val evAvg = avgEv()
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: -1
            Log.d(
                TAG,
                "EnvEV: EVinst=${"%.2f".format(evInstant)} EVavg=${"%.2f".format(evAvg)} iso=$iso ae=$aeState env=$envState pipe=$pipeline"
            )

            when (envState) {
                Env.OUTDOOR -> {
                    if (evAvg < VERYDARK_ENTER && !isRecording) {
                        dwellToVeryDark++; dwellToIndoor = 0
                        if (dwellToVeryDark >= dwellConfirm || isFirstTime) {
                            isFirstTime = false
                            smoothSwitchTo(Pipeline.VERY_DARK, withRecorder = false)
                            envState = Env.VERY_DARK; dwellToVeryDark = 0
                            logMode("EV switch OUTDOOR→VERY_DARK")
                        }
                    } else if (evAvg < OUTDOOR_EXIT && !isRecording) {
                        dwellToIndoor++; dwellToVeryDark = 0
                        if (dwellToIndoor >= dwellConfirm || isFirstTime) {
                            isFirstTime = false
                            smoothSwitchTo(Pipeline.STD60, withRecorder = isRecording)
                            envState = Env.INDOOR; dwellToIndoor = 0
                            logMode("EV switch OUTDOOR→INDOOR")
                        }
                    } else {
                        dwellToIndoor = 0; dwellToVeryDark = 0
                    }
                }

                Env.INDOOR -> {
                    if (evAvg >= OUTDOOR_ENTER && !isRecording) {
                        dwellToOutdoor++
                        if (dwellToOutdoor >= dwellConfirm || isFirstTime) {
                            isFirstTime = false
                            smoothSwitchTo(Pipeline.HFR, withRecorder = isRecording)
                            envState = Env.OUTDOOR; dwellToOutdoor = 0
                            logMode("EV switch INDOOR→OUTDOOR"); return
                        }
                    } else dwellToOutdoor = 0

                    if (evAvg <= VERYDARK_ENTER && !isRecording) {
                        dwellToVeryDark++
                        if (dwellToVeryDark >= dwellConfirm || isFirstTime) {
                            isFirstTime = false
                            smoothSwitchTo(Pipeline.VERY_DARK, withRecorder = false)
                            envState = Env.VERY_DARK; dwellToVeryDark = 0
                            logMode("EV switch INDOOR→VERY_DARK")
                        }
                    } else dwellToVeryDark = 0
                }

                Env.VERY_DARK -> {
                    if (evAvg > VERYDARK_EXIT && !isRecording) {
                        dwellToIndoor++
                        if (dwellToIndoor >= dwellConfirm || isFirstTime) {
                            isFirstTime = false
                            smoothSwitchTo(Pipeline.STD60, withRecorder = isRecording)
                            envState = Env.INDOOR; dwellToIndoor = 0
                            logMode("EV switch VERY_DARK→INDOOR")
                        }
                    } else dwellToIndoor = 0
                }
            }
        }
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    // ---- Torch ----
    fun setTorch(enabled: Boolean) {
        if (!autoTorchEnabled) return
        try {
            val camId = option?.cameraId ?: return
            val chars = manager?.getCameraCharacteristics(camId) ?: return
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) manager?.setTorchMode(camId, enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Torch control failed", e)
        }
    }

    // ---- Utilities ----
    private fun closeSessionSync(timeoutMs: Long = 1000) {
        val s = captureSession ?: return
        sessionClosedLatch = CountDownLatch(1)
        runCatching { s.stopRepeating() }
        runCatching { s.abortCaptures() }
        runCatching { s.close() }
        captureSession = null
        sessionTargets.clear()
        sessionHasRecorder = false
        try {
            sessionClosedLatch?.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        sessionClosedLatch = null
    }

    private fun createOutputFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "MySlowMoVideos"
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "SLOWMO_$ts.mp4")
    }

    private fun logRecorderSizesOnce() {
        try {
            val camId = option?.cameraId ?: return
            val chars = manager?.getCameraCharacteristics(camId) ?: return
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(MediaRecorder::class.java)
                ?.joinToString { "${it.width}x${it.height}" }
            Log.d(TAG, "MediaRecorder supported sizes: $sizes")
        } catch (_: Exception) {
        }
    }

    private fun logMode(why: String) {
        val ae = getCurrentAeFpsRange()
        val sz = currentSize()
        val baseEv = if (envState == Env.OUTDOOR) -2 else indoorHfrBaseEv
        val eff = clampEv(baseEv + currentEvDelta)
        Log.d(
            TAG,
            "Mode[$why]: env=$envState • pipe=$pipeline • wantFixedFps=${desiredFixedFps()} • range=${ae ?: "?"} • size=${sz.width}x${sz.height} • EV(base=$baseEv, delta=$currentEvDelta, eff=$eff) • AB=$currentAntibanding"
        )
    }

    // ---- Release (full cleanup) ----
    fun release() {
        ready = false
        try {
            closeSessionSync()
        } catch (_: Exception) {
        }
        cameraDevice?.let { dev ->
            deviceClosedLatch = CountDownLatch(1)
            runCatching { dev.close() }
            cameraDevice = null
            try {
                deviceClosedLatch?.await(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            deviceClosedLatch = null
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        previewSurface = null
        recorderSurface = null
        isRecording = false
        sessionTargets.clear()
        sessionHasRecorder = false
        stop()
    }
}
