package com.example.myapplication

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO
import android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
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
import com.example.overlay.RotationLineOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class CameraRecordingActivity : ComponentActivity() {


    private var camera: Camera? = null
    private var minFocusDistance: Float = 0.6f
    private var currentZoom: Float = 1f
    private lateinit var previewView: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var cameraSelector: CameraSelector
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    lateinit var zoombutton: ImageView
    private var timerJob: Job? = null
    private var startTime: Long = 0L
    private var lastVibratedFocusIndex: Int = -1
    lateinit var videobuttonRecording: ImageButton
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo
    private var isRecording = false
    private var isPaused = false
    private var pausedTime = 0L
    private var isZoomButtonSelected = false
    lateinit var stopButton: TextView
    lateinit var flashBtn: ImageView
    private var isFlashOn = false
    private var isManualFocus = false
    lateinit var timerImage: ImageView
    lateinit var manualfocus: TextView
    lateinit var tvOPIC: OpicTextView
    lateinit var timerView: GlowingTimerView
    private var isZoomEnabled = false

    private var zoomAnimator: ValueAnimator? = null
    private var currentZoomRatio: Float = 1.2f  // Track current zoom
    lateinit var zoomRulerView: ZoomRulerView
    lateinit var zoomSwipeDetectRecyclerView: SwipeDetectRecyclerView
    lateinit var zoomControlLayout: LinearLayout
    lateinit var zoomControlAdapter: ZoomAdapterControl
    lateinit var focusScaleView: FocusRulerView

    private var lastAutoFocusDistance: Float = 0.6f

    lateinit var angleLineView: RotationLineOverlay
    private lateinit var orientationEventListener: OrientationEventListener
    private lateinit var sensorManager: SensorManager
    lateinit var li_Message: LinearLayout
    lateinit var progressDialog: AlertDialog

    @SuppressLint("MissingInflatedId", "WrongViewCast", "ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        progressDialog = showProgressDialog(this)
        setContentView(R.layout.activity_camera_recording)
        previewView = findViewById(R.id.previewView)
        videobuttonRecording = findViewById(R.id.videobutton)
        stopButton = findViewById(R.id.stopButton)
        flashBtn = findViewById(R.id.hdrIcon)
        zoombutton = findViewById(R.id.videobuttonblack)
        timerImage = findViewById(R.id.micIcon)
        manualfocus = findViewById(R.id.stopButton)
        li_Message = findViewById(R.id.llRotationMessage)
        tvOPIC = findViewById(R.id.tv_opic_spartial)
        timerView = findViewById<GlowingTimerView>(R.id.glowTimer)
        focusScaleView = findViewById(R.id.focusScaleView)
        zoomRulerView = findViewById<ZoomRulerView>(R.id.zoomRulerView)
        zoomControlLayout = findViewById<LinearLayout?>(R.id.zoomControlBg)
        zoomSwipeDetectRecyclerView =
            findViewById<SwipeDetectRecyclerView?>(R.id.zoomControlRecyclerView)
        zoomSwipeDetectRecyclerView.rulerView = zoomRulerView
        angleLineView = findViewById<RotationLineOverlay>(R.id.lineOverlay)
        val zoomLevels: MutableList<Float> = mutableListOf(5f, 4f, 3f, 2f, 1.2f, 1f)
      //  pickVideo()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        zoomControlAdapter =
            ZoomAdapterControl(zoomLevels, object : ZoomAdapterControl.OnZoomClick {
                override fun onZoomClick(ratio: Float) {
                    zoomRulerView.zoomValue = ratio
                    Log.d("ratio", "onCreate: $ratio")
                    cameraControl.setZoomRatio(ratio)
                    currentZoom = ratio
                    if (ratio == 1.2f) {
                        zoomControlAdapter.selectRatio(1.2f)
                    }
                }
            })

        val layoutManager = LinearLayoutManager(
            this, RecyclerView.VERTICAL, false // reverseLayout = false
        )
        zoomSwipeDetectRecyclerView.setLayoutManager(layoutManager)
        zoomSwipeDetectRecyclerView.setAdapter(zoomControlAdapter)

        zoomControlAdapter.selectRatio(1.2f)
        zoomRulerView.zoomValue = 1.2f

        zoomSwipeDetectRecyclerView.onSwipeUp = {
            onSwipeDown()
        }

        zoomSwipeDetectRecyclerView.onSwipeDown = {
            onSwipeDown()
        }

        val hideDelay = 3000L // 3 seconds

        var isUserSliding = false

        zoomRulerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isUserSliding = true
                    zoomControlLayout.visibility = GONE
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isUserSliding) {
                        isUserSliding = true
                        zoomControlLayout.visibility = GONE
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserSliding = false
                    zoomRulerView.postDelayed({


                        // Only show if user isn't sliding anymore
                        if (!isUserSliding && focusScaleView.isGone) {
                            zoomControlLayout.visibility = VISIBLE
                            zoomRulerView.visibility = GONE
                        }
                    }, hideDelay)
                }
            }
            false // Let ruler process zoom changes too
        }



        camera?.cameraInfo?.zoomState?.observe(this, Observer { state: ZoomState? ->
            val cur = state!!.zoomRatio
            zoomControlAdapter.selectRatio(cur)

        })

        var lastAppliedStep: Float? = null
        val stepSize = 0.1f   // ruler step size (change to your needs)

        zoomRulerView.onZoomChanged = { newZoom ->
            // Snap the new zoom to stepSize (e.g. 2.87 → 2.9)
            val steppedZoom = ((newZoom / stepSize).roundToInt() * stepSize)

            // Only update if stepped zoom actually changed
            if (lastAppliedStep != steppedZoom) {
                cameraControl.setZoomRatio(steppedZoom)
                currentZoom = steppedZoom


                vibrateOnce()  // vibrate per step change
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
            bindVideoUseCase()
        }, ContextCompat.getMainExecutor(this))


        manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
        manualfocus.setTextColor(Color.WHITE)
        zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
        zoombutton.setImageResource(R.drawable.zoomwhite)
        zoombutton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        zoombutton.setPadding(22, 22, 22, 22)

        handleClickListener()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun handleClickListener() {

        videobuttonRecording.setOnClickListener {
            Log.d("checkkSxc", "yess2")
            tvOPIC.visibility = GONE
            zoombutton.visibility = VISIBLE
            stopButton.visibility = VISIBLE

            when {
                !isRecording -> {
                    videobuttonRecording.setBackgroundResource(R.drawable.record_button_ring)
                    videobuttonRecording.setImageResource(R.drawable.recordicon)
                    // videobuttonRecording.setBackgroundColor(Color.TRANSPARENT)
                    videobuttonRecording.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    videobuttonRecording.setPadding(32, 32, 32, 32)
                    timerView.visibility = VISIBLE
                    focusScaleView.visibility = GONE
//                    timerView.isTimerActive = true

                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startVideoRecording()
                    }

                    isRecording = true
                    isPaused = false
                }

                isRecording && !isPaused -> {
                    // Stop recording completely
                    stopVideoRecording()
                    stopTimer()
                    pausedTime = 0L
                    timerView.timerText = "00:00:00"
                    tvOPIC.visibility = VISIBLE
                    zoombutton.visibility = GONE
                    stopButton.visibility = GONE
                    focusScaleView.visibility = GONE
                    zoombutton.visibility = VISIBLE
                    manualfocus.visibility = VISIBLE
                    videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
                    isRecording = false
                    isPaused = false
                }

                isRecording && isPaused -> {
                    timerView.timerText = "00:00:00"
                    pausedTime = 0L
                    startVideoRecording()
                    tvOPIC.visibility = GONE
                    zoombutton.visibility = VISIBLE
                    stopButton.visibility = VISIBLE
                    isRecording = true
                    isPaused = false
                }

            }
        }


