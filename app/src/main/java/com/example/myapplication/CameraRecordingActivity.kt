package com.opic3d.Spatial.trendingvideos

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.opic3d.Spatial.trendingvideos.controllers.HybridSlowMoController
import com.opic3d.Spatial.trendingvideos.controllers.ModeSelectorController
import com.opic3d.Spatial.trendingvideos.controllers.ZoomAdapterControl
import com.opic3d.Spatial.trendingvideos.helper.ImageSplitter
import com.opic3d.Spatial.trendingvideos.model.SlowMoOption
import com.opic3d.Spatial.trendingvideos.model.SlowMoResult
import com.opic3d.Spatial.trendingvideos.model.listBackCameraSlowMoOptions
import com.opic3d.Spatial.trendingvideos.views.GlowingTimerView
import com.opic3d.Spatial.trendingvideos.views.OpicTextView
import com.opic3d.Spatial.trendingvideos.views.RotationLineOverlay
import com.opic3d.Spatial.trendingvideos.views.ZoomRulerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
// ADD these for 3D photo

class CameraRecordingActivity : ComponentActivity() {

    // ——— Controller ———
    private lateinit var controller: HybridSlowMoController

    // ——— Views ———
    private lateinit var previewView: PreviewView
    private lateinit var zoombutton: ImageView
    private lateinit var videobuttonRecording: ImageButton
    private lateinit var videoPauseResume: ImageButton
    private lateinit var flashBtn: ImageView
    private lateinit var timerImage: ImageView
    private lateinit var manualfocus: TextView
    private lateinit var timerView: GlowingTimerView
    private lateinit var zoomRulerView: ZoomRulerView
    private lateinit var zoomSwipeDetectRecyclerView: SwipeDetectRecyclerView
    private lateinit var zoomControlLayout: LinearLayout
    private lateinit var zoomControlAdapter: ZoomAdapterControl
    private lateinit var focusScaleView: FocusRulerView
    private lateinit var root: FrameLayout
    private lateinit var angleLineView: RotationLineOverlay
    private lateinit var li_Message: LinearLayout
    private lateinit var progressDialog: AlertDialog
    private lateinit var spnOptions: Spinner
    private lateinit var slowMoOption: OpicTextView
    private lateinit var modeController: ModeSelectorController

    // ——— Mode & state ———
    private enum class CaptureMode { PHOTO, VIDEO, TIMELAPSE,SLOWMO,THREEDPHOTO,THREEDVIDEO}

    private var captureMode: CaptureMode = CaptureMode.VIDEO
    private var isRecording = false
    private var isPaused = false
    private var isFlashOn = false
    private var isManualFocus = false
    private var isZoomButtonSelected = false
    private var isRotated = false
    var isSoundOn = true

    // ——— Options ———
    private var options: List<SlowMoOption> = emptyList()
    private var selectedOption: SlowMoOption? = null

    // ——— Focus / zoom state ———
    private var minFocusDistance: Float = 0f
    private var lastManualFocusNormalized: Float = 0.08f // [0..1] 0=∞, 1=macro
    private var lastSelectedFocus: Float = 0.08f
    private var currentZoomRatio: Float = 1.2f
    private var zoomAnimator: ValueAnimator? = null
    private var lastAppliedStep: Float? = null
    var zoomValue = 1.2f

    // ——— Timer ———
    private var timerJob: Job? = null
    private var startTime: Long = 0L
    private var pausedTime = 0L

    // ——— Sensors / rebind ———
    private lateinit var orientationEventListener: OrientationEventListener
    private lateinit var sensorManager: SensorManager
    private var rebindJob: Job? = null

    @Volatile
    private var isRebinding = false

    // ——— Time-lapse ———
    private var timeLapseMultiplier: Double = 10.0
    private var timeLapsePlaybackFps: Int = 30

