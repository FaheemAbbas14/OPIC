package com.opic3d.Spatial.trendingvideos.controllers

// ===== GL imports for SBS compositor =====
import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.EGLSurface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.camera.view.PreviewView
import androidx.core.animation.addListener
import com.opic3d.Spatial.trendingvideos.controllers.gl.SbsGlComposer
import com.opic3d.Spatial.trendingvideos.helper.retieToFixedFps
import com.opic3d.Spatial.trendingvideos.model.SlowMoOption
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.use

class Camera2Controller(
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

    // Session target bookkeeping
    private var sessionTargets: MutableSet<Surface> = mutableSetOf()
    private var sessionHasRecorder: Boolean = false

    // Surfaces
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private val sbsSize: Size = Size(1920, 1080)
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
    private enum class Pipeline { HFR, STD60, VERY_DARK, TIMELAPSE, SBS3D }
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

    // Time-lapse
    private var timelapseCaptureFps: Double = 2.0
    private var timelapsePlaybackFps: Int = 60

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
        evBuf[evBufIdx] = ev; evBufIdx = (evBufIdx + 1) % evBuf.size
        if (evBufCount < evBuf.size) evBufCount++
    }

    private fun avgEv(): Double =
        if (evBufCount == 0) 0.0 else (0 until evBufCount).sumOf { evBuf[it] } / evBufCount.toDouble()

    // Hysteresis thresholds
    private val OUTDOOR_ENTER = 12.0
    private val OUTDOOR_EXIT = 10.5
    private val VERYDARK_ENTER = 6.0
    private val VERYDARK_EXIT = 7.0

    // Dwell counters
    private var dwellToOutdoor = 0
    private var dwellToIndoor = 0
    private var dwellToVeryDark = 0
    private val dwellConfirm = 3
    private var isFirstTime = true

    // Size selection
    private var autoSelectBestHfrSize = true
    private var preferAspectFromOption = false
    private var allowDownscaleForHfr = true
    private var activeSize: Size? = null
    private fun currentSize(): Size = activeSize ?: option?.size ?: Size(1280, 720)

    // HS fps range we forced
    private var forcedHsRange: Range<Int>? = null

    // Bitrate / codec
    private var useHevcIfAvailable = true

    // Focus/zoom
    private enum class FocusState { AUTO, MANUAL }

    private var focusState: FocusState = FocusState.AUTO
    private var focusDistance: Float = 0.08f
    private var zoomLevel: Float = 1.2f
    private var maxDigitalZoom: Float = 5f
    private var activeArrayRect: Rect? = null

    // Indoor/Very Dark brightness policy
    private var indoorHfrBaseEv = +6
    private var maxAutoEvDelta = +6
    private var autoTorchEnabled = false // keep false per your request
    private var torchOn = false
    private var tonemapBoostActive = false
    private var wantTonemapBoost = true
    private var allowPostRawBoost = true
    private var postRawBoostValue = 200

    private var preferHfrOutdoors: Boolean = true

    // Surface for live SBS preview (same as PreviewView’s internal TextureView)
    private var sbsPreviewSurface: Surface? = null
    private var previewEglSurface: EGLSurface? = null

    // Smooth switch overlay
    private var freezeDrawable: Drawable? = null
    private var isSwitching = false
    private var autoSwitching = true
    private var lastRecordFps: Int = 0

    // UI hook
    var onTooDark: (() -> Unit)? = null

    // 🔹 NEW mirror surfaces to keep live preview during recording
    private var mirrorSurface: Surface? = null
    private var mirrorTexture: SurfaceTexture? = null

    // Debug helpers
    private fun clampEv(ev: Int): Int = aeCompRange?.let { ev.coerceIn(it.lower, it.upper) } ?: ev
    private fun aeUpper(): Int = aeCompRange?.upper ?: 6
    private fun surfaceUsable(s: Surface?): Boolean = s != null && s.isValid
    private fun sessionContains(surface: Surface?): Boolean =
        surface != null && sessionTargets.contains(surface)

    // ======= NEW: error code mapping =======
    private fun explainCamError(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
        else -> "ERROR_UNKNOWN"
    }

    // ======= Public API unchanged knobs =======
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
        indoorHfrBaseEv = clampEv(evSteps); this.maxAutoEvDelta = maxAutoDelta.coerceIn(0, 6)
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
    }

    fun setAutoExposureBias(enabled: Boolean) {
        autoEvEnabled = enabled
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
    fun bind(opt: SlowMoOption, isSbs3D: Boolean, onError: (Throwable) -> Unit = {}) {
        start()
        ready = false
        option = opt

        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager?.cameraIdList?.forEachIndexed { i, id ->
                Log.d(TAG, "Camera[$i] ID=$id")
            }
            val camId = opt.cameraId
            if (camId.isBlank()) {
                onError(IllegalArgumentException("bind(): SlowMoOption.cameraId is blank"))
                return
            }
            try {
                val chars = manager?.getCameraCharacteristics(camId)

                if (chars != null) {
                    aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    activeArrayRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    maxDigitalZoom =
                        chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                    zoomLevel = zoomLevel.coerceIn(1f, maxDigitalZoom.coerceAtMost(5f))

                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    if (apertures != null && apertures.isNotEmpty()) lensAperture =
                        apertures.minOrNull() ?: apertures[0]
                    Log.d(TAG, "Aperture set for EV100: f/$lensAperture")

                    val wantFps = opt.fpsRange.upper
                    val hsPick =
                        if (autoSelectBestHfrSize) pickMaxHsSizeForFps(chars, wantFps) else null
                    if (hsPick != null) {
                        activeSize = hsPick.first
                        forcedHsRange = hsPick.second
                        Log.d(
                            TAG,
                            "Auto HFR pick for ${wantFps}fps: ${activeSize!!.width}x${activeSize!!.height}, range=$forcedHsRange"
                        )
                    } else {
                        activeSize = opt.size
                        forcedHsRange = null
                        Log.w(
                            TAG,
                            "No HS size advertises $wantFps fps; falling back to option size ${opt.size.width}x${opt.size.height}"
                        )
                    }
                    dumpHighSpeedTable(chars)
                } else {
                    activeSize = opt.size
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            currentEvDelta = 0
            targetFpsForBudget =
                max(getSupportedHighSpeedRange(opt)?.upper ?: 0, opt.fpsRange.upper).coerceAtLeast(
                    60
                )

            previewSurface = waitForPreviewSurface(currentSize(), 1200)
                ?: return onError(IllegalStateException("Preview surface not ready"))

            manager?.openCamera(opt.cameraId, object : CameraDevice.StateCallback() {
                @RequiresApi(Build.VERSION_CODES.P)
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    logRecorderSizesOnce()

                    // If SBS3D requested at bind, force pipeline to SBS and stop here:
                    if (isSbs3D) {
                        pipeline = Pipeline.SBS3D
                        ready = false

                        mainHandler.post {
                            startSbs3dPreview(
                                onReady = {
                                    ready = true
                                    logMode("SBS3D preview active at bind()")
                                },
                                onError = { err ->
                                    // Toast.makeText(context,"OPIC 3D not supported", Toast.LENGTH_SHORT).show()
                                    Log.e(TAG, "SBS3D preview failed: ${err.message}", err)
                                }
                            )
                        }
                        return
                    }


                    if (getSupportedHighSpeedRange(opt) != null) {
                        pipeline = Pipeline.HFR
                        rebuildSession(withRecorder = false) { e ->
                            Log.e(TAG, "HFR preview failed", e); onError(e)
                        }
                    } else {
                        pipeline = Pipeline.STD60
                        rebuildSession(withRecorder = false) { e ->
                            Log.e(TAG, "STD preview failed", e); onError(e)
                        }
                    }
                    logMode("onOpened")
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close(); cameraDevice = null; ready = false
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val why = explainCamError(error)
                    device.close(); cameraDevice = null; ready = false
                    onError(RuntimeException("Camera2 error $error ($why)"))
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
    private fun desiredFixedFps(): Int = option?.fpsRange?.upper ?: 120

    private fun pickMaxHsSizeForFps(
        chars: CameraCharacteristics,
        wantFps: Int
    ): Pair<Size, Range<Int>>? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val hsSizes = try {
            map.highSpeedVideoSizes
        } catch (_: Throwable) {
            null
        } ?: return null

        data class Cand(val size: Size, val range: Range<Int>)

        val exactFixed = mutableListOf<Cand>()
        val includeWant = mutableListOf<Cand>()
        for (sz in hsSizes) {
            val ranges = map.getHighSpeedVideoFpsRangesFor(sz) ?: continue
            ranges.firstOrNull { it.lower == wantFps && it.upper == wantFps }
                ?.let { exactFixed += Cand(sz, it) }
            ranges.firstOrNull { it.lower >= 120 && it.upper >= 120 && it.lower <= wantFps && it.upper >= wantFps }
                ?.let { includeWant += Cand(sz, it) }
        }
        fun area(s: Size) = s.width.toLong() * s.height.toLong()
        fun pickLargest(list: List<Cand>) = list.maxByOrNull { area(it.size) }
        val best = pickLargest(exactFixed) ?: pickLargest(includeWant) ?: return null
        return best.size to best.range
    }

    fun check3DSupport(): Boolean {
        val cm = manager ?: return false
        val logical = findLogicalBackWithTwoPhysicals(cm)
            ?: return false
        return true
    }

    @RequiresApi(28)
    private fun startSbs3dPreview(
        size: Size = Size(1280, 720),
        fps: Range<Int> = Range(30, 30),
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val cm = manager ?: return onError(IllegalStateException("CameraManager not ready"))
        val logical = findLogicalBackWithTwoPhysicals(cm)
            ?: return onError(IllegalStateException("No logical back camera with 2 physicals found"))

        val (logicalId, physicals) = logical
        val leftId = physicals[0]
        val rightId = physicals.getOrNull(1)
            ?: return onError(IllegalStateException("Need 2 physical cameras"))

        val previewSurface = findPreviewSurface()
        if (previewSurface == null) return onError(IllegalStateException("Preview surface not ready"))

        // ✅ Force landscape buffer for preview EGL surface
        val landscapeW = size.width * 2
        val landscapeH = size.height
        val outW = (sbsSize.width * 2 / 2) * 2
        val outH = (sbsSize.height / 2) * 2

// Create an offscreen buffer for horizontal SBS rendering
        val eglOffscreen = SurfaceTexture(999).apply {
            setDefaultBufferSize(landscapeW, landscapeH)
        }
        val eglSurface = Surface(eglOffscreen)

// ✅ Render horizontally to eglSurface and mirror to PreviewView
        sbsComposer = SbsGlComposer(
            outW = landscapeW,
            outH = landscapeH,
            outSurface = eglSurface,      // EGL surface for correct SBS geometry
            eyeSize = size,
            previewSurface = previewSurface   // still show live preview
        )

        sbsLeftSurface = sbsComposer!!.leftSurface
        sbsRightSurface = sbsComposer!!.rightSurface

        sbsLogicalId = logicalId
        sbsLeftPhysicalId = leftId
        sbsRightPhysicalId = rightId
        sbsUsingLogical = true

        val stateCb = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                val outLeft =
                    OutputConfiguration(sbsLeftSurface!!).apply { setPhysicalCameraId(leftId) }
                val outRight =
                    OutputConfiguration(sbsRightSurface!!).apply { setPhysicalCameraId(rightId) }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outLeft, outRight),
                    { r -> (camHandler ?: mainHandler).post(r) },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sbsSession = session
                            try {
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply {
                                        addTarget(sbsLeftSurface!!)
                                        addTarget(sbsRightSurface!!)
                                        set(
                                            CaptureRequest.CONTROL_MODE,
                                            CaptureRequest.CONTROL_MODE_AUTO
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AWB_MODE,
                                            CaptureRequest.CONTROL_AWB_MODE_AUTO
                                        )
                                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                                    }
                                session.setRepeatingRequest(req.build(), null, camHandler)
                                sbsComposer?.start()
                                onReady()
                            } catch (t: Throwable) {
                                onError(t)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            onError(RuntimeException("SBS3D preview configure failed"))
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                onError(RuntimeException("SBS3D preview: device disconnected"))
            }

            override fun onError(device: CameraDevice, error: Int) {
                val why = explainCamError(error)
                onError(RuntimeException("SBS3D preview open failed: $why ($error)"))
            }
        }

        manager?.openCamera(logicalId, stateCb, camHandler)
    }

    private fun aspect(s: Size) = s.width.toDouble() / s.height.toDouble()

    // --- replace old waitForPreviewSurface() ---
    private fun waitForPreviewSurface(size: Size, timeoutMs: Long): Surface? {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        } catch (_: Throwable) {
        }

        // 🔹 Try to use internal TextureView inside PreviewView
        while (System.nanoTime() < end) {
            for (i in 0 until previewView.childCount) {
                val v = previewView.getChildAt(i)
                if (v is TextureView && v.isAvailable && v.surfaceTexture != null) {
                    v.surfaceTexture!!.setDefaultBufferSize(size.width, size.height)
                    Log.d(TAG, "Using internal TextureView surface from PreviewView")
                    return Surface(v.surfaceTexture)
                }
            }
            Thread.sleep(20)
        }

        // 🔹 Fallback: create dummy TextureView surface if PreviewView never exposes one
        val dummy = SurfaceTexture(11)
        dummy.setDefaultBufferSize(size.width, size.height)
        Log.w(TAG, "PreviewView had no TextureView — using dummy SurfaceTexture")
        return Surface(dummy)
    }

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
        Pipeline.HFR -> forcedHsRange ?: option?.let { getSupportedHighSpeedRange(it) }
        Pipeline.STD60, Pipeline.VERY_DARK, Pipeline.TIMELAPSE, Pipeline.SBS3D -> pickStd60FpsRange()
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

    private fun makeGammaCurve(g: Float = 0.45f): TonemapCurve {
        fun p(x: Float) = (x.toDouble().pow(1.0 / g)).toFloat().coerceIn(0f, 1f)
        val steps = 16
        val rgb = FloatArray(steps * 2)
        for (i in 0 until steps) {
            val x = i / (steps - 1f);
            val y = p(x); rgb[2 * i] = x; rgb[2 * i + 1] = y
        }
        return TonemapCurve(rgb, rgb, rgb)
    }

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

        val r = getCurrentAeFpsRange()
        if (r != null && r.lower == r.upper) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)

        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        if (wantTonemapBoost && tonemapBoostActive) {
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, makeGammaCurve(0.45f))
        } else {
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        }

        if (allowPostRawBoost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                b.set(
                    CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                    postRawBoostValue
                )
            }
        }
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (supportsVideoStab()) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF) // torch disabled
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
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (supportsVideoStab()) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        return b
    }

    private fun rebuildSession(withRecorder: Boolean, onError: (Throwable) -> Unit = {}) {
        // guard SBS: its session is managed separately
        if (pipeline == Pipeline.SBS3D) return

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
        if (needRecorder) {
            mirrorSurface?.let { outputs.add(it) }  // 🔹 add mirror surface to keep preview alive
            recorderSurface?.let { outputs.add(it) }
        }

        closeSessionSync()

        try {
            if (pipeline == Pipeline.HFR) {
                val template =
                    if (withRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                cameraDevice!!.createConstrainedHighSpeedCaptureSession(
                    outputs,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            sessionTargets.clear(); sessionTargets.addAll(outputs)
                            sessionHasRecorder = needRecorder
                            try {
                                val template =
                                    if (useTemplateRecordForPreview && needRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                                val b = buildHfrRequest(template, wantRecorder = needRecorder)
// 🔹 Always attach preview surface even when recording
                                previewSurface?.let {
                                    if (!sessionTargets.contains(it)) b.addTarget(
                                        it
                                    )
                                }

// 🔹 Make sure both surfaces are in the target list
                                previewSurface?.let { if (surfaceUsable(it)) b.addTarget(it) }
                                recorderSurface?.let { if (surfaceUsable(it)) b.addTarget(it) }

                                val hs = session as CameraConstrainedHighSpeedCaptureSession
                                val burst = hs.createHighSpeedRequestList(b.build())
                                hs.setRepeatingBurst(burst, captureCallback, camHandler)

                                ready = true
                                Log.d(
                                    TAG,
                                    "Preview HFR; range=${getCurrentAeFpsRange()} size=${currentSize().width}x${currentSize().height}"
                                )
                                Log.d(
                                    TAG,
                                    "Preview surface valid=${previewSurface?.isValid} attached=${
                                        sessionContains(previewSurface)
                                    }"
                                )

                                onNewSessionConfigured(); logMode("Preview HFR")
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
                cameraDevice!!.createCaptureSession(
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
                                    Pipeline.TIMELAPSE -> pickStd60FpsRange() ?: Range(60, 60)
                                    else -> Range(60, 60)
                                }
                                val template =
                                    if (needRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                                val b = buildStdRequest(template, needRecorder, fps)

// 🔹 Make sure both surfaces are in the target list
                                previewSurface?.let { if (surfaceUsable(it)) b.addTarget(it) }
                                recorderSurface?.let { if (surfaceUsable(it)) b.addTarget(it) }

                                session.setRepeatingRequest(b.build(), captureCallback, camHandler)

                                ready = true
                                Log.d(
                                    TAG,
                                    "Preview ${pipeline.name}; range=$fps size=${currentSize().width}x${currentSize().height}"
                                )
                                onNewSessionConfigured(); logMode("Preview ${pipeline.name}")
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

    private fun onNewSessionConfigured() {
        if (!isSwitching) return
        mainHandler.post {
            freezeDrawable?.let { drawable ->
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

    private fun smoothSwitchTo(newPipe: Pipeline, withRecorder: Boolean) {
        if (pipeline == newPipe || isSwitching) return
        isSwitching = true

        val snapshot = capturePreviewBitmapSafely()
        if (snapshot != null) {
            val drawable = BitmapDrawable(previewView.resources, snapshot).apply { alpha = 255 }
            freezeDrawable = drawable
            mainHandler.post { previewView.overlay.add(drawable) }
        } else freezeDrawable = null

        pipeline = newPipe
        rebuildSession(withRecorder = withRecorder) { e ->
            Log.e(TAG, "rebuild during smoothSwitch failed", e)
            mainHandler.post {
                freezeDrawable?.let { previewView.overlay.remove(it) }
                freezeDrawable = null
                isSwitching = false
            }
        }
    }

    private fun capturePreviewBitmapSafely(): Bitmap? {
        for (i in 0 until previewView.childCount) {
            val v = previewView.getChildAt(i)
            if (v is TextureView && v.isAvailable) return runCatching { v.bitmap }.getOrNull()
        }
        return null
    }

    // ---- RECORD (Slow-mo / Standard) ----
    fun startRecording(
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (pipeline == Pipeline.SBS3D) return onError(IllegalStateException("Use start3DRecordingSbs for SBS"))

        val dev = cameraDevice ?: return onError(IllegalStateException("Camera not ready"))
        val file = createOutputFile(context, "SBS3D_Video", ".mp4")

        val recordFps = when (pipeline) {
            Pipeline.HFR -> (forcedHsRange?.upper ?: getCurrentAeFpsRange()?.upper)
                ?: desiredFixedFps()

            Pipeline.STD60 -> pickStd60FpsRange()?.upper ?: 60
            Pipeline.VERY_DARK -> 60
            Pipeline.TIMELAPSE -> 60
            Pipeline.SBS3D -> 30
        }
        lastRecordFps = recordFps
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
            val targetBitrate = computeTargetBitrate(encW, encH, recordFps, usingHevc, pipeline)
            setVideoEncodingBitRate(targetBitrate)
            prepare()
            Log.d(
                TAG,
                "Recorder pipe=$pipeline size=${encW}x${encH} fps=$recordFps vbitrate=$targetBitrate codec=${if (usingHevc) "HEVC" else "H264"}"
            )
        }

        recorderSurface = mediaRecorder!!.surface

// 🔹 Create a mirror texture for live preview (fix preview freeze)
        mirrorTexture = SurfaceTexture(66).apply {
            setDefaultBufferSize(currentSize().width, currentSize().height)
        }
        mirrorSurface = Surface(mirrorTexture)

        isRecording = true
        useTemplateRecordForPreview = true

// 🔹 Explicitly rebuild session with TEMPLATE_RECORD and both surfaces
        rebuildSession(withRecorder = true) { e -> onError(e); return@rebuildSession }
        camHandler?.postDelayed({
            try {
                val dev = cameraDevice ?: return@postDelayed
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    previewSurface?.let { addTarget(it) }
                    recorderSurface?.let { addTarget(it) }
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                }
                captureSession?.setRepeatingRequest(req.build(), captureCallback, camHandler)
                Log.d(TAG, "Recording request set → preview+recorder active")
            } catch (t: Throwable) {
                Log.e(TAG, "recording request reapply failed", t)
            }
        }, 300)

        try {
            mediaRecorder?.start(); onStarted()
        } catch (e: Exception) {
            onError(e)
        }
        outputFile = file
    }

    fun stopRecordingWithPlaybackFps(
        keepAudio: Boolean,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val desiredFps = if (lastRecordFps == 60) 15 else 30
        finishRecorder(
            makeOutput = { srcFile ->
                retieToFixedFps(
                    src = srcFile,
                    targetFps = desiredFps,
                    keepAudio = keepAudio
                )
            },
            onSaved = onSaved,
            onError = onError
        )
    }

    // ---- TIME-LAPSE ----
    fun startTimeLapse(
        captureFps: Double = 2.0,
        playbackFps: Int = 30,
        onStarted: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (pipeline == Pipeline.SBS3D) return onError(IllegalStateException("Stop SBS before timelapse"))
        val dev = cameraDevice ?: return onError(IllegalStateException("Camera not ready"))
        if (captureFps <= 0.0 || playbackFps <= 0) return onError(IllegalArgumentException("Invalid fps"))
        timelapseCaptureFps = captureFps.coerceIn(0.5, playbackFps.toDouble())
        timelapsePlaybackFps = playbackFps
        if (pipeline == Pipeline.HFR) smoothSwitchTo(Pipeline.STD60, withRecorder = false)
        pipeline = Pipeline.TIMELAPSE

        val file = createOutputFile(context, "TimeLapse", ".mp4")
        lastRecordFps = playbackFps

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
                trySetHevc(this); if (!usingHevc) setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoFrameRate(timelapsePlaybackFps)
            try {
                setCaptureRate(timelapseCaptureFps)
            } catch (e: Throwable) {
                Log.w(TAG, "setCaptureRate not supported; continuing without native TL", e)
            }
            val sz = currentSize()
            val encW = (sz.width / 2) * 2
            val encH = (sz.height / 2) * 2
            setVideoSize(encW, encH)
            val targetBitrate =
                computeTargetBitrate(encW, encH, timelapsePlaybackFps, usingHevc, Pipeline.STD60)
            setVideoEncodingBitRate(targetBitrate)
            prepare()
            Log.d(
                TAG,
                "Recorder TL size=${encW}x${encH} capture=${"%.3f".format(timelapseCaptureFps)} play=$timelapsePlaybackFps"
            )
        }

        recorderSurface = mediaRecorder!!.surface
        isRecording = true
        rebuildSession(withRecorder = true) { e -> onError(e); return@rebuildSession }
        try {
            mediaRecorder?.start(); onStarted()
        } catch (e: Exception) {
            onError(e)
        }
        outputFile = file
    }

    fun stopTimeLapse(
        normalizePlaybackFps: Boolean = false,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        finishRecorder(
            makeOutput = { srcFile ->
                if (!normalizePlaybackFps) srcFile else retieToFixedFps(
                    src = srcFile,
                    targetFps = timelapsePlaybackFps,
                    keepAudio = true
                )
            },
            onSaved = { uri ->
                mainHandler.post {
                    pipeline = Pipeline.STD60; logMode("stopTimeLapse")
                }; onSaved(uri)
            },
            onError = onError
        )
    }

    fun startTimeLapseX(
        multiplier: Double,
        playbackFps: Int = 30,
        onStarted: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (multiplier <= 0.0) return onError(IllegalArgumentException("Multiplier must be > 0"))
        val rawCapture = playbackFps / multiplier
        val captureFps = rawCapture.coerceIn(0.5, playbackFps.toDouble())
        startTimeLapse(captureFps, playbackFps, onStarted, onError)
    }

    fun parseSpeedMultiplier(spec: String): Double? {
        val s = spec.trim().lowercase(Locale.US)
        val number = s.removeSuffix("x").toDoubleOrNull()
        return number?.takeIf { it > 0.0 }
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
        // 🔹 Rebuild preview-only session so user sees live feed again
        mainHandler.postDelayed({
            rebuildSession(withRecorder = false) {}
        }, 300)

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null

        val recorded = outputFile
        recorderSurface = null
// 🔹 Cleanup mirror surfaces
        try {
            mirrorSurface?.release()
        } catch (_: Throwable) {
        }
        try {
            mirrorTexture?.release()
        } catch (_: Throwable) {
        }
        mirrorSurface = null
        mirrorTexture = null

        if (err != null) {
            rebuildSession(withRecorder = false) {}
            mainHandler.post { onError(err!!) }; return
        }
        if (recorded == null || !recorded.exists()) {
            rebuildSession(withRecorder = false) {}
            mainHandler.post { onError(IllegalStateException("No output file")) }; return
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

    private fun trySetHevc(rec: MediaRecorder): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC); true
        } else false
    } catch (_: Exception) {
        false
    }

    private fun computeTargetBitrate(
        w: Int, h: Int, fps: Int, usingHevc: Boolean, pipe: Pipeline
    ): Int {
        val baseBppf = when (pipe) {
            Pipeline.HFR -> 0.28
            Pipeline.STD60 -> 0.22
            Pipeline.VERY_DARK -> 0.18
            Pipeline.TIMELAPSE -> 0.20
            Pipeline.SBS3D -> 0.24
        }
        val bppf = if (usingHevc) baseBppf * 0.6 else baseBppf
        val est = (w.toLong() * h.toLong() * fps * bppf).toLong()
        val min = if (usingHevc) 6_000_000L else 10_000_000L
        val maxB = 80_000_000L
        return est.coerceIn(min, maxB).toInt()
    }

    fun enableAutoFocus() {
        focusState = FocusState.AUTO; reapplyRepeating("enableAutoFocus")
    }

    fun setManualFocus(distance: Float) {
        focusState = FocusState.MANUAL; focusDistance = distance; reapplyRepeating("setManualFocus")
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setZoomLevel(zoom: Float) {
        val newZ = zoom.coerceIn(1f, maxDigitalZoom.coerceAtMost(5f))
        if (abs(newZ - zoomLevel) < 0.001f) return
        zoomLevel = newZ

        if (pipeline == Pipeline.SBS3D) {
            // 🔹 Sync both eyes when in SBS 3D mode
            setSbsZoomLevel(newZ)
        } else {
            reapplyRepeating("setZoomLevel")
        }
    }

    private fun CaptureRequest.Builder.applyPhysicalCrop(physicalId: String?, crop: Rect) {
        // Just apply the same crop region to the whole request.
        // On logical multi-camera devices the HAL will replicate it to both lenses.
        set(CaptureRequest.SCALER_CROP_REGION, crop)
    }

    private fun getActiveArraySize(cameraId: String?): Rect? {
        if (cameraId == null) return null
        return try {
            manager?.getCameraCharacteristics(cameraId)
                ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        } catch (_: Throwable) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setSbsZoomLevel(zoom: Float) {
        // 🔹 Normalize zoom between left and right lenses
        val leftFocal = getFocalLength(sbsLeftPhysicalId)
        val rightFocal = getFocalLength(sbsRightPhysicalId)
        val focalRatio = (leftFocal / rightFocal) * 1.03f // tweak ±3 % if needed


        val newZoom = zoom.coerceIn(1f, maxDigitalZoom.coerceAtMost(5f))
        zoomLevel = newZoom

        // --- Logical multi-camera (API 28+) ---
        if (sbsUsingLogical && cameraDevice != null && sbsSession != null) {
            try {
                val dev = cameraDevice ?: return
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    sbsLeftSurface?.let { addTarget(it) }
                    sbsRightSurface?.let { addTarget(it) }
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    val leftCrop = cropRegionForZoom(newZoom)
                    val rightCrop = cropRegionForZoom(newZoom * focalRatio)
                    applyPhysicalCrop(sbsLeftPhysicalId, leftCrop!!)
                    applyPhysicalCrop(sbsRightPhysicalId, rightCrop!!)

                }
                sbsSession?.setRepeatingRequest(req.build(), null, camHandler)
                Log.d(TAG, "SBS zoom sync applied (logical) → zoom=$newZoom")
            } catch (e: Throwable) {
                Log.e(TAG, "SBS zoom sync failed (logical)", e)
            }
        }

        // --- Concurrent dual-device fallback (API 29+) ---
        if (!sbsUsingLogical && sbsLeftDev != null && sbsRightDev != null) {
            try {
                val leftCrop = cropRegionForZoom(newZoom)
                val rightCrop = cropRegionForZoom(newZoom)
                if (leftCrop != null) {
                    val leftReq =
                        sbsLeftDev!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            sbsLeftSurface?.let { addTarget(it) }
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(
                                CaptureRequest.CONTROL_AWB_MODE,
                                CaptureRequest.CONTROL_AWB_MODE_AUTO
                            )
                            set(CaptureRequest.SCALER_CROP_REGION, leftCrop)
                        }
                    sbsLeftSession?.setRepeatingRequest(leftReq.build(), null, camHandler)
                }
                if (rightCrop != null) {
                    val rightReq =
                        sbsRightDev!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            sbsRightSurface?.let { addTarget(it) }
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(
                                CaptureRequest.CONTROL_AWB_MODE,
                                CaptureRequest.CONTROL_AWB_MODE_AUTO
                            )
                            set(CaptureRequest.SCALER_CROP_REGION, rightCrop)
                        }
                    sbsRightSession?.setRepeatingRequest(rightReq.build(), null, camHandler)
                }
                Log.d(TAG, "SBS zoom sync applied (concurrent) → zoom=$newZoom")
            } catch (e: Throwable) {
                Log.e(TAG, "SBS zoom sync failed (concurrent)", e)
            }
        }
    }

    private fun setCurrentEv(delta: Int) {
        currentEvDelta = clampEv(delta).coerceIn(-6, +6)
        reapplyRepeating("setCurrentEv")
        Log.d(TAG, "EV delta applied: $currentEvDelta")
    }

    fun checkZoomValues(): MutableList<Float> {
        val definedZoomLevels: MutableList<Float> = mutableListOf(5f, 4f, 3f, 2f, 1.2f, 1f)
        val finalize: MutableList<Float> = mutableListOf()
        val camId = option?.cameraId
        if (camId.isNullOrBlank()) {
            Log.e(TAG, "checkZoomValues: invalid cameraId (null or blank)")
            return mutableListOf()
        }
        val characteristics = manager?.getCameraCharacteristics(camId)
            ?: return mutableListOf()

        val activeRect = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val maxZoom =
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val minZoom = 1.0
        Log.d("Camera2", "Max zoom ratio = $maxZoom")
        for (zoom in definedZoomLevels) {
            if (zoom >= minZoom && zoom <= maxZoom) {
                if (!finalize.contains(zoom)) finalize.add(zoom)
            }
        }
        return finalize
    }

    private fun reapplyRepeating(reason: String) {
        if (pipeline == Pipeline.SBS3D) return // SBS session managed separately
        val session = captureSession ?: return
        val wantRecorder = isRecording && surfaceUsable(recorderSurface)

        if (!surfaceUsable(previewSurface)) {
            Log.w(TAG, "Preview surface invalid → rebuilding session ($reason)")
            rebuildSession(withRecorder = wantRecorder) {}
            return
        }

        if (wantRecorder != sessionHasRecorder || !sessionContains(previewSurface) || (wantRecorder && !sessionContains(
                recorderSurface
            ))
        ) {
            Log.d(
                TAG,
                "Session targets mismatch → rebuilding session ($reason) wantRecorder=$wantRecorder hasRecorder=$sessionHasRecorder"
            )
            rebuildSession(withRecorder = wantRecorder) {}; return
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

                Pipeline.STD60, Pipeline.VERY_DARK, Pipeline.TIMELAPSE -> {
                    val fps = when (pipeline) {
                        Pipeline.STD60 -> pickStd60FpsRange() ?: Range(60, 60)
                        Pipeline.VERY_DARK -> Range(60, 60)
                        Pipeline.TIMELAPSE -> pickStd60FpsRange() ?: Range(60, 60)
                        else -> Range(60, 60)
                    }
                    val template =
                        if (wantRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    val b = buildStdRequest(template, wantRecorder, fps)
                    session.setRepeatingRequest(b.build(), captureCallback, camHandler)
                }

                else -> {}
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

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val frameDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)

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

    private fun handleEnvironmentHeuristics(result: TotalCaptureResult) {
        if (pipeline == Pipeline.TIMELAPSE || pipeline == Pipeline.SBS3D) return

        if (autoSwitching || isFirstTime) {
            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val tSec = expNs.toDouble() / 1_000_000_000.0
            val evInstant = if (tSec > 0 && iso > 0) {
                log2((lensAperture.toDouble().pow(2.0) / tSec) * (100.0 / iso.toDouble()))
            } else 0.0

            pushEv(evInstant)
            val evAvg = avgEv()

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

    private fun createMoviesUriViaMediaStore(): Uri? = try {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                "VID_${System.currentTimeMillis()}.mp4"
            )
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/OPIC")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    } catch (_: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun createOutputFile(
        context: Context,
        prefix: String = "SBS3D_Photo",
        extension: String? = null
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val ext = when {
            extension != null -> extension
            prefix.contains("photo", true) || prefix.contains("image", true) -> ".jpg"
            prefix.contains("pic", true) -> ".jpg"
            prefix.contains("video", true) || prefix.contains("record", true) -> ".mp4"
            else -> ".mp4"
        }

        // 1️⃣ Create temp file in internal cache (always works)
        val internalDir = File(context.cacheDir, "opic_temp").apply {
            if (!exists()) mkdirs()
        }

        val tempFile = File(internalDir, "${prefix}_$ts$ext")

        try {
            tempFile.parentFile?.mkdirs()
            tempFile.createNewFile()
        } catch (e: Exception) {
            Log.e("Camera2Controller", "Failed to create internal file", e)
        }

        return tempFile
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

    private fun dumpHighSpeedTable(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val hsSizes = try {
            map.highSpeedVideoSizes
        } catch (_: Throwable) {
            null
        } ?: return
        val sorted = hsSizes.sortedByDescending { it.width.toLong() * it.height.toLong() }
        for (sz in sorted) {
            val ranges = map.getHighSpeedVideoFpsRangesFor(sz)?.joinToString() ?: "[]"
            Log.d(TAG, "HS ${sz.width}x${sz.height} → $ranges")
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

    fun suggestTimeLapseCaptureFps(intervalSeconds: Double): Int {
        val fps = (1.0 / intervalSeconds).coerceIn(0.5, 30.0)
        return fps.toInt().coerceAtLeast(1)
    }

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

        releaseSbs()
        stop()
    }

    // =========================================================================================
    // ============= 3D FULL-SBS: logical multi-camera OR concurrent dual camera ===============
    // =========================================================================================

    // [SBS] GL compositor and its input surfaces
    private var sbsComposer: SbsGlComposer? = null
    private var sbsLeftSurface: Surface? = null
    private var sbsRightSurface: Surface? = null

    // [SBS] state
    private var sbsSession: CameraCaptureSession? = null
    private var sbsLogicalId: String? = null
    private var sbsLeftPhysicalId: String? = null
    private var sbsRightPhysicalId: String? = null
    private var sbsFps: Range<Int> = Range(30, 30)
    private var sbsUsingLogical: Boolean = true

    // concurrent fallback
    private var sbsLeftDev: CameraDevice? = null
    private var sbsRightDev: CameraDevice? = null
    private var sbsLeftSession: CameraCaptureSession? = null
    private var sbsRightSession: CameraCaptureSession? = null

    // ----- helper: find logical back with >=2 physicals
    private fun findLogicalBackWithTwoPhysicals(cm: CameraManager): Pair<String, List<String>>? {
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val physicals = ch.physicalCameraIds?.toList() ?: emptyList()
            val isLogical =
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && isLogical && physicals.size >= 2) {
                return id to physicals
            }
        }
        return null
    }

    // ----- helper: concurrent fallback pairs
    @RequiresApi(29)
    private fun findConcurrentPairBack(cm: CameraManager): Pair<String, String>? {
        val sets: List<Set<String>> =
            cm.concurrentCameraIds?.map { it.toSet() }?.toList() ?: return null

        if (sets.isEmpty()) return null

        fun isBack(id: String): Boolean {
            val ch = cm.getCameraCharacteristics(id)
            return ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        // Prefer back+back if available, else back+front
        sets.forEach { s ->
            val list = s.toList()
            if (list.size >= 2) {
                val backs = list.filter { isBack(it) }
                if (backs.size >= 2) return backs[0] to backs[1]
            }
        }
        sets.forEach { s ->
            val list = s.toList()
            if (list.size >= 2) {
                val back = list.firstOrNull { isBack(it) }
                val other = list.firstOrNull { it != back }
                if (back != null && other != null) return back to other
            }
        }
        return null
    }

    fun canDoSbs3D(): Boolean {
        // logical multi-cam?
        for (id in manager?.cameraIdList!!) {
            val ch = manager?.getCameraCharacteristics(id)
            val caps = ch?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val physicals = ch?.physicalCameraIds
            val isLogical =
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (isLogical && physicals != null && physicals.size >= 2) return true
        }
        // concurrent fallback? (API 29+)
        return if (Build.VERSION.SDK_INT >= 29) {
            val sets: List<Set<String>> =
                manager?.concurrentCameraIds?.map { it.toSet() }?.toList() ?: emptyList()
            sets.any { it.size >= 2 }
        } else false
    }

    private fun findPreviewSurface(): Surface? {
        for (i in 0 until previewView.childCount) {
            val v = previewView.getChildAt(i)
            if (v is TextureView && v.isAvailable && v.surfaceTexture != null) {
                return Surface(v.surfaceTexture)
            }
        }
        return null
    }

    /**
     * Start FULL-SBS recording (two camera streams into one MP4)
     * Prefers logical multi-camera if present. Falls back to concurrent dual-camera on API 29+ if available.
     */
    @RequiresApi(28)
    fun start3DRecordingSbs(
        leftCameraId: String? = null,
        rightCameraId: String? = null,
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 28) {
            onError(UnsupportedOperationException("SBS requires API 28+")); return
        }
        if (isRecording) {
            onError(IllegalStateException("Already recording; stop current session first")); return
        }

        val cm =
            manager ?: run { onError(IllegalStateException("CameraManager not ready")); return }

        // Prepare recorder for double width (full-SBS)
        val file = createOutputFile(context, "SBS3D_Video", ".mp4")
        val outW = (sbsSize.width * 2 / 2) * 2
        val outH = (sbsSize.height / 2) * 2

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
                trySetHevc(this).also { if (!it) setVideoEncoder(MediaRecorder.VideoEncoder.H264) }
            setVideoFrameRate(sbsFps.upper)
            setVideoSize(outW, outH)
            val targetBitrate = computeTargetBitrate(
                outW,
                outH,
                sbsFps.upper,
                usingHevc = usingHevc,
                pipe = Pipeline.SBS3D
            )
            setVideoEncodingBitRate(targetBitrate)
            prepare()
        }
        recorderSurface = mediaRecorder!!.surface
        outputFile = file

        // Create GL compositor
        try {
            // Extract PreviewView surface for live on-screen SBS display
            val previewSurface = findPreviewSurface()
            sbsPreviewSurface = previewSurface
            sbsComposer?.switchOutputSurface(recorderSurface!!)
            // after sbsComposer creation
            val monitorTex = SurfaceTexture(101).apply {
                setDefaultBufferSize(sbsSize.width, sbsSize.height)
            }
            sbsLeftSurface = sbsComposer!!.leftSurface
            sbsRightSurface = sbsComposer!!.rightSurface
        } catch (t: Throwable) {
            onError(RuntimeException("Failed to initialize SBS compositor: ${t.message}", t))
            return
        }

        // Try logical multi-camera first
        val logical = findLogicalBackWithTwoPhysicals(cm)
        if (logical != null) {
            sbsUsingLogical = true
            val (logicalId, physicals) = logical
            val leftId = leftCameraId ?: physicals[0]
            val rightId = rightCameraId ?: physicals.firstOrNull { it != leftId } ?: run {
                onError(IllegalStateException("No second physical camera found under logical $logicalId")); return
            }
            sbsLogicalId = logicalId
            sbsLeftPhysicalId = leftId
            sbsRightPhysicalId = rightId

            // Close any mono device/session
            try {
                closeSessionSync()
            } catch (_: Throwable) {
            }
            cameraDevice?.close(); cameraDevice = null

            val stateCb = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createSbsSessionLogical(device, onStarted, onSaved, onError)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close(); onError(RuntimeException("SBS: logical camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val why = explainCamError(error)
                    device.close(); onError(RuntimeException("SBS logical open failed: $why ($error)"))
                }
            }
            manager?.openCamera(logicalId, stateCb, camHandler)
            return
        }

        // Fallback to concurrent dual-camera (API 29+)
        if (Build.VERSION.SDK_INT >= 29) {
            val pair = findConcurrentPairBack(cm)
            if (pair != null) {
                sbsUsingLogical = false
                val (leftId, rightId) = pair
                openConcurrentDualSessions(
                    leftId, rightId, sbsSize, sbsFps,
                    onStarted = {
                        pipeline = Pipeline.SBS3D
                        isRecording = true
                        sbsComposer?.start()    // start GL pump first
                        mediaRecorder?.start()  // then recorder
                        onStarted()
                    },
                    onError = onError
                )
                return
            }
        }

        onError(IllegalStateException("Device has no logical or concurrent multi-camera capable of SBS on this device."))
    }

    @RequiresApi(28)
    private fun createSbsSessionLogical(
        dev: CameraDevice,
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val leftIn = sbsLeftSurface ?: return onError(IllegalStateException("No left surface"))
        val rightIn = sbsRightSurface ?: return onError(IllegalStateException("No right surface"))

        val outLeft = OutputConfiguration(leftIn).apply { setPhysicalCameraId(sbsLeftPhysicalId) }
        val outRight =
            OutputConfiguration(rightIn).apply { setPhysicalCameraId(sbsRightPhysicalId) }

        val exec = { r: Runnable -> (camHandler ?: mainHandler).post(r) }

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sbsSession = session
                try {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(leftIn)
                        addTarget(rightIn)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, sbsFps)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(
                            CaptureRequest.NOISE_REDUCTION_MODE,
                            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                        )
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                    session.setRepeatingRequest(b.build(), null, camHandler)

                    pipeline = Pipeline.SBS3D
                    isRecording = true
                    sbsComposer?.start()    // start GL pump first
                    mediaRecorder?.start()  // then start muxer/recorder
                    onStarted()
                    Log.d(
                        TAG,
                        "SBS (logical) configured. logical=$sbsLogicalId L=$sbsLeftPhysicalId R=$sbsRightPhysicalId out=${sbsSize.width * 2}x${sbsSize.height}"
                    )
                } catch (t: Throwable) {
                    onError(t)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError(IllegalStateException("SBS configure failed"))
            }

            override fun onClosed(session: CameraCaptureSession) { /* no-op */
            }
        }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(outLeft, outRight),
            { runnable -> exec(runnable) },
            sessionCallback
        )

        try {
            dev.createCaptureSession(sessionConfig)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    @RequiresApi(29)
    private fun openConcurrentDualSessions(
        leftId: String,
        rightId: String,
        size: Size,
        fps: Range<Int>,
        onStarted: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val leftIn = sbsLeftSurface ?: return onError(IllegalStateException("No left surface"))
        val rightIn = sbsRightSurface ?: return onError(IllegalStateException("No right surface"))

        var leftOpened = false
        var rightOpened = false

        val tryStartSessions = {
            if (leftOpened && rightOpened) {
                // both devices open; create sessions
                val leftCb = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sbsLeftSession = session
                        val b =
                            sbsLeftDev!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(leftIn)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                                set(
                                    CaptureRequest.CONTROL_AWB_MODE,
                                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                                )
                                set(
                                    CaptureRequest.NOISE_REDUCTION_MODE,
                                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                                )
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            }
                        session.setRepeatingRequest(b.build(), null, camHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError(IllegalStateException("Left concurrent session configure failed"))
                    }
                }
                val rightCb = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sbsRightSession = session
                        val b =
                            sbsRightDev!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(rightIn)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                                set(
                                    CaptureRequest.CONTROL_AWB_MODE,
                                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                                )
                                set(
                                    CaptureRequest.NOISE_REDUCTION_MODE,
                                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                                )
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            }
                        session.setRepeatingRequest(b.build(), null, camHandler)
                        // When right session is up, we can start pipeline
                        onStarted()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError(IllegalStateException("Right concurrent session configure failed"))
                    }
                }

                sbsLeftDev!!.createCaptureSession(listOf(leftIn), leftCb, camHandler)
                sbsRightDev!!.createCaptureSession(listOf(rightIn), rightCb, camHandler)
            }
        }

        val leftOpenCb = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                sbsLeftDev = device; leftOpened = true; tryStartSessions()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close(); onError(RuntimeException("Left camera disconnected"))
            }

            override fun onError(device: CameraDevice, error: Int) {
                val why =
                    explainCamError(error); device.close(); onError(RuntimeException("Left camera open failed: $why ($error)"))
            }
        }
        val rightOpenCb = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                sbsRightDev = device; rightOpened = true; tryStartSessions()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close(); onError(RuntimeException("Right camera disconnected"))
            }

            override fun onError(device: CameraDevice, error: Int) {
                val why =
                    explainCamError(error); device.close(); onError(RuntimeException("Right camera open failed: $why ($error)"))
            }
        }

        manager?.openCamera(leftId, leftOpenCb, camHandler)
        manager?.openCamera(rightId, rightOpenCb, camHandler)
    }

    fun stop3DRecordingSbs(
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (pipeline != Pipeline.SBS3D) {
            onError(IllegalStateException("Not in SBS3D mode")); return
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

        pipeline = Pipeline.STD60 // back to normal after SBS

        if (err != null) {
            onError(err!!); return
        }
        if (recorded == null || !recorded.exists()) {
            onError(IllegalStateException("No output file")); return
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(recorded.absolutePath),
            arrayOf("video/mp4"),
            null
        )
        onSaved(Uri.fromFile(recorded))
    }
    fun releaseSbs(){
        // Stop repeating on whichever path was used
        if (sbsUsingLogical) {
            try {
                sbsSession?.stopRepeating()
            } catch (_: Throwable) {
            }
        } else {
            try {
                sbsLeftSession?.stopRepeating()
            } catch (_: Throwable) {
            }
            try {
                sbsRightSession?.stopRepeating()
            } catch (_: Throwable) {
            }
        }
        // Tear down GL compositor
        sbsComposer?.stop()
        sbsComposer?.release()
        sbsComposer = null
        sbsLeftSurface = null
        sbsRightSurface = null



        // close sessions & devices
        if (sbsUsingLogical) {
            try {
                sbsSession?.close()
            } catch (_: Throwable) {
            }
            sbsSession = null
            try {
                cameraDevice?.close()
            } catch (_: Throwable) {
            }
            cameraDevice = null
        } else {
            try {
                sbsLeftSession?.close()
            } catch (_: Throwable) {
            }
            try {
                sbsRightSession?.close()
            } catch (_: Throwable) {
            }
            sbsLeftSession = null; sbsRightSession = null
            try {
                sbsLeftDev?.close()
            } catch (_: Throwable) {
            }
            try {
                sbsRightDev?.close()
            } catch (_: Throwable) {
            }
            sbsLeftDev = null; sbsRightDev = null
        }

    }

    // ADD: convert Image (JPEG) → ByteArray
    private fun imageToJpegBytes(img: Image): ByteArray {
        val plane = img.planes.firstOrNull() ?: throw IllegalStateException("No image planes")
        val buf: ByteBuffer = plane.buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        return bytes
    }

    /**
     * Capture one FULL-SBS 3D photo by firing two JPEG stills from a logical multi-camera
     * (back) that exposes at least two physical camera IDs. The two JPEGs are stitched
     * side-by-side into a single JPG and saved under Pictures/OPIC.
     *
     * Requirements: API 28+ (logical multi-camera). Falls back error if not available.
     */
    @RequiresApi(28)
    @RequiresPermission(Manifest.permission.CAMERA)
    fun capture3DPhotoSbs(
        context: Context,
        jpegQuality: Int = 92,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val cm = manager ?: return onError(IllegalStateException("CameraManager not ready"))
        val logical = findLogicalBackWithTwoPhysicals(cm)
            ?: return onError(IllegalStateException("No logical back camera with 2 physicals found"))

        val (logicalId, physicals) = logical
        val leftId =
            physicals.firstOrNull() ?: return onError(IllegalStateException("Missing left camera"))
        val rightId =
            physicals.getOrNull(1) ?: return onError(IllegalStateException("Missing right camera"))

        // Prepare two JPEG readers
        val leftReader = ImageReader.newInstance(sbsSize.width, sbsSize.height, ImageFormat.JPEG, 2)
        val rightReader = ImageReader.newInstance(sbsSize.width, sbsSize.height, ImageFormat.JPEG, 2)

        val leftSurface = leftReader.surface
        val rightSurface = rightReader.surface

        val leftLatch = CountDownLatch(1)
        val rightLatch = CountDownLatch(1)
        val leftBytes = AtomicReference<ByteArray>()
        val rightBytes = AtomicReference<ByteArray>()

        fun acquireImageBytes(
            reader: ImageReader,
            target: AtomicReference<ByteArray>,
            latch: CountDownLatch
        ) {
            reader.acquireLatestImage()?.use { img ->
                val buf = img.planes[0].buffer
                val data = ByteArray(buf.remaining())
                buf.get(data)
                target.set(data)
            }
            latch.countDown()
        }

        leftReader.setOnImageAvailableListener({
            acquireImageBytes(
                leftReader,
                leftBytes,
                leftLatch
            )
        }, camHandler)
        rightReader.setOnImageAvailableListener({
            acquireImageBytes(
                rightReader,
                rightBytes,
                rightLatch
            )
        }, camHandler)

        // Camera device callback
        val stateCb = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                try {
                    val outLeft =
                        OutputConfiguration(leftSurface).apply { setPhysicalCameraId(leftId) }
                    val outRight =
                        OutputConfiguration(rightSurface).apply { setPhysicalCameraId(rightId) }

                    val sessionCfg = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(outLeft, outRight),
                        { r -> (camHandler ?: mainHandler).post(r) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val req =
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            .apply {
                                                addTarget(leftSurface)
                                                addTarget(rightSurface)
                                                set(
                                                    CaptureRequest.CONTROL_MODE,
                                                    CaptureRequest.CONTROL_MODE_AUTO
                                                )
                                                set(
                                                    CaptureRequest.CONTROL_AE_MODE,
                                                    CaptureRequest.CONTROL_AE_MODE_ON
                                                )
                                                set(
                                                    CaptureRequest.CONTROL_AWB_MODE,
                                                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                                                )
                                            }
                                    session.capture(req.build(), null, camHandler)
                                } catch (t: Throwable) {
                                    onError(t)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                onError(RuntimeException("SBS3D photo session configuration failed"))
                            }
                        }
                    )

                    device.createCaptureSession(sessionCfg)
                } catch (t: Throwable) {
                    onError(t)
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                onError(RuntimeException("SBS3D photo: device disconnected"))
            }

            override fun onError(device: CameraDevice, error: Int) {
                val why = explainCamError(error)
                onError(RuntimeException("SBS3D photo open failed: $why ($error)"))
            }
        }

        // ✅ Use safeOpenCamera() to avoid “open failed” errors
        safeOpenCamera(logicalId, stateCb)

        // Merge + save thread
        Thread {
            try {
                // Wait up to 3 s for both JPEGs
                leftLatch.await(3, TimeUnit.SECONDS)
                rightLatch.await(3, TimeUnit.SECONDS)

                val l = leftBytes.get() ?: throw IllegalStateException("Left image missing")
                val r = rightBytes.get() ?: throw IllegalStateException("Right image missing")

                val leftBmp = BitmapFactory.decodeByteArray(l, 0, l.size)
                val rightBmp = BitmapFactory.decodeByteArray(r, 0, r.size)

                val outW = leftBmp.width + rightBmp.width
                val outH = max(leftBmp.height, rightBmp.height)
                val combined = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                Canvas(combined).apply {
                    drawBitmap(leftBmp, 0f, 0f, null)
                    drawBitmap(rightBmp, leftBmp.width.toFloat(), 0f, null)
                }

                // ✅ Save using your createOutputFile()
                val file = createOutputFile(context, "SBS3D_Photo", ".jpg")
                FileOutputStream(file).use {
                    combined.compress(
                        Bitmap.CompressFormat.JPEG,
                        jpegQuality,
                        it
                    )
                }

                // Register in gallery
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
                mainHandler.post { onSaved(Uri.fromFile(file)) }
            } catch (t: Throwable) {
                mainHandler.post { onError(t) }
            } finally {
                // Cleanup
                (camHandler ?: mainHandler).post {
                    try {
                        leftReader.close()
                    } catch (_: Throwable) {
                    }
                    try {
                        rightReader.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }.start()
    }

    // ===================================================
// Safe camera open helper (prevents Pixel8 open crash)
// ===================================================
    @Volatile
    private var cameraOpening = false

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun safeOpenCamera(id: String, cb: CameraDevice.StateCallback) {
        if (cameraOpening) {
            Log.w(TAG, "Camera $id is already opening — skipping")
            return
        }
        if (cameraDevice != null && cameraDevice!!.id == id) {
            Log.d(TAG, "Camera $id already open → reusing existing instance")
            cb.onOpened(cameraDevice!!)
            return
        }

        cameraOpening = true
        val handler = camHandler ?: Handler(Looper.getMainLooper())

        try {
            manager?.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    cameraOpening = false
                    cameraDevice = dev
                    cb.onOpened(dev)
                }

                override fun onDisconnected(dev: CameraDevice) {
                    cameraOpening = false
                    cb.onDisconnected(dev)
                }

                override fun onError(dev: CameraDevice, error: Int) {
                    cameraOpening = false
                    val why = explainCamError(error)
                    Log.e(TAG, "Camera open failed: $why ($error)")
                    cb.onError(dev, error)
                }
            }, handler)
        } catch (t: Throwable) {
            cameraOpening = false
            Log.e(TAG, "Exception during openCamera", t)
        }
    }

    /** Safely close combo resources used in one-off SBS photo capture */
    private fun safeClose(
        device: CameraDevice?,
        session: CameraCaptureSession? = null,
        readers: Array<ImageReader> = emptyArray()
    ) {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        try {
            device?.close()
        } catch (_: Throwable) {
        }
        readers.forEach { r ->
            try {
                r.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun getFocalLength(cameraId: String?): Float {
        if (cameraId == null) return 0f
        return try {
            val chars = manager?.getCameraCharacteristics(cameraId)
            val fl = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            fl?.firstOrNull() ?: 0f
        } catch (_: Throwable) {
            0f
        }
    }

}