//        selectDefaultZoom()
        hideZoomSelectorView()
//        enableAutoFocus()
        /*
                zoombutton.setOnClickListener {
                    zoombutton.setBackgroundResource(R.drawable.ring_white_color)
                    zoombutton.setImageResource(R.drawable.zoom_black)
                    manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                    manualfocus.setTextColor(Color.WHITE)
                    focusScaleView.visibility = GONE
                    isManualFocus = false
                    enableAutoFocus()

                    showZoomSelectorView()

                }
        */

        zoombutton.setOnClickListener {
            isZoomButtonSelected = !isZoomButtonSelected

            if (isZoomButtonSelected) {
                zoombutton.setBackgroundResource(R.drawable.ring_white_color)
                zoombutton.setImageResource(R.drawable.zoom_black)

                manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                manualfocus.setTextColor(Color.WHITE)

                isManualFocus = false

//                enableAutoFocus()
//                manualfocus.text = "AF"

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

        stopButton.setOnClickListener {
            stopVideoRecording()
            stopTimer()
            pausedTime = 0L // Reset timer value
            timerView.timerText = "00:00:00" // Reset UI timer
            isRecording = false
            isPaused = false
        }

        flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
        flashBtn.setImageResource(R.drawable.flash_circle)

        flashBtn.setOnClickListener {
            if (::cameraControl.isInitialized) {
                isFlashOn = !isFlashOn
                cameraControl.enableTorch(isFlashOn)
                if (isFlashOn) {
                    flashBtn.setImageResource(R.drawable.flash_on) // your "flash on" icon
                    //flashBtn.imageTintList = ColorStateList.valueOf(Color.YELLOW)
                } else {
                    flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
                    flashBtn.setImageResource(R.drawable.flash_circle)

                    // flashBtn.setImageResource(R.drawable.flash) // your "flash off" icon
                    // flashBtn.imageTintList = ColorStateList.valueOf(Color.WHITE)
                }
            }
        }

        manualfocus.setOnClickListener {
            if (!isManualFocus) {

                // Enable manual focus UI
                zoomControlLayout.visibility = GONE
                focusScaleView.visibility = VISIBLE
                zoomRulerView.visibility = GONE

                hideZoomSelectorView()
                // Hide zoom UI & disable zoom logic
                isZoomEnabled = false
                tvOPIC.visibility = GONE
                isManualFocus = true
                disableAutoFocus()

                // store auto focus camera distance

                // If AF gave us a last distance, use it as starting point for manual
                if (lastAutoFocusDistance > 0f && minFocusDistance > 0f) {
                    val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
                    val options = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            lastAutoFocusDistance
                        )
                        .build()
                    camera2Control.setCaptureRequestOptions(options)

                    // Map to slider (normalized 0..1 for your FocusRulerView)
                    val normalized = 1f - (lastAutoFocusDistance / minFocusDistance)
                    focusScaleView.focusValue = normalized

                    Log.d(
                        "AF->Manual",
                        "Starting MF at AF=$lastAutoFocusDistance (slider=$normalized)"
                    )
                }

                isZoomButtonSelected = false
                manualfocus.setBackgroundResource(R.drawable.manulafocus_bg)
                manualfocus.setTextColor(Color.BLACK)
                manualfocus.text = "MF"
                zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
                zoombutton.setImageResource(R.drawable.zoomwhite)
                zoombutton.imageTintList = null  // Optional

            } else {
                enableAutoFocus()
//                zoomControlLayout.visibility = VISIBLE
                focusScaleView.visibility = GONE
//                zoomRulerView.visibility = VISIBLE
                manualfocus.text = "AF"

//                isZoomEnabled = true  // Still off unless you re-enable above
//                isZoomButtonSelected = true
                isManualFocus = false
                tvOPIC.visibility = GONE
                manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                manualfocus.setTextColor(Color.WHITE)
//                selectDefaultZoom()
            }
        }
        checkOrientation()
    }

    private fun checkOrientation() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                // 0° and 180° → Portrait
                if ((orientation in 350..360) || (orientation in 0..10) || (orientation in 170..190)) {
                    if (!isRecording) {
                        angleLineView.visibility = View.GONE
                        li_Message.visibility = View.VISIBLE
                    }
                    // Toast.makeText(baseContext, "Portrait", Toast.LENGTH_SHORT).show()
                }
                // 90° and 270° → Landscape
                else if ((orientation in 80..100) || (orientation in 260..280)) {
                    if (!isRecording) {
                        angleLineView.visibility = View.VISIBLE
                        li_Message.visibility = View.GONE
                    }
                    //Toast.makeText(baseContext, "Landscape", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Keep it horizontal in every rotation:
        // angleLineView.angle = 0f
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

//    override fun onResume() {
//        super.onResume()
//        levelCtl = (levelCtl ?: ThreeLinesLevelController(threeLevelLinesView)).also { it.start() }
//    }
//
//    override fun onPause() {
//        levelCtl?.stop()
//        super.onPause()
//
//    }


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


    private fun selectDefaultZoom() {
        zoombutton.setBackgroundResource(R.drawable.ring_white_color)
        zoombutton.setImageResource(R.drawable.zoom_black)

        manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
        manualfocus.setTextColor(Color.WHITE)
        zoomRulerView.visibility = GONE

    }


    fun showZoomSelectorView() {

        zoomControlLayout.visibility = VISIBLE
        tvOPIC.visibility = GONE
        zoomRulerView.visibility = GONE

    }

    fun hideZoomSelectorView() {
        tvOPIC.visibility = GONE
        zoomControlLayout.visibility = GONE
        zoomRulerView.visibility = GONE

    }


    private fun hasPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val audioPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return cameraPermission == PackageManager.PERMISSION_GRANTED && audioPermission == PackageManager.PERMISSION_GRANTED


    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Restart the activity
                val pm = packageManager
                val intent = pm.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                finish()
                startActivity(intent)
                Runtime.getRuntime().exit(0) // Ensures full restart
//                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindVideoUseCase() {
        try {
            // Use Camera2Interop to configure Preview Builder
            val previewBuilder = Preview.Builder()
            val previewExtender = Camera2Interop.Extender(previewBuilder)

            if (isManualFocus) {
                // Manual focus = autofocus disabled
                previewExtender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
                )
            } else {
                // Autofocus enabled
                previewExtender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }

            // 🔑 Attach CaptureCallback to read AF updates
            previewExtender.setSessionCaptureCallback(object :
                CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)

                    val focusDistance = result.get(LENS_FOCUS_DISTANCE)
                    if (focusDistance != null && focusDistance > 0f) {
                        lastAutoFocusDistance = focusDistance
                        Log.d("AF_TRACK", "AutoFocus distance = $focusDistance")
                    }
                }
            })

            previewExtender.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO
            )

            val preview = previewBuilder.setTargetAspectRatio(AspectRatio.RATIO_16_9).build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // Use a more compatible quality selector with fallback options
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, videoCapture
            )
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
        angleLineView.visibility = View.GONE // hide line when recording starts
        val videoCapture = this.videoCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())
        val outDir = cacheVideoDir()
        if (!outDir.exists()) outDir.mkdirs()

        val outFile = File(outDir, "$name.mp4")

        val fileOutput = FileOutputOptions.Builder(outFile).build()

        pausedTime = 0L
        recording = videoCapture.output
            .prepareRecording(this, fileOutput)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        startTimer()
                    }

                    is VideoRecordEvent.Finalize -> {
                        stopTimer()
                        recording = null
                        if (!recordEvent.hasError()) {
                            // Use FileProvider (content://) from cache
                            val cacheUri = getCacheFileProviderUri(outFile)
                            val videoUri = cacheUri.toString()
                            val videoPath = cacheUri.path
//
//                            // Create result intent
//                            val resultIntent = Intent().apply {
//                                putExtra("video_uri", videoUri)
//                                putExtra("video_path", videoPath)
//                                putExtra(
//                                    "duration",
//                                    (SystemClock.elapsedRealtime() - startTime) / 1000
//                                )
//                                putExtra(
//                                    "file_size",
//                                    cacheUri.path?.let { File(it).length() } ?: 0L)
//                            }
//
//                            setResult(RESULT_OK, resultIntent)


                            // Share straight from cache (no MediaStore copy)
//                            shareVideoToWhatsAppFromCache(cacheUri)
//                            Toast.makeText(
//                                this,
//                                "Video saved to cache: " + cacheUri,
//                                Toast.LENGTH_SHORT
//                            ).show()
                            // ✅ Call the compressor function
                            Log.d("VideoCompressor", "Orignal video saved at: $cacheUri")
                            Log.d(
                                "VideoCompressor", "Orignal video size: ${
                                    getFileSize(
                                        cacheUri
                                    )
                                }"
                            )
                            lifecycleScope.launch {
                                compressVideo(cacheUri)
                                // use result
                            }


                        } else {
                            // Optionally delete a partial/corrupt file
                            if (outFile.exists()) outFile.delete()
                            setResult(RESULT_CANCELED)
                            Toast.makeText(this, "Video recording failed", Toast.LENGTH_SHORT)
                                .show()
                        }
                        finish()
                    }
                }
            }
    }

    fun pickVideo() {

        // Example: pick a video from gallery
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "video/*"
        }
        startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1 && resultCode == RESULT_OK) {
            val inputUri = data?.data ?: return
            Log.d("VideoCompressor", "Orignal video saved at: $inputUri")
            Log.d(
                "VideoCompressor", "Orignal video size: ${
                    getFileSize(
                        inputUri
                    )
                }"
            )
            lifecycleScope.launch {
                compressVideo(inputUri)
                // use result
            }

        }
    }

    private fun cacheVideoDir(): File {
        return externalCacheDir ?: cacheDir
    }

    private fun getCacheFileProviderUri(file: File): Uri {
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
    }


    private fun moveCacheVideoToGallery(src: File) {
        val resolver = contentResolver
        val name = src.nameWithoutExtension
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CameraXVideos")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        // Optionally delete the cache copy after promoting
        // src.delete()
    }

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
            Toast.makeText(
                this, "Recording stopped due to app going to background", Toast.LENGTH_SHORT
            ).show()
            isRecording = false
            isPaused = false
        }
    }

    fun getFileSize(uri: Uri): String {
        return try {
            val fileDescriptor =
                contentResolver.openFileDescriptor(uri, "r") ?: return "0 B"
            val size = fileDescriptor.statSize
            fileDescriptor.close()

            when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> String.format("%.2f KB", size / 1024f)
                else -> String.format("%.2f MB", size / (1024f * 1024f))
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun stopVideoRecording() {
        isRecording = false
        angleLineView.visibility = View.VISIBLE
        recording?.stop()
        recording = null
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
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
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
                val facing = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_FACING
                )
                return facing?.let {
                    CameraSelector.Builder().requireLensFacing(it).build()
                }
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

        Log.d("ManualFocusCheck", "Min focus distance: $minFocusDistance")

        if (minFocusDistance == null || minFocusDistance == 0f) {
            Log.w("ManualFocusCheck", "Manual focus is NOT supported on this device.")
//            focusScaleView.visibility = GONE
//            Toast.makeText(this, "Manual focus not supported", Toast.LENGTH_SHORT).show()
            return
        } else {
            Log.i(
                "ManualFocusCheck",
                "Manual focus IS supported. Min distance: $minFocusDistance"
            )
        }

        val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)


        focusScaleView.onFocusChanged = { newFocus ->
            try {
                // Map correctly: 0 = closest, 1 = infinity
                val focusDistance = (1f - newFocus) * minFocusDistance
                Log.d("FocusSet", "Slider=$newFocus → FocusDistance=$focusDistance")

                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        focusDistance
                    )
                    .build()

                camera2Control.setCaptureRequestOptions(options)

                vibrateOnce()
            } catch (e: Exception) {
                Log.e("ManualFocus", "Failed to set manual focus", e)
                Toast.makeText(
                    this,
                    "Error setting focus: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
//    }
    }

    private fun smoothZoom(from: Float, to: Float) {
        if (from == to) return  // no change, ignore

        zoomAnimator?.cancel()  // stop previous animation if still running

        zoomAnimator = ValueAnimator.ofFloat(from, to).apply {
            // ValueAnimator.setDuration = 150
            addUpdateListener {
                val zoom = it.animatedValue as Float
                camera?.cameraControl?.setZoomRatio(zoom)
                currentZoomRatio = zoom
            }
            start()
        }
    }

    private fun vibrateOnce() {
        val vibrator = getSystemService<Vibrator>() ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern =
                longArrayOf(0L, 60L)            // just one vibrate: wait 0ms, vibrate 60ms
            val amplitudes = intArrayOf(0, 10)           // light vibration

            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(pattern, amplitudes, /*repeat*/ -1)
            } else {
                // fallback, only timing:
                VibrationEffect.createWaveform(pattern, /*repeat*/ -1)
            }
            vibrator.vibrate(effect)
        } else {
            vibrator.vibrate(60L)
        }
    }

    private fun CameraRecordingActivity.onSwipeDown() {
        zoomRulerView.visibility = VISIBLE
        // No more postDelayed here
        zoomControlLayout.visibility = GONE
    }

    override fun onBackPressed() {
        // Stop recording if currently recording
        if (isRecording) {
            stopVideoRecording()
            stopTimer()
            isRecording = false
            isPaused = false
        }

        // Set result as cancelled and finish
        setResult(RESULT_CANCELED)
        finish()
    }

    fun getPath(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    val index = it.getColumnIndexOrThrow("_data")
                    if (it.moveToFirst()) {
                        return it.getString(index)
                    }
                }
                null
            }

            else -> null
        }
    }

    private suspend fun compressVideo(uri: Uri) {
        // Example inside a coroutine scope (e.g., ViewModel or lifecycleScope)
        val outputUri = compressVideoToCacheUri(this, uri)
        if (outputUri != null) {
            // use the compressed video Uri
            Log.d("VideoCompressor", "Compressed video saved at: $outputUri")
            Log.d("VideoCompressor", "Compressed video size: ${getFileSize(outputUri)}")
            val videoView = VideoView(this)
            videoView.setVideoURI(outputUri)
            videoView.start()
            setContentView(videoView)
        } else {
            Log.e("VideoCompressor", "Compression failed")
            // handle failure
        }

    }

    @OptIn(UnstableApi::class)
    suspend fun compressVideoToCacheUri(
        context: Context,
        inputUri: Uri,
        targetBitrate: Int = 4_000_000, // ~4 Mbps default
        preferHevc: Boolean = true
    ): Uri? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->

        val outputFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        val outputPath = outputFile.absolutePath
        val mediaItem = MediaItem.fromUri(inputUri)

        val videoSettings = VideoEncoderSettings.Builder()
            .setBitrate(targetBitrate)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setEnableFallback(true)
            .build()

        // CHANGE: ensure dialog work happens on Main
        val main = androidx.core.os.HandlerCompat.createAsync(Looper.getMainLooper())
        var finished = false

        progressDialog.show()
        fun finishWith(result: Uri?) {
            if (finished) return
            finished = true
            // Dismiss dialog on main
            main.post { progressDialog?.dismiss() }
            if (cont.isActive) cont.resume(result, onCancellation = null)
        }

        // CHANGE: support retry from HEVC -> AVC without dismissing early
        fun startWith(mime: String) {
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(mime)
                .build()

            // Cancel transformer if coroutine is cancelled
            cont.invokeOnCancellation {
                try {
                    transformer.cancel()
                } catch (_: Throwable) { /* no-op */
                }
                // Best effort cleanup
                outputFile.delete()
            }

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    progressDialog.dismiss()
                    finishWith(Uri.fromFile(outputFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException
                ) {
                    progressDialog.dismiss()
                    if (mime == MimeTypes.VIDEO_H265) {
                        // Retry with AVC once
                        startWith(MimeTypes.VIDEO_H264)
                    } else {
                        outputFile.delete()
                        finishWith(null)
                    }
                }
            })

            // Start transform
            try {
                transformer.start(mediaItem, outputPath)
            } catch (t: Throwable) {
                // Synchronous failure (rare but possible)
                if (mime == MimeTypes.VIDEO_H265) {
                    startWith(MimeTypes.VIDEO_H264)
                } else {
                    outputFile.delete()
                    finishWith(null)
                }
            }
        }

        startWith(if (preferHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
    }

    // Create a simple indeterminate progress dialog
    private fun showProgressDialog(context: Context): AlertDialog {
        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
        }

        return AlertDialog.Builder(context)
            .setTitle("Compressing Video")
            .setMessage("Please wait while your video is being processed...")
            .setView(progressBar)
            .setCancelable(false)  // prevent accidental dismiss
            .create()
    }