    // ——— Sounds ———
    private val shutter by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    private val mediaSounds by lazy {
        MediaActionSound().apply {
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    // ——— Picker ———
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { /* uri */ }

    // ——— Activity ———
    @SuppressLint("MissingInflatedId", "ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setDecorFitsSystemWindows(false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        progressDialog = showPrettyProgressDialog(this)
        setContentView(R.layout.activity_camera_recording)

        // Views
        root = findViewById(R.id.root)
        previewView = findViewById(R.id.previewView)
        videobuttonRecording = findViewById(R.id.videobutton)
        videoPauseResume = findViewById(R.id.videoPauseResume)
        spnOptions = findViewById(R.id.spnOptions)
        flashBtn = findViewById(R.id.hdrIcon)
        zoombutton = findViewById(R.id.videobuttonblack)
        timerImage = findViewById(R.id.micIcon)
        manualfocus = findViewById(R.id.stopButton)
        li_Message = findViewById(R.id.llRotationMessage)
        timerView = findViewById(R.id.glowTimer)
        focusScaleView = findViewById(R.id.focusScaleView)
        zoomRulerView = findViewById(R.id.zoomRulerView)
        zoomControlLayout = findViewById(R.id.zoomControlBg)
        zoomSwipeDetectRecyclerView = findViewById(R.id.zoomControlRecyclerView)
        angleLineView = findViewById(R.id.lineOverlay)
        slowMoOption = findViewById(R.id.slowMoOption)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        // Controller
        controller = HybridSlowMoController(this, this, previewView)

        updateUiForMode()
        findViewById<TextView>(R.id.pickImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Mode strip placement
        val rv = findViewById<RecyclerView>(R.id.rvMode)
        val marginStartPx = 260.dp(rv.context)
        rv.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            MarginLayoutParamsCompat.setMarginStart(this, marginStartPx)
        }
        (rv.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = marginStartPx
        rv.requestLayout()

        // Mode controller
        modeController = ModeSelectorController(rv,controller.check3DSupport()) { index ->
            captureMode = when (index) {
                0 -> CaptureMode.VIDEO
                1 -> CaptureMode.PHOTO
                2 -> CaptureMode.TIMELAPSE
                3 -> CaptureMode.SLOWMO
                4 -> CaptureMode.THREEDPHOTO
                else -> CaptureMode.THREEDVIDEO
            }
            toggleMode()
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        //zoomControlAdapter()
        // Button look
        manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
        manualfocus.setTextColor(Color.WHITE)
        zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
        zoombutton.setImageResource(R.drawable.zoomwhite)
        zoombutton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        zoombutton.setPadding(22, 22, 22, 22)

        handleClickListener()
        initAfterPermissions()
        setSwiperMovementHandler()
        checkOrientation()
    }

    private fun zoomControlAdapter() {
        // Zoom control list
        //  val zoomLevels: MutableList<Float> = mutableListOf(5f, 4f, 3f, 2f, 1.2f, 1f)
        val zoomLevels = controller.getZoomLevels()
        zoomControlAdapter =
            ZoomAdapterControl(zoomLevels, object : ZoomAdapterControl.OnZoomClick {
                override fun onZoomClick(ratio: Float) {
                    zoomRulerView.zoomValue = ratio
                    isZoomButtonSelected = true
                    zoomValue = ratio
                    Log.d("ZoomRuler", "set ${ratio}")
                    controller.setZoomLevel(ratio)
                    currentZoomRatio = ratio
                    if (ratio == 1.2f) zoomControlAdapter.selectRatio(1.2f)
                }
            })
        zoomSwipeDetectRecyclerView.layoutManager =
            LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        zoomSwipeDetectRecyclerView.adapter = zoomControlAdapter
        zoomSwipeDetectRecyclerView.rulerView = zoomRulerView
        zoomControlAdapter.selectRatio(1.2f)
        zoomRulerView.zoomValue = 1.2f

        zoomSwipeDetectRecyclerView.onSwipeUp = { onSwipeDown() }
        zoomSwipeDetectRecyclerView.onSwipeDown = { onSwipeDown() }

        var isUserSliding = false
        val hideDelay = 3000L
        zoomRulerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isUserSliding = true; zoomControlLayout.visibility = View.GONE
                }

                MotionEvent.ACTION_MOVE -> if (!isUserSliding) {
                    isUserSliding = true; zoomControlLayout.visibility = View.GONE
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserSliding = false
                    zoomRulerView.postDelayed({
                        isZoomButtonSelected = true
                        zoomValue = lastAppliedStep ?: 0.0f
                        if (!isUserSliding && focusScaleView.isGone) {
                            zoomControlLayout.visibility = View.VISIBLE
                            zoomRulerView.visibility = View.GONE
                        }
                    }, hideDelay)
                }
            }
            false
        }

        // Ruler incremental zoom changes
        val stepSize = 0.1f
        zoomRulerView.onZoomChanged = { newZoom ->
            var steppedZoom = ((newZoom / stepSize).roundToInt() * stepSize)
            if (lastAppliedStep != steppedZoom) {
                if (isZoomButtonSelected) {
                    steppedZoom = zoomValue
                    isZoomButtonSelected = false
                } else {
                    zoomValue = steppedZoom
                }
                controller.setZoomLevel(steppedZoom)
                currentZoomRatio = steppedZoom
                vibrateOnce()
                zoomControlAdapter.updateSingleZoomStep(newZoom.roundToInt(), newZoom)
                lastAppliedStep = steppedZoom
                zoomRulerView.zoomValue = steppedZoom
            }
        }

    }


    // ——— Bind / Rebind ———
    private fun toggleMode() {
        spnOptions.visibility = if (captureMode == CaptureMode.SLOWMO) View.GONE else View.GONE
        slowMoOption.visibility = if (captureMode == CaptureMode.SLOWMO) View.VISIBLE else View.GONE
        updateUiForMode()
        rebindForCurrentMode()
        vibrateOnce()
    }

    private fun rebindForCurrentMode() {
        if (isRebinding) return
        isRebinding = true
        rebindJob?.cancel()
        rebindJob = lifecycleScope.launch {
            try {
                delay(120)
                when (captureMode) {
                    CaptureMode.PHOTO -> {
                        controller.release()
                        controller.bindPhoto()
                        afterBindCommon()
                        zoomControlAdapter()
                    }

                    CaptureMode.VIDEO -> {
                        controller.release()
                        controller.bindVideo()
                        afterBindCommon()
                        zoomControlAdapter()
                    }

                    CaptureMode.SLOWMO -> {
                        if (options.isEmpty()) {
                            Toast.makeText(
                                this@CameraRecordingActivity,
                                "No slow-mo options found!",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        if (selectedOption == null) selectedOption = options.first()
                        controller.release()
                        if (ActivityCompat.checkSelfPermission(
                                this@CameraRecordingActivity,
                                Manifest.permission.CAMERA
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return@launch
                        }
                        slowMoOption.timerText= selectedOption?.label!!
                        controller.bindSlowMo(selectedOption!!) { e ->
                            Log.e("Hybrid", "Slow-mo bind failed", e)
                            runOnUiThread {
                                modeController.setIndex(0)
                                captureMode = CaptureMode.VIDEO
                                toggleMode()
                                Toast.makeText(
                                    this@CameraRecordingActivity,
                                    "Slow-mo unsupported: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        afterBindCommon()
                        zoomControlAdapter()
                    }
                    CaptureMode.THREEDPHOTO -> {

                        controller.release()
                        if (ActivityCompat.checkSelfPermission(
                                this@CameraRecordingActivity,
                                Manifest.permission.CAMERA
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return@launch
                        }
                        slowMoOption.timerText= selectedOption?.label!!
                        controller.bindTHREED(selectedOption!!) { e ->
                            Log.e("Hybrid", "3D bind failed", e)
                            runOnUiThread {
                                modeController.setIndex(0)
                                captureMode = CaptureMode.PHOTO
                                toggleMode()
                                Toast.makeText(
                                    this@CameraRecordingActivity,
                                    "3D unsupported: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        afterBindCommon()
                        zoomControlAdapter()
                    }
                    CaptureMode.THREEDVIDEO -> {
                        controller.release()
                        if (ActivityCompat.checkSelfPermission(
                                this@CameraRecordingActivity,
                                Manifest.permission.CAMERA
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return@launch
                        }
                        slowMoOption.timerText= selectedOption?.label!!
                        controller.bindTHREED(selectedOption!!) { e ->
                            Log.e("Hybrid", "3D bind failed", e)
                            runOnUiThread {
                                modeController.setIndex(0)
                                captureMode = CaptureMode.VIDEO
                                toggleMode()
                                Toast.makeText(
                                    this@CameraRecordingActivity,
                                    "3D unsupported: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        afterBindCommon()
                        zoomControlAdapter()
                    }
                    CaptureMode.TIMELAPSE -> {
                        // ✅ Timelapse uses CameraX video bind (not Camera2 slow-mo)
                        controller.release()
                        controller.bindTimeLapse()
                        afterBindCommon()
                        zoomControlAdapter()
                    }
                }
            } finally {
                isRebinding = false
            }
        }
    }

    private fun afterBindCommon() {
        minFocusDistance = controller.getMinFocusDistance()

        // Keep the MF ruler UI in sync with what the user chose last time
        if (minFocusDistance > 0f) {
            focusScaleView.visibility = if (isManualFocus) View.VISIBLE else View.GONE
            focusScaleView.setValueSilently(lastManualFocusNormalized)
        } else {
            focusScaleView.visibility = View.GONE
        }

        // ✅ Re-apply the LAST zoom the user set (don't reset to 1.2x)
        controller.setZoomLevel(currentZoomRatio)

        // ✅ Re-apply focus mode to the freshly bound camera
        if (isManualFocus && minFocusDistance > 0f) {
            controller.setManualFocusNormalized(lastManualFocusNormalized)
        } else {
            controller.enableAutoFocus()
        }
    }

    // ——— Click listeners ———
    @SuppressLint("ClickableViewAccessibility")
    private fun handleClickListener() {
        videobuttonRecording.setOnClickListener {
            when (captureMode) {
                CaptureMode.PHOTO -> if (!isRotated) takePhoto()
                CaptureMode.THREEDPHOTO -> if (!isRotated) take3DPhoto()
                else -> if (!isRotated) {
                    zoombutton.visibility = View.VISIBLE
                    when {
                        !isRecording -> {
                            videoPauseResume.visibility = View.VISIBLE
                            manualfocus.visibility = View.GONE
                            videobuttonRecording.setBackgroundResource(R.drawable.record_button_ring)
                            videobuttonRecording.setImageResource(R.drawable.recordicon)
                            videobuttonRecording.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            videobuttonRecording.setPadding(32, 32, 32, 32)
                            timerView.visibility = View.VISIBLE
                            focusScaleView.visibility = View.GONE

                            if (ActivityCompat.checkSelfPermission(
                                    this, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) startVideoRecording()

                            isRecording = true
                            isPaused = false
                        }

                        isRecording && !isPaused -> {
                            when (captureMode) {
                                CaptureMode.SLOWMO -> {
                                    keepDeviceAwake(false)
                                    controller.stopRecording(
                                        keepAudio = false,
                                        onSaved = { uri ->
                                            isRecording = false
                                            Handler(Looper.getMainLooper()).post {
                                                showMediaPopup(uri)
                                            }
                                        },
                                        onError = { e ->
                                            isRecording = false
                                            Log.e("SlowMo", "Stop error", e)
                                        }
                                    )
                                }

                                CaptureMode.TIMELAPSE -> {
                                    keepDeviceAwake(false)
                                    controller.stopLapseVideo(
                                        normalizePlaybackFps = false,
                                        onSaved = { uri ->
                                            isRecording = false
                                            Handler(Looper.getMainLooper()).post {
                                                showMediaPopup(uri)
                                            }
                                        },
                                        onError = { e ->
                                            isRecording = false
                                            Log.e("TL", "Stop error", e)
                                        }
                                    )
                                }

                                else -> stopVideoRecording()
                            }
                            videoPauseResume.visibility = View.GONE
                            manualfocus.visibility = View.VISIBLE
                            stopTimer()
                            keepDeviceAwake(false)
                            pausedTime = 0L
                            timerView.timerText = "00:00:00"
                            zoombutton.visibility = View.GONE
                            focusScaleView.visibility = View.GONE
                            zoombutton.visibility = View.VISIBLE
                            videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
                            isRecording = false
                            isPaused = false
                        }

                        isRecording && isPaused -> {
                            timerView.timerText = "00:00:00"
                            pausedTime = 0L
                            startVideoRecording()
                            zoombutton.visibility = View.VISIBLE
                            isRecording = true
                            isPaused = false
                        }
                    }
                }
            }
        }

        videoPauseResume.setOnClickListener {
            if (isRotated) return@setOnClickListener
            zoombutton.visibility = View.VISIBLE
            focusScaleView.visibility = View.GONE
            when {
                isRecording && !isPaused -> pauseVideoRecording()
                isRecording && isPaused -> resumeVideoRecording()
            }
        }

        videobuttonRecording.setOnLongClickListener {
            if (isRecording) {
                stopVideoRecording()
                stopTimer()
                keepDeviceAwake(false)
                pausedTime = 0L
                timerView.timerText = "00:00:00"
                zoombutton.visibility = View.GONE
                videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
                videobuttonRecording.setImageResource(R.drawable.recordicon)
                isRecording = false
                isPaused = false
            }
            true
        }

        hideZoomSelectorView()

        zoombutton.setOnClickListener {
            isZoomButtonSelected = !isZoomButtonSelected
            if (isZoomButtonSelected) {
                zoombutton.setBackgroundResource(R.drawable.ring_white_color)
                zoombutton.setImageResource(R.drawable.zoom_black)
                manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                manualfocus.setTextColor(Color.WHITE)
                isManualFocus = false
                zoomControlLayout.visibility = View.GONE
                focusScaleView.visibility = View.GONE
                showZoomSelectorView()
            } else {
                zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
                zoombutton.setImageResource(R.drawable.zoomwhite)
                zoomControlLayout.visibility = View.VISIBLE
                focusScaleView.visibility = View.GONE
                isManualFocus = false
                hideZoomSelectorView()
            }
        }

        flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
        flashBtn.setImageResource(R.drawable.flash_circle)
        flashBtn.setOnClickListener {
            isFlashOn = !isFlashOn
            controller.setTorch(isFlashOn)
            if (isFlashOn) {
                flashBtn.setImageResource(R.drawable.flash_on)
            } else {
                flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
                flashBtn.setImageResource(R.drawable.flash_circle)
            }
        }

        manualfocus.setOnClickListener {
            if (!isManualFocus) {
                isManualFocus = true
                controller.setManualFocusNormalized(lastManualFocusNormalized)
                focusScaleView.visibility = View.VISIBLE
                zoomControlLayout.visibility = View.GONE
                zoomRulerView.visibility = View.GONE

                manualfocus.setBackgroundResource(R.drawable.manulafocus_bg)
                manualfocus.setTextColor(Color.BLACK)
                manualfocus.text = "MF"
            } else {
                isManualFocus = false
                controller.enableAutoFocus()
                focusScaleView.visibility = View.GONE
                manualfocus.text = "AF"
                manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                manualfocus.setTextColor(Color.WHITE)
            }
        }

        // Focus slider normalized [0..1]
        focusScaleView.onFocusChanged = { normalized ->
            lastManualFocusNormalized = normalized.coerceIn(0f, 1f)
            if (isManualFocus) {
                controller.setManualFocusNormalized(lastManualFocusNormalized)
                vibrateOnce()
            }
            lastSelectedFocus = normalized
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun take3DPhoto() {
        if (isSoundOn) shutter.play(MediaActionSound.SHUTTER_CLICK)
        controller.take3DPhoto(
            withAudio = true,
            onStarted = { isRecording = true },
            onSaved = { uri -> showMediaPopup(uri,true) },
            onError = { e ->
                stopTimer()
                isRecording = false
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )

    }

    // Saves any content Uri to a temp file, returns File
    private fun saveUriToTempFile(uri: Uri, prefix: String = "IMG_", ext: String = ".jpg"): File? {
        return try {
            val outDir = externalCacheDir ?: cacheDir
            if (!outDir.exists()) outDir.mkdirs()
            val f = File(outDir, "$prefix${System.currentTimeMillis()}$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            f
        } catch (e: Exception) {
            Log.e("Photo", "Failed to save Uri to temp file", e)
            null
        }
    }

    // Photo via controller
    private fun takePhoto() {
        controller.takePhoto(
            onSaved = { uri ->
                if (isSoundOn) shutter.play(MediaActionSound.SHUTTER_CLICK)
                val tmp = saveUriToTempFile(uri) ?: return@takePhoto
                lifecycleScope.launch {
                    delay(300)
                    cropImage(tmp)
                }
            },
            onError = { e ->
                Log.e("Photo", "Failed: ${e.message}", e)
                Toast.makeText(this, "Photo failed: ${e.localizedMessage}", Toast.LENGTH_SHORT)
                    .show()
            }
        )
    }

    // ——— Start/Stop/Timer ———
    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startVideoRecording() {
        isRecording = true
        li_Message.visibility = View.GONE

        when (captureMode) {
            CaptureMode.SLOWMO -> {
                if (!controller.isReady()) {
                    isRecording = false
                    Toast.makeText(this, "Slow-mo not ready", Toast.LENGTH_SHORT).show()
                    return
                }
                controller.startRecording(
                    withAudio = true,
                    onStarted = { isRecording = true },
                    onSaved = { uri -> showMediaPopup(uri) },
                    onError = { e ->
                        stopTimer()
                        isRecording = false
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }

            CaptureMode.TIMELAPSE -> {
                if (!controller.isReady()) {
                    isRecording = false
                    Toast.makeText(this, "Time-lapse not ready", Toast.LENGTH_SHORT).show()
                    return
                }
                showTimeLapseSpeedPicker()
                return
            }

            else -> {
                controller.startRecording(
                    withAudio = true,
                    onStarted = {},
                    onSaved = { uri -> showMediaPopup(uri) },
                    onError = { e ->
                        stopTimer()
                        isRecording = false
                        Log.d("Video", "Start error ${e.message}")
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

        if (isSoundOn) mediaSounds.play(MediaActionSound.START_VIDEO_RECORDING)
        pausedTime = 0L
        startTimer()
        keepDeviceAwake(true)
    }

    private fun stopVideoRecording() {
        isRecording = false
        isPaused = false
        angleLineView.visibility = View.VISIBLE
        controller.stopRecording(
            keepAudio = true,
            onSaved = { uri ->
                if (isSoundOn) mediaSounds.play(MediaActionSound.STOP_VIDEO_RECORDING)
                showMediaPopup(uri)
            },
            onError = { e -> Log.e("Video", "Stop error", e) }
        )
        videobuttonRecording.alpha = 1.0f
    }

    private fun startTimer() {
        startTime = SystemClock.elapsedRealtime() - pausedTime
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000
                val hours = elapsed / 3600
                val minutes = (elapsed % 3600) / 60
                val seconds = elapsed % 60
                timerView.isTimerRunning = true
                timerView.timerText =
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerView.isTimerRunning = false
        timerJob?.cancel()
    }

    private fun resumeTimer() {
        startTimer()
    }

    private fun pauseVideoRecording() {
        // (We only pause the timer/UX; actual MediaRecorder pause not used here)
        pausedTime = SystemClock.elapsedRealtime() - startTime
        stopTimer()
        isPaused = true
        videoPauseResume.setBackgroundResource(R.drawable.play_icon)
    }

    private fun resumeVideoRecording() {
        resumeTimer()
        isPaused = false
        videoPauseResume.setBackgroundResource(R.drawable.pause)
    }

    // ——— Orientation ———
    private fun checkOrientation() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val isLandscape = (orientation in 45..135) || (orientation in 225..315)
                if (!isLandscape) {
                    if (!isRecording) {
                        angleLineView.visibility = View.GONE
                        li_Message.visibility = View.VISIBLE
                        isRotated = true
                    }
                } else {
                    if (!isRecording) {
                        isRotated = false
                        angleLineView.visibility = View.VISIBLE
                        li_Message.visibility = View.GONE
                    }
                }
            }
        }
        if (orientationEventListener.canDetectOrientation()) orientationEventListener.enable()
    }

    override fun onDestroy() {
        shutter.release()
        mediaSounds.release()
        controller.release()
        super.onDestroy()
    }

    // ——— UI helpers ———
    fun showZoomSelectorView() {
        zoomControlLayout.visibility = View.VISIBLE
        zoomRulerView.visibility = View.GONE
    }

    fun hideZoomSelectorView() {
        zoomControlLayout.visibility = View.GONE
        zoomRulerView.visibility = View.GONE
    }

    private fun updateUiForMode() {
        timerView.visibility = if (captureMode != CaptureMode.PHOTO) View.VISIBLE else View.GONE
        if (captureMode == CaptureMode.PHOTO ||captureMode == CaptureMode.THREEDPHOTO ) {
            videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
            videobuttonRecording.setImageResource(R.drawable.ic_camera)
            timerView.isTimerRunning = false
            timerView.visibility= View.GONE
        } else {
            videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
            videobuttonRecording.setImageResource(R.drawable.recordicon)
            timerView.visibility= View.VISIBLE
        }
    }

    // ——— Permissions ———
    private fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val aud = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return cam == PackageManager.PERMISSION_GRANTED && aud == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val pm = packageManager
                val intent = pm.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                finish()
                startActivity(intent)
                Runtime.getRuntime().exit(0)
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initAfterPermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                101
            )
            return
        }

        lifecycleScope.launch {
            options = withContext(Dispatchers.Default) {
                listBackCameraSlowMoOptions(this@CameraRecordingActivity, 60)
            }
            if (options.isEmpty()) {
                Toast.makeText(
                    this@CameraRecordingActivity,
                    "No slow-mo options found",
                    Toast.LENGTH_LONG
                ).show()
            }
            val slowMoAdapter = com.opic3d.Spatial.trendingvideos.adapters.SlowMoOptionAdapter(
                this@CameraRecordingActivity, options
            )
            spnOptions.adapter = slowMoAdapter
            spnOptions.setPopupBackgroundResource(R.drawable.bg_dropdown_popup)

            spnOptions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (captureMode != CaptureMode.SLOWMO) return
                    val newOpt = options[position]
                    if (selectedOption === newOpt) return
                    selectedOption = newOpt
                    rebindForCurrentMode()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            if (options.isNotEmpty()) spnOptions.setSelection(0)
            rebindForCurrentMode()
        }
    }

    // ——— Swipe handler ———
    private fun setSwiperMovementHandler() {
        var startY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y; true
                }

                MotionEvent.ACTION_UP -> {
                    val deltaY = event.y - startY
                    if (deltaY > 120) {
                        captureMode = when (captureMode) {
                            CaptureMode.VIDEO -> {
                                modeController.setIndex(1); CaptureMode.PHOTO
                            }

                            CaptureMode.PHOTO -> {
                                modeController.setIndex(2); CaptureMode.TIMELAPSE
                            }

                            CaptureMode.TIMELAPSE -> {
                                modeController.setIndex(3); CaptureMode.SLOWMO
                            }

                            CaptureMode.SLOWMO -> {
                                modeController.setIndex(4); CaptureMode.THREEDPHOTO
                            }
                            CaptureMode.THREEDPHOTO -> {
                                modeController.setIndex(5); CaptureMode.THREEDVIDEO
                            }
                            CaptureMode.THREEDVIDEO -> {
                                modeController.setIndex(0); CaptureMode.VIDEO
                            }
                        }
                        toggleMode()
                    } else if (deltaY < -120) {
                        captureMode = when (captureMode) {
                            CaptureMode.THREEDVIDEO -> {
                                modeController.setIndex(4); CaptureMode.THREEDPHOTO
                            }
                            CaptureMode.THREEDPHOTO -> {
                                modeController.setIndex(3); CaptureMode.SLOWMO
                            }
                            CaptureMode.SLOWMO -> {
                                modeController.setIndex(2); CaptureMode.TIMELAPSE
                            }

                            CaptureMode.TIMELAPSE -> {
                                modeController.setIndex(1); CaptureMode.PHOTO
                            }

                            else -> {
                                modeController.setIndex(0); CaptureMode.VIDEO
                            }
                        }
                        toggleMode()
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ——— Video compression helper (unchanged logic) ———
    private fun compressVideo(uri: Uri) {
        lifecycleScope.launch {
            val outputUri = compressVideoToCacheUri(this@CameraRecordingActivity, uri)
            if (outputUri != null) showMediaPopup(uri)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(UnstableApi::class)
    suspend fun compressVideoToCacheUri(
        context: Context,
        inputUri: Uri,
        targetBitrate: Int = 4_000_000,
        preferHevc: Boolean = true
    ): Uri? = suspendCancellableCoroutine { cont ->
        val outputFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        val outputPath = outputFile.absolutePath
        val mediaItem = MediaItem.fromUri(inputUri)

        val videoSettings = VideoEncoderSettings.Builder().setBitrate(targetBitrate).build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setEnableFallback(true)
            .build()

        val act = context as? Activity
        if (act != null && !act.isFinishing && !act.isDestroyed) progressDialog.show()

        var finished = false
        fun finishWith(result: Uri?) {
            if (finished) return
            finished = true
            Handler(Looper.getMainLooper()).post { runCatching { progressDialog.dismiss() } }
            if (cont.isActive) cont.resume(result, onCancellation = null)
        }

        fun startWith(mime: String) {
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(mime)
                .build()

            cont.invokeOnCancellation {
                runCatching { transformer.cancel() }
                outputFile.delete()
            }

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Handler(Looper.getMainLooper()).post { runCatching { progressDialog.dismiss() } }
                    finishWith(Uri.fromFile(outputFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException
                ) {
                    progressDialog.dismiss()
                    if (mime == MimeTypes.VIDEO_H265) {
                        startWith(MimeTypes.VIDEO_H264)
                    } else {
                        outputFile.delete()
                        finishWith(null)
                    }
                }
            })

            try {
                transformer.start(mediaItem, outputPath)
            } catch (_: Throwable) {
                if (mime == MimeTypes.VIDEO_H265) startWith(MimeTypes.VIDEO_H264)
                else {
                    outputFile.delete(); finishWith(null)
                }
            }
        }

        startWith(if (preferHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
    }

    private fun showPrettyProgressDialog(
        context: Context,
        title: String = "Compressing Video",
        message: String = "Please wait while your video is being processed...",
        cancelable: Boolean = false
    ): AlertDialog {
        val themedCtx = ContextThemeWrapper(
            context,
            com.google.android.material.R.style.Theme_Material3_DayNight
        )
        val view =
            LayoutInflater.from(themedCtx).inflate(R.layout.dialog_progress_pretty, null, false)
        view.findViewById<TextView>(R.id.tvTitle).text = title
        view.findViewById<TextView>(R.id.tvMessage).text = message
        return MaterialAlertDialogBuilder(themedCtx, R.style.PrettyDialogTheme)
            .setView(view)
            .setCancelable(cancelable)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
    }

    // dp extension
    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).roundToInt()

    // Fullscreen preview
    fun Context.showMediaPopup(uri: Uri, isImage: Boolean = false) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_media_popup)

        val imgClose = dialog.findViewById<AppCompatImageView>(R.id.imgClose)
        val imgMedia = dialog.findViewById<ImageView>(R.id.imgPreview)
        val videoMedia = dialog.findViewById<VideoView>(R.id.videoPreview)

        if (isImage) {
            imgMedia.visibility = View.VISIBLE
            videoMedia.visibility = View.GONE
            imgMedia.setImageURI(uri)
        } else {
            val result = checkSlowMoFromUri(this, uri, assumedCaptureFps = 120)
            Log.d(
                "SlowMoCheck",
                "Playback FPS=${result.playbackFps?.let { "%.2f".format(it) } ?: "?"} • Factor=${
                    result.factor?.let {
                        "%.2f".format(it)
                    } ?: "?"
                }x • SlowMo=${result.isSlowMo}")
            imgMedia.visibility = View.GONE
            videoMedia.visibility = View.VISIBLE
            videoMedia.setVideoURI(uri)
            videoMedia.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoMedia.start()
            }
        }

        imgClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    fun getFileSize(uri: Uri): String = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            val size = fd.statSize
            when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> String.format(Locale.US, "%.2f KB", size / 1024f)
                else -> String.format(Locale.US, "%.2f MB", size / (1024f * 1024f))
            }
        } ?: "0 B"
    } catch (_: Exception) {
        "Unknown"
    }

    private fun cropImage(outFile: File) {
        val cacheUri = FileProvider.getUriForFile(
            this@CameraRecordingActivity,
            "${packageName}.fileprovider",
            outFile
        )
        val fileUriString: String = outFile.toURI().toString()
        val leftUri: Uri = ImageSplitter.splitHalfToUri(this, cacheUri, ImageSplitter.Side.LEFT)
        val rightUri: Uri = ImageSplitter.splitHalfToUri(this, cacheUri, ImageSplitter.Side.RIGHT)
        val resultIntent = Intent().apply {
            putExtra("image_uri", cacheUri.toString())
            putExtra("image_path", fileUriString)
            putExtra("duration", (SystemClock.elapsedRealtime() - startTime) / 1000)
            putExtra("file_size", getFileSize(cacheUri))
        }
        showMediaPopup(leftUri, true)
    }

    // ——— TL speed picker ———
    private fun showTimeLapseSpeedPicker() {
        val items = arrayOf("5x", "10x", "20x", "30x")
        AlertDialog.Builder(this)
            .setTitle("Time-lapse speed")
            .setItems(items) { _, which ->
                val picked = items[which].removeSuffix("x").toDoubleOrNull() ?: 10.0
                timeLapseMultiplier = picked

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) return@setItems

                // ✅ Bind & start TL inside a coroutine (bindTimeLapse is suspend)
                lifecycleScope.launch {
                    try {
                        controller.bindTimeLapse()
                        // ✅ ensure TL starts with the same zoom + MF as before
                        afterBindCommon()
                        controller.startLapseVideo(
                            multiplier = timeLapseMultiplier,
                            playbackFps = timeLapsePlaybackFps,
                            onStarted = { isRecording = true },
                            onSaved = { uri ->
                                isRecording = false
                                Handler(Looper.getMainLooper()).post { showMediaPopup(uri) }
                            },
                            onError = { e ->
                                isRecording = false
                                Log.d("timelapse", "${e.message}")
                                Toast.makeText(
                                    this@CameraRecordingActivity,
                                    "Error: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )

                        if (isSoundOn) mediaSounds.play(MediaActionSound.START_VIDEO_RECORDING)
                        pausedTime = 0L
                        startTimer()
                        keepDeviceAwake(true)
                    } catch (t: Throwable) {
                        Toast.makeText(
                            this@CameraRecordingActivity,
                            "Bind failed: ${t.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    // ——— Wake lock ———
    private fun keepDeviceAwake(keep: Boolean) {
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            previewView.keepScreenOn = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            previewView.keepScreenOn = false
        }
    }

    // ——— Swipe zoom helper ———
    private fun onSwipeDown() {
        zoomRulerView.visibility = View.VISIBLE
        zoomControlLayout.visibility = View.GONE
    }

    private fun vibrateOnce() {
        val vibrator = getSystemService<Vibrator>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0L, 60L)
            val amplitudes = intArrayOf(0, 10)
            val effect = if (vibrator.hasAmplitudeControl())
                VibrationEffect.createWaveform(pattern, amplitudes, -1)
            else VibrationEffect.createWaveform(pattern, -1)
            vibrator.vibrate(effect)
        } else vibrator.vibrate(60L)
    }

    override fun onBackPressed() {
        if (isRecording) {
            stopVideoRecording()
            stopTimer()
            isRecording = false
            isPaused = false
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    // ——— Slow-mo playback check ———
    fun checkSlowMoFromUri(
        context: Context,
        uri: Uri,
        assumedCaptureFps: Int = 120
    ): SlowMoResult {
        val playbackFps = computePlaybackFpsFromExtractor(context, uri)
        val factor = playbackFps?.let { if (it > 0) assumedCaptureFps / it else null }
        val isSlowMo = (factor ?: 1.0) > 1.5
        return SlowMoResult(playbackFps, assumedCaptureFps, factor, isSlowMo)
    }

    fun computePlaybackFpsFromExtractor(
        context: Context,
        uri: Uri,
        sampleLimit: Int = 400
    ): Double? {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i; break
                }
            }
            if (videoTrack < 0) return null
            extractor.selectTrack(videoTrack)

            var lastPtsUs: Long? = null
            var sumDeltaUs = 0L
            var deltaCount = 0
            var samplesRead = 0
            while (samplesRead < sampleLimit) {
                val ptsUs = extractor.sampleTime
                if (ptsUs < 0) break
                lastPtsUs?.let { prev ->
                    val d = ptsUs - prev
                    if (d > 0 && d < 1_000_000) {
                        sumDeltaUs += d
                        deltaCount++
                    }
                }
                lastPtsUs = ptsUs
                extractor.advance()
                samplesRead++
            }
            if (deltaCount == 0) return null
            val avgDeltaUs = sumDeltaUs.toDouble() / deltaCount.toDouble()
            return if (avgDeltaUs > 0.0) 1_000_000.0 / avgDeltaUs else null
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }


}
