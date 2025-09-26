package com.example.myapplication

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO
import android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE
import android.hardware.camera2.TotalCaptureResult
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
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
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
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Observer
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
import com.example.myapplication.controllers.HybridSlowMoController
import com.example.myapplication.controllers.ModeSelectorController
import com.example.myapplication.controllers.ZoomAdapterControl
import com.example.myapplication.helper.ImageSplitter
import com.example.myapplication.model.SlowMoOption
import com.example.myapplication.model.listBackCameraSlowMoOptions
import com.example.myapplication.views.GlowingTimerView
import com.example.myapplication.views.RotationLineOverlay
import com.example.myapplication.views.ZoomRulerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class CameraRecordingActivity : ComponentActivity() {

    // ——— CameraX instance state ———
    private var camera: Camera? = null
    private lateinit var previewView: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var cameraSelector: CameraSelector
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo

    // ——— UI ———
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
    private lateinit var modeController: ModeSelectorController

    // ——— Mode & state flags ———
    private enum class CaptureMode { PHOTO, VIDEO }

    private var captureMode: CaptureMode = CaptureMode.VIDEO
    private var isRecording = false
    private var isPaused = false
    private var isFlashOn = false
    private var isManualFocus = false
    private var isZoomEnabled = false
    private var isZoomButtonSelected = false
    private var isRotated = false
    var isSoundOn = true

    // slow-mo single source of truth:
    var isSlowMo = false

    // ——— Slow-mo controller and options ———
    private lateinit var controller: HybridSlowMoController
    private var options: List<SlowMoOption> = emptyList()
    private var selectedOption: SlowMoOption? = null

    // ——— Focus / zoom ———
    private var minFocusDistance: Float = 0.6f
    private var currentZoom: Float = 1f
    private var currentZoomRatio: Float = 1.2f
    private var lastAutoFocusDistance: Float = 0.6f
    private var lastSelectedFocusDistance: Float = 0.6f
    var lastSelectedFocus: Float = 0.08f
    private var lastMFAutoFocusDistance: Float = 9.2f
    var zoomValue = 1.2f
    private var zoomAnimator: ValueAnimator? = null
    var lastAppliedStep: Float? = null

    // ——— Timer ———
    private var timerJob: Job? = null
    private var startTime: Long = 0L
    private var pausedTime = 0L

    // ——— Sensors ———
    private lateinit var orientationEventListener: OrientationEventListener
    private lateinit var sensorManager: SensorManager
    private var currentPreview: Preview? = null
    private var rebindJob: Job? = null
    @Volatile private var isRebinding = false
    // ——— Media sounds ———
    private val shutter by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    private val mediaSounds by lazy {
        MediaActionSound().apply {
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    // ——— Photo use case ———
    private var imageCapture: ImageCapture? = null

    // ——— Pick image (gallery) ———
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) cropImage(uri)
        }

    // ——— Activity lifecycle ———

    @SuppressLint("MissingInflatedId", "WrongViewCast", "ClickableViewAccessibility")
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

        // ——— find views ———
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
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        // ——— basic UI setup ———
        updateUiForMode()
        findViewById<TextView>(R.id.pickImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Move your Mode selector RV if needed
        val rv = findViewById<RecyclerView>(R.id.rvMode)
        val marginStartPx = 260.dp(rv.context)
        rv.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            MarginLayoutParamsCompat.setMarginStart(this, marginStartPx)
        }
        (rv.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = marginStartPx
        rv.requestLayout()

        // ——— ModeSelector ———
        modeController = ModeSelectorController(rv) { index ->
            // Your mapping: index==0 normal video, index==1 photo, else slow-mo
            if (index == 1) {
                captureMode = CaptureMode.PHOTO
            } else {
                isSlowMo = index != 0
                captureMode = CaptureMode.VIDEO
            }
            spnOptions.isEnabled = isSlowMo
            toggleMode()
        }

        controller = HybridSlowMoController(this, this, previewView)

        initAfterPermissions()
        setSwiperMovementHandler()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // ——— Zoom control list ———
        val zoomLevels: MutableList<Float> = mutableListOf(5f, 4f, 3f, 2f, 1.2f, 1f)
        zoomControlAdapter =
            ZoomAdapterControl(zoomLevels, object : ZoomAdapterControl.OnZoomClick {
                override fun onZoomClick(ratio: Float) {
                    zoomRulerView.zoomValue = ratio
                    isZoomButtonSelected = true
                    zoomValue = ratio
                    cameraControl.setZoomRatio(ratio)
                    currentZoom = ratio
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
                    isUserSliding = true; zoomControlLayout.visibility = GONE
                }

                MotionEvent.ACTION_MOVE -> if (!isUserSliding) {
                    isUserSliding = true; zoomControlLayout.visibility = GONE
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserSliding = false
                    zoomRulerView.postDelayed({
                        isZoomButtonSelected = true
                        zoomValue = lastAppliedStep ?: 0.0f
                        if (!isUserSliding && focusScaleView.isGone) {
                            zoomControlLayout.visibility = VISIBLE
                            zoomRulerView.visibility = GONE
                        }
                    }, hideDelay)
                }
            }
            false
        }

        camera?.cameraInfo?.zoomState?.observe(this, Observer { state: ZoomState? ->
            state?.zoomRatio?.let { zoomControlAdapter.selectRatio(it) }
        })

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
                camera?.cameraControl?.setZoomRatio(steppedZoom)
                currentZoom = steppedZoom
                vibrateOnce()
                zoomControlAdapter.updateSingleZoomStep(newZoom.roundToInt(), newZoom)
                lastAppliedStep = steppedZoom
                zoomRulerView.zoomValue = steppedZoom
            }
        }

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 101
            )
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            cameraSelector =
                getFilteredBackCameraSelector(cameraProvider) ?: CameraSelector.DEFAULT_BACK_CAMERA
            bindUseCasesForCurrentMode()
        }, ContextCompat.getMainExecutor(this))

        // Buttons look
        manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
        manualfocus.setTextColor(Color.WHITE)
        zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
        zoombutton.setImageResource(R.drawable.zoomwhite)
        zoombutton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        zoombutton.setPadding(22, 22, 22, 22)

        handleClickListener()
    }

    // ——— Central rebind logic (slow vs normal) ———
    // CHANGE: make rebindForCurrentMode() serialized
    private fun rebindForCurrentMode() {
        if (!::cameraProvider.isInitialized) return
        if (isRebinding) return
        isRebinding = true

        rebindJob?.cancel()
        rebindJob = lifecycleScope.launch {
            try {
                // Small debounce to collapse rapid UI triggers (mode switch + spinner)
                delay(150)

                if (captureMode == CaptureMode.PHOTO) {
                    controller.release()                 // ensure Camera2 fully closed
                    cameraProvider.unbindAll()
                    detachPreviewFromCameraX(currentPreview)
                    // Let PreviewView release surface before binding
                    delay(150)
                    bindPhotoUseCase()
                    return@launch
                }

                if (isSlowMo) {
                    if (options.isEmpty()) {
                        Toast.makeText(this@CameraRecordingActivity, "No slow-mo options found!", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    if (selectedOption == null) selectedOption = options.first()

                    // FULL tear-down of CameraX before Camera2
                    controller.release()
                    cameraProvider.unbindAll()
                    detachPreviewFromCameraX(currentPreview)
                    // Give EGL/Surface a moment to detach
                    delay(250)

                    // Bind Camera2 high-speed
                    controller.bind(
                        selectedOption!!,
                        onError = { e ->
                            Log.e("HybridSlowMo", "Bind error", e)
                            // Fallback gracefully to CameraX if Camera2 HS fails
                            isSlowMo = false
                            runOnUiThread {
                                modeController.setIndex(0)
                                toggleMode()
                                Toast.makeText(this@CameraRecordingActivity, "Slow-mo unsupported: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                    controller.onTooDark = {
                        val sixty = options.firstOrNull { it.fpsRange.upper == 60 }
                        if (sixty != null) {
                            runOnUiThread {
                                selectedOption = sixty
                                isSlowMo = true
                                rebindForCurrentMode()
                                Toast.makeText(this@CameraRecordingActivity, "Low light — switched to 60 fps for brightness", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    controller.release()
                    cameraProvider.unbindAll()
                    detachPreviewFromCameraX(currentPreview)
                    delay(150)
                    bindVideoUseCase()
                }
            } finally {
                isRebinding = false
            }
        }
    }


    private fun detachPreviewFromCameraX(preview: Preview?) {
        try {
            preview?.setSurfaceProvider(null)
        } catch (_: Throwable) {}

    }
    private fun toggleMode() {
        if (isSlowMo && captureMode == CaptureMode.VIDEO) {
            spnOptions.visibility = VISIBLE
        } else {
            spnOptions.visibility = GONE
        }
        updateUiForMode()
        rebindForCurrentMode()
        vibrateOnce()
    }

    private fun setSwiperMovementHandler() {
        var startY = 0f

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val deltaY = event.y - startY
                    if (deltaY > 120) {
                        if (captureMode == CaptureMode.VIDEO) {
                            // Swipe down → PHOTO
                            captureMode = CaptureMode.PHOTO
                            isSlowMo = false
                            modeController.setIndex(1) // assuming 0=video, 1=photo, 2=slow
                        } else {
                            // Swipe down → PHOTO
                            captureMode = CaptureMode.VIDEO
                            isSlowMo = true
                            modeController.setIndex(2) // assuming 0=video, 1=photo, 2=slow
                        }

                        toggleMode()
                    } else if (deltaY < -120) {
                        // Swipe up → VIDEO (normal → slow-mo)
                        if (captureMode == CaptureMode.VIDEO && isSlowMo) {
                            // Swipe down → PHOTO
                            captureMode = CaptureMode.PHOTO
                            isSlowMo = false
                            modeController.setIndex(1) // assuming 0=video, 1=photo, 2=slow
                        } else {
                            // From PHOTO or slow → go to VIDEO normal
                            captureMode = CaptureMode.VIDEO
                            isSlowMo = false
                            modeController.setIndex(0)
                        }
                        toggleMode()
                    }
                    true
                }

                else -> false
            }
        }
    }


    @OptIn(ExperimentalCamera2Interop::class)
    private fun handleClickListener() {
        videobuttonRecording.setOnClickListener {
            when (captureMode) {
                CaptureMode.PHOTO -> if (!isRotated) takePhoto()
                CaptureMode.VIDEO -> if (!isRotated) {
                    zoombutton.visibility = VISIBLE
                    when {
                        !isRecording -> {
                            videoPauseResume.visibility = VISIBLE
                            manualfocus.visibility = GONE
                            videobuttonRecording.setBackgroundResource(R.drawable.record_button_ring)
                            videobuttonRecording.setImageResource(R.drawable.recordicon)
                            videobuttonRecording.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            videobuttonRecording.setPadding(32, 32, 32, 32)
                            timerView.visibility = VISIBLE
                            focusScaleView.visibility = GONE

                            if (ActivityCompat.checkSelfPermission(
                                    this, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) startVideoRecording()

                            isRecording = true
                            isPaused = false
                        }

                        isRecording && !isPaused -> {
                            if (isSlowMo) {
                                controller.stopRecording(
                                    onSaved = { uri ->
                                        isRecording = false
                                        Log.d("SlowMoTest", "Finalized video = $uri")
                                        playBack(uri)
                                    },
                                    onError = { e ->
                                        isRecording = false
                                        Log.e("SlowMoTest", "Stop error", e)
                                    }
                                )
                            } else {
                                stopVideoRecording()
                            }
                            videoPauseResume.visibility = GONE
                            manualfocus.visibility = VISIBLE
                            stopTimer()
                            pausedTime = 0L
                            timerView.timerText = "00:00:00"
                            zoombutton.visibility = GONE
                            focusScaleView.visibility = GONE
                            zoombutton.visibility = VISIBLE
                            videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
                            isRecording = false
                            isPaused = false
                        }

                        isRecording && isPaused -> {
                            timerView.timerText = "00:00:00"
                            pausedTime = 0L
                            startVideoRecording()
                            zoombutton.visibility = VISIBLE
                            isRecording = true
                            isPaused = false
                        }
                    }
                }
            }
        }

        videoPauseResume.setOnClickListener {
            if (isRotated) return@setOnClickListener
            zoombutton.visibility = VISIBLE
            focusScaleView.visibility = GONE
            when {
                isRecording && !isPaused -> pauseVideoRecording()
                isRecording && isPaused -> resumeVideoRecording()
            }
        }

        videobuttonRecording.setOnLongClickListener {
            if (isRecording) {
                stopVideoRecording()
                stopTimer()
                pausedTime = 0L
                timerView.timerText = "00:00:00"
                zoombutton.visibility = GONE
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
                zoomControlLayout.visibility = GONE
                focusScaleView.visibility = GONE
                showZoomSelectorView()
            } else {
                zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
                zoombutton.setImageResource(R.drawable.zoomwhite)
                zoomControlLayout.visibility = VISIBLE
                focusScaleView.visibility = GONE
                isManualFocus = false
                hideZoomSelectorView()
            }
        }

        flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
        flashBtn.setImageResource(R.drawable.flash_circle)
        flashBtn.setOnClickListener {
            if (::cameraControl.isInitialized) {
                isFlashOn = !isFlashOn
                cameraControl.enableTorch(isFlashOn)
                if (isFlashOn) {
                    flashBtn.setImageResource(R.drawable.flash_on)
                } else {
                    flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
                    flashBtn.setImageResource(R.drawable.flash_circle)
                }
            }
        }

        manualfocus.setOnClickListener {
            if (!isManualFocus) {
                zoomControlLayout.visibility = GONE
                focusScaleView.visibility = VISIBLE
                zoomRulerView.visibility = GONE
                isZoomEnabled = false
                isManualFocus = true
                disableAutoFocus()

                if (lastAutoFocusDistance > 0f && minFocusDistance > 0f) {
                    val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
                    val options = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            lastMFAutoFocusDistance
                        )
                        .build()
                    camera2Control.setCaptureRequestOptions(options)

                    val normalized = 1f - (lastMFAutoFocusDistance / minFocusDistance)
                    focusScaleView.focusValue = lastSelectedFocus
                    Log.d(
                        "FocusSet",
                        "Starting MF at AF=$lastAutoFocusDistance MF=$lastMFAutoFocusDistance (slider=$normalized)"
                    )
                }

                isZoomButtonSelected = false
                manualfocus.setBackgroundResource(R.drawable.manulafocus_bg)
                manualfocus.setTextColor(Color.BLACK)
                manualfocus.text = "MF"
                zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
                zoombutton.setImageResource(R.drawable.zoomwhite)
                zoombutton.imageTintList = null
            } else {
                enableAutoFocus()
                focusScaleView.visibility = GONE
                manualfocus.text = "AF"
                isManualFocus = false
                manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                manualfocus.setTextColor(Color.WHITE)
            }
        }

        checkOrientation()
    }

    private fun playBack(outputUri: Uri) {
        val vv = VideoView(this)
        vv.setVideoURI(outputUri)
        vv.start()
        setContentView(vv)
    }

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
        super.onDestroy()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun enableAutoFocus() {
        val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            .build()
        camera2Control.setCaptureRequestOptions(options)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun disableAutoFocus() {
        val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            .build()
        camera2Control.setCaptureRequestOptions(options)
    }

    fun showZoomSelectorView() {
        zoomControlLayout.visibility = VISIBLE
        zoomRulerView.visibility = GONE
    }

    fun hideZoomSelectorView() {
        zoomControlLayout.visibility = GONE
        zoomRulerView.visibility = GONE
    }

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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindVideoUseCase() {
        try {
            currentPreview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val previewBuilder = Preview.Builder()
            val previewExtender = Camera2Interop.Extender(previewBuilder)

            if (isManualFocus) {
                previewExtender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
            } else {
                previewExtender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }

            previewExtender.setSessionCaptureCallback(object :
                CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    result.get(LENS_FOCUS_DISTANCE)?.let { fd ->
                        if (fd > 0f) {
                            lastAutoFocusDistance = fd
                            if (isManualFocus) lastMFAutoFocusDistance = fd
                        }
                    }
                }
            })

            previewExtender.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )

            val preview = previewBuilder.setTargetAspectRatio(AspectRatio.RATIO_16_9).build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            videoCapture = VideoCapture.withOutput(recorder)

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            cameraControl = camera!!.cameraControl
            cameraInfo = camera!!.cameraInfo

            cameraControl.setZoomRatio(1.2f)
            setupManualFocusRecyclerView()
        } catch (e: Exception) {
            Log.e("CameraRecording", "Error binding video use case", e)
            Toast.makeText(this, "Failed to initialize camera: ${e.message}", Toast.LENGTH_LONG)
                .show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startVideoRecording() {
        isRecording = true
        li_Message.visibility = View.GONE
        val vc = this.videoCapture

        if (isSlowMo) {
            // Guard: if controller hasn’t finished binding yet, bail
            if (!controller.isReady()) {  // ADD: expose isReady() in your controller
                isRecording = false
                Toast.makeText(this, "Slow-mo not ready yet", Toast.LENGTH_SHORT).show()
                return
            }
            controller.startRecording(
                onStarted = {
                    isRecording = true
                },
                onSaved = { uri -> playBack(uri) },
                onError = { e ->
                    isRecording = false
                    Log.e("SlowMoTest", "Recording error", e)
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
            if (isSoundOn) mediaSounds.play(MediaActionSound.START_VIDEO_RECORDING)
            pausedTime = 0L
            startTimer()
            return
        }

        // Normal recording
        val name =
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(System.currentTimeMillis())
        val outFile = File(cacheVideoDir(), "$name.mp4").apply { parentFile?.mkdirs() }
        val fileOutput = FileOutputOptions.Builder(outFile).build()

        vc ?: return
        pausedTime = 0L
        recording = vc.output
            .prepareRecording(this, fileOutput)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        pausedTime = 0L
                        startTimer()
                        isRecording = true
                        isPaused = false
                        if (isSoundOn) mediaSounds.play(MediaActionSound.START_VIDEO_RECORDING)
                    }

                    is VideoRecordEvent.Pause -> if (!isPaused) pauseVideoRecording()
                    is VideoRecordEvent.Resume -> if (isPaused) resumeVideoRecording()
                    is VideoRecordEvent.Finalize -> {
                        stopTimer()
                        recording = null
                        isRecording = false
                        isPaused = false
                        if (isSoundOn) mediaSounds.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        if (!recordEvent.hasError()) {
                            val cacheUri = getCacheFileProviderUri(outFile)
                            Log.d("VideoCompressor", "Original video saved at: $cacheUri")
                            Log.d("VideoCompressor", "Original size: ${getFileSize(cacheUri)}")
                            lifecycleScope.launch { compressVideo(cacheUri) }
                        } else {
                            if (outFile.exists()) outFile.delete()
                            setResult(RESULT_CANCELED)
                            Toast.makeText(this, "Video recording failed", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            }
    }

    fun pickVideo() {
        val intent = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
        startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val inputUri = data?.data ?: return
            Log.d("VideoCompressor", "Original video: $inputUri  size=${getFileSize(inputUri)}")
            lifecycleScope.launch { compressVideo(inputUri) }
        }
    }

    private fun cacheVideoDir(): File = externalCacheDir ?: cacheDir
    private fun getCacheFileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

    private fun pruneOldCacheVideos(days: Int = 7) {
        val dir = cacheVideoDir()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) {
            stopVideoRecording()
            Toast.makeText(this, "Recording stopped (app backgrounded)", Toast.LENGTH_SHORT).show()
            isRecording = false
            isPaused = false
        }
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

    private fun stopVideoRecording() {
        isRecording = false
        isPaused = false
        angleLineView.visibility = View.VISIBLE
        recording?.stop()
        recording = null
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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun getFilteredBackCameraSelector(cameraProvider: ProcessCameraProvider): CameraSelector? {
        for (cameraInfo in cameraProvider.availableCameraInfos) {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val focalLengths = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: continue
            val focal = focalLengths.firstOrNull() ?: continue
            if (focal in 20f..70f) {
                val facing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                return facing?.let { CameraSelector.Builder().requireLensFacing(it).build() }
            }
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setupManualFocusRecyclerView() {
        val camera2Info = Camera2CameraInfo.from(camera!!.cameraInfo)
        minFocusDistance = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f

        if (minFocusDistance == 0f) {
            Log.w("ManualFocus", "Manual focus NOT supported.")
            return
        }

        val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
        focusScaleView.onFocusChanged = { newFocus ->
            try {
                val focusDistance = (1f - newFocus) * minFocusDistance
                lastSelectedFocus = newFocus
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                    .build()
                camera2Control.setCaptureRequestOptions(options)
                vibrateOnce()
            } catch (e: Exception) {
                Log.e("ManualFocus", "Failed to set manual focus", e)
                Toast.makeText(this, "Error setting focus: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun smoothZoom(from: Float, to: Float) {
        if (from == to) return
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(from, to).apply {
            addUpdateListener {
                val z = it.animatedValue as Float
                camera?.cameraControl?.setZoomRatio(z)
                currentZoomRatio = z
            }
            start()
        }
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

    private fun CameraRecordingActivity.onSwipeDown() {
        zoomRulerView.visibility = VISIBLE
        zoomControlLayout.visibility = GONE
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

    fun getPath(context: Context, uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path
        "content" -> {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val index = it.getColumnIndexOrThrow("_data")
                if (it.moveToFirst()) it.getString(index) else null
            }
        }

        else -> null
    }

    @OptIn(UnstableApi::class)
    private suspend fun compressVideo(uri: Uri) {
        val outputUri = compressVideoToCacheUri(this, uri)
        if (outputUri != null) {
            Log.d("VideoCompressor", "Compressed at: $outputUri size=${getFileSize(outputUri)}")
            playBack(uri)
        } else {
            Log.e("VideoCompressor", "Compression failed")
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun compressVideoToCacheUri(
        context: Context,
        inputUri: Uri,
        targetBitrate: Int = 4_000_000,
        preferHevc: Boolean = true
    ): Uri? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
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

    private fun updateUiForMode() {
        // EXISTING
        timerView.visibility = if (captureMode == CaptureMode.VIDEO) VISIBLE else GONE
        if (captureMode == CaptureMode.PHOTO) {
            videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
            videobuttonRecording.setImageResource(R.drawable.ic_camera)
            timerView.isTimerRunning = false
        } else {
            videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
            videobuttonRecording.setImageResource(R.drawable.recordicon)
        }

        // ADD: in slow-mo, hide/disable manual focus and any CameraX-only toggles
      //  manualfocus.isEnabled = !isSlowMo
      //  manualfocus.visibility = if (isSlowMo) GONE else VISIBLE
        flashBtn.isEnabled = !isSlowMo
    }


    private fun bindUseCasesForCurrentMode() {
        rebindForCurrentMode()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindPhotoUseCase() {
        try {
            val previewBuilder = Preview.Builder()
            val previewExt = Camera2Interop.Extender(previewBuilder)
            if (isManualFocus) {
                previewExt.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
            } else {
                previewExt.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }
            previewExt.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )

            val preview = previewBuilder.setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also { it.surfaceProvider = previewView.surfaceProvider }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            cameraControl = camera!!.cameraControl
            cameraInfo = camera!!.cameraInfo
            cameraControl.setZoomRatio(1.2f)
            setupManualFocusRecyclerView()
        } catch (e: Exception) {
            Log.e("CameraRecording", "Error binding photo use case", e)
            Toast.makeText(this, "Failed to init photo mode: ${e.message}", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun takePhoto() {
        val imageCapture = this.imageCapture ?: return
        val outDir = externalCacheDir ?: cacheDir
        if (!outDir.exists()) outDir.mkdirs()
        val name =
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(System.currentTimeMillis())
        val outFile = File(outDir, "IMG_$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (isSoundOn) shutter.play(MediaActionSound.SHUTTER_CLICK)
                    val cacheUri = FileProvider.getUriForFile(
                        this@CameraRecordingActivity,
                        "${packageName}.fileprovider",
                        outFile
                    )
                    lifecycleScope.launch { delay(300); cropImage(cacheUri) }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exc.message}", exc)
                }
            }
        )
    }

    private fun pauseVideoRecording() {
        recording?.pause()
        pausedTime = SystemClock.elapsedRealtime() - startTime
        stopTimer()
        isPaused = true
        videoPauseResume.setBackgroundResource(R.drawable.play_icon)
    }

    private fun resumeVideoRecording() {
        recording?.resume()
        resumeTimer()
        isPaused = false
        videoPauseResume.setBackgroundResource(R.drawable.pause)
    }

    fun cropImage(imageUri: Uri) {
        val leftUri: Uri = ImageSplitter.splitHalfToUri(this, imageUri, ImageSplitter.Side.LEFT)
        val rightUri: Uri = ImageSplitter.splitHalfToUri(this, imageUri, ImageSplitter.Side.RIGHT)
        val imageView = ImageView(this)
        imageView.setImageURI(leftUri)
        setContentView(imageView)
    }

    private fun initAfterPermissions() {
        lifecycleScope.launch {
            options =
                withContext(Dispatchers.Default) {listBackCameraSlowMoOptions(this@CameraRecordingActivity, 60)}
            if (options.isEmpty()) {
                Toast.makeText(
                    this@CameraRecordingActivity,
                    "No slow-mo options found",
                    Toast.LENGTH_LONG
                ).show()
            }
            val labels = options.map { it.label }
            // after you load `options` list
            val slowMoAdapter = com.example.myapplication.adapters.SlowMoOptionAdapter(
                this@CameraRecordingActivity,
                options
            )

// Use custom row resources
            spnOptions.adapter = slowMoAdapter
            spnOptions.setPopupBackgroundResource(R.drawable.bg_dropdown_popup)
// (Optional) narrow/widen popup to content
//            spnOptions.dropDownVerticalOffset = 8.dp(this@CameraRecordingActivity)
//            spnOptions.dropDownWidth = (240 * resources.displayMetrics.density).toInt()

// Enable/disable spinner with your mode:
            spnOptions.isEnabled = isSlowMo

            spnOptions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!isSlowMo) return
                    val newOpt = options[position]
                    if (selectedOption === newOpt) return // ADD: no-op if same
                    selectedOption = newOpt
                    rebindForCurrentMode()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            if (options.isNotEmpty()) spnOptions.setSelection(0)
            rebindForCurrentMode()
        }
    }

    // Pretty progress dialog (indeterminate)
    private fun showProgressDialog(context: Context): AlertDialog = showPrettyProgressDialog(this)

    // dp extension
    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).roundToInt()
}