//    private fun saveVideoToGallery(
//        context: Context,
//        sourceUri: Uri,                 // the compressed cache Uri you already return
//        albumName: String = "OPIC"     // folder under Movies/
//    ): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//        saveToGalleryQPlus(context, sourceUri, albumName)
//    } else {
//        saveToGalleryLegacy(context, sourceUri, albumName)
//    }
//
//    @RequiresApi(Build.VERSION_CODES.Q)
//    private fun saveToGalleryQPlus(
//        context: Context,
//        sourceUri: Uri,
//        albumName: String
//    ): Uri? {
//        val resolver = context.contentResolver
//        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
//        val displayName = "VID_${System.currentTimeMillis()}.mp4"
//
//        val values = ContentValues().apply {
//            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
//            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
//            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/$albumName")
//            put(MediaStore.Video.Media.IS_PENDING, 1)
//            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
//            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
//        }
//
//        val destUri = resolver.insert(collection, values) ?: return null
//        try {
//            resolver.openOutputStream(destUri)?.use { out ->
//                resolver.openInputStream(sourceUri)?.use { inp ->
//                    inp.copyTo(out)
//                }
//            }
//            // Mark as finalized
//            values.clear()
//            values.put(MediaStore.Video.Media.IS_PENDING, 0)
//            resolver.update(destUri, values, null, null)
//            return destUri
//        } catch (e: Exception) {
//            // Cleanup on failure
//            resolver.delete(destUri, null, null)
//            return null
//        }
//    }
//
//    @Suppress("DEPRECATION")
//    private fun saveToGalleryLegacy(
//        context: Context,
//        sourceUri: Uri,
//        albumName: String
//    ): Uri? {
//        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
//        val album = File(movies, albumName).apply { if (!exists()) mkdirs() }
//        val destFile = File(album, "VID_${System.currentTimeMillis()}.mp4")
//
//        return try {
//            context.contentResolver.openInputStream(sourceUri)?.use { inp ->
//                FileOutputStream(destFile).use { out -> inp.copyTo(out) }
//            }
//            // Tell MediaScanner
//            MediaScannerConnection.scanFile(
//                context,
//                arrayOf(destFile.absolutePath),
//                arrayOf("video/mp4"),
//            ) { _, scannedUri -> /* scannedUri is the gallery Uri */ }
//
//            // Build a Uri for consistency
//            Uri.fromFile(destFile)
//        } catch (_: Exception) {
//            null
//        }
//    }
//
//    @OptIn(UnstableApi::class)
//    fun compressVideoToCacheUri(
//        context: Context,
//        inputUri: Uri,
//        targetBitrate: Int = 4_000_000,
//        preferHevc: Boolean = true,
//        albumName: String = "OPIC",              // <-- ADD: desired gallery subfolder under Movies/
//        onComplete: (Uri?) -> Unit                // returns the *Gallery* Uri now
//    ) {
//        val outputFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
//        val outputPath = outputFile.absolutePath
//        val mediaItem = MediaItem.fromUri(inputUri)
//
//        val videoSettings = VideoEncoderSettings.Builder()
//            .setBitrate(targetBitrate)
//            .build()
//
//        val encoderFactory = DefaultEncoderFactory.Builder(context)
//            .setRequestedVideoEncoderSettings(videoSettings)
//            .setEnableFallback(true)
//            .build()
//        // Show dialog before starting
//       // progressDialog.show()
//        fun startWith(mime: String) {
//            val transformer = Transformer.Builder(context)
//                .setEncoderFactory(encoderFactory)
//                .setVideoMimeType(mime)
//                .build()
//
//            transformer.addListener(object : Transformer.Listener {
//                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
//                    // -- ADD: Save compressed cache file to Gallery (MediaStore)
//                    val cacheUri = Uri.fromFile(outputFile)
//                    val galleryUri = saveVideoToGallery(context, cacheUri, albumName)
////                    try {
////                        progressDialog.dismiss()
////                    } catch (e: Exception) {
////                        e.printStackTrace()
////                    }
//
//                    // Toast.makeText(context, "Video saved to Movie/OPIC", Toast.LENGTH_SHORT).show()
//                    // Optionally remove the cache file after copying:
//                    runCatching { outputFile.delete() }
//                    onComplete(galleryUri)
//                }
//
//                override fun onError(
//                    composition: Composition,
//                    exportResult: ExportResult,
//                    exception: ExportException
//                ) {
//                   // progressDialog.dismiss()
//                    if (mime == MimeTypes.VIDEO_H265) {
//                        startWith(MimeTypes.VIDEO_H264)
//                    } else {
//                        runCatching { outputFile.delete() }
//                        onComplete(null)
//                    }
//                }
//            })
//
//            transformer.start(mediaItem, outputPath)
//        }
//
//        startWith(if (preferHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
//    }
}
