package com.example.myapplication



import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.recyclerview.widget.LinearSnapHelper
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

class CameraRecordingActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var cameraSelector: CameraSelector

    //lateinit var timerText:TextView
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var isPhotoMode = true
    private var recording: Recording? = null
    private var recordedVideoUri: Uri? = null

    //lateinit var videoButton:ImageButton
    lateinit var zoombutton: ImageButton
    private var timerJob: Job? = null
    private var startTime: Long = 0L
    private var lastVibratedFocusIndex: Int = -1

    //lateinit var playButton:ImageButton
    lateinit var videobuttonRecording: ImageButton

    //lateinit var photoButton:ImageButton
    private lateinit var camera: Camera
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo
    private lateinit var outputDirectory: File
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
    lateinit var li_Zoom: LinearLayout
    lateinit var angleLineView: RotationLineOverlay
    lateinit var tvOPIC: OpicTextView
    lateinit var timerView: GlowingTimerView
    private var isManualFocusEnabled = false
    lateinit var overlay: View
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var isZoomEnabled = true
    private lateinit var sensorManager: SensorManager

    private lateinit var orientationEventListener: OrientationEventListener

    lateinit var li_Message: LinearLayout

    @SuppressLint("MissingInflatedId", "WrongViewCast")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_camera_recording)

        previewView = findViewById(R.id.previewView)
        videobuttonRecording = findViewById(R.id.videobutton)
        stopButton = findViewById(R.id.stopButton)
        flashBtn = findViewById(R.id.hdrIcon)
        zoombutton = findViewById(R.id.videobuttonblack)
        li_Zoom = findViewById(R.id.li_Zoom)
        li_Message = findViewById(R.id.llRotationMessage)
        timerImage = findViewById(R.id.micIcon)
        manualfocus = findViewById(R.id.stopButton)
        tvOPIC = findViewById(R.id.tv_opic_spartial)
        //tvOPIC = findViewById<TextView>(R.id.tv_opic_spartial)
        timerView = findViewById<GlowingTimerView>(R.id.glowTimer)
        overlay = findViewById<View>(R.id.zoomDragOverlay)
        // timerView.timerText = "00:01:23"
        angleLineView = findViewById<RotationLineOverlay>(R.id.lineOverlay)
        // pickVideo()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pruneOldCacheVideos(2)
        val recyclerView = findViewById<RecyclerView>(R.id.zoomRecyclerView)
        // setupZoomRecyclerView()
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(

                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                101

            )

        }


        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            //cameraSelector = getFilteredBackCameraSelector(cameraProvider)
            cameraSelector =
                getFilteredBackCameraSelector(cameraProvider) ?: CameraSelector.DEFAULT_BACK_CAMERA

            bindVideoUseCase()
        }, ContextCompat.getMainExecutor(this))

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
                    timerView.visibility = View.VISIBLE
                    recyclerView.visibility = GONE
                    zoombutton.visibility = GONE
                    manualfocus.visibility = GONE

                    startVideoRecording()
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
                    recyclerView.visibility = GONE
                    zoombutton.visibility = VISIBLE
                    manualfocus.visibility = VISIBLE
                    li_Zoom.visibility = GONE
                    videobuttonRecording.setBackgroundResource(R.drawable.circle_button_bg)
                    isRecording = false
                    isPaused = false
                }

                isRecording && isPaused -> {
                    // Start fresh recording
                    //   playButton.setImageResource(R.drawable.pause)
                    timerView.timerText = "00:00:00"
                    pausedTime = 0L
                    startVideoRecording()
                    tvOPIC.visibility = GONE
                    li_Zoom.visibility = GONE
                    zoombutton.visibility = VISIBLE
                    stopButton.visibility = VISIBLE
                    isRecording = true
                    isPaused = false
                }

            }
        }
        manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
        manualfocus.setTextColor(Color.WHITE)
        zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
        zoombutton.setImageResource(R.drawable.zoomwhite)

        zoombutton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        zoombutton.setPadding(44, 44, 44, 44)


        zoombutton.setOnClickListener {
            isZoomButtonSelected = !isZoomButtonSelected
            if (isZoomButtonSelected) {
                zoombutton.setBackgroundResource(R.drawable.ring_white_color)
                zoombutton.setImageResource(R.drawable.zoom_black)

                manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                manualfocus.setTextColor(Color.WHITE)
                tvOPIC.visibility = GONE
                overlay.visibility = VISIBLE
                overlay.isEnabled = true
                li_Zoom.visibility = VISIBLE
                li_Zoom.isEnabled = false
                li_Zoom.isClickable = false
                recyclerView.visibility = GONE

                // Optional: cameraControl.setZoomRatio(2.0f)
            } else {

                zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
                zoombutton.setImageResource(R.drawable.zoomwhite)

                tvOPIC.visibility = GONE
                li_Zoom.visibility = GONE
                li_Zoom.isEnabled = false
                li_Zoom.isClickable = false
                overlay.visibility = GONE
                overlay.isEnabled = false

                // Optional: cameraControl.setZoomRatio(1.0f)
            }

        }

        stopButton.setOnClickListener {
            stopVideoRecording()
            stopTimer()
            pausedTime = 0L // Reset timer value
            timerView.timerText = "00:00:00" // Reset UI timer
            //playButton.setImageResource(R.drawable.playbutton)
            isRecording = false
            isPaused = false
        }

        flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
        flashBtn.setImageResource(R.drawable.flash3)

        flashBtn.setOnClickListener {
            if (::cameraControl.isInitialized) {
                isFlashOn = !isFlashOn
                cameraControl.enableTorch(isFlashOn)
                if (isFlashOn) {
                    flashBtn.setImageResource(R.drawable.flash1) // your "flash on" icon
                    //flashBtn.imageTintList = ColorStateList.valueOf(Color.YELLOW)
                } else {
                    flashBtn.setBackgroundResource(R.drawable.record_button_ring1)
                    flashBtn.setImageResource(R.drawable.flash3)

                    // flashBtn.setImageResource(R.drawable.flash) // your "flash off" icon
                    // flashBtn.imageTintList = ColorStateList.valueOf(Color.WHITE)
                }
            }
        }

        manualfocus.setOnClickListener {
            if (!isManualFocus) {
                // Enable manual focus UI
                recyclerView.visibility = View.VISIBLE

                // Hide zoom UI & disable zoom logic
                li_Zoom.visibility = View.GONE
                li_Zoom.isEnabled = false
                overlay.visibility = GONE
                overlay.isEnabled = false
                isZoomEnabled = false  // ✅ Disable all zoom-related logic
                tvOPIC.visibility = GONE
                isManualFocus = true

                manualfocus.setBackgroundResource(R.drawable.manulafocus_bg)
                manualfocus.setTextColor(Color.BLACK)
                zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
                zoombutton.setImageResource(R.drawable.zoomwhite)
                zoombutton.imageTintList = null  // Optional

            } else {
                recyclerView.visibility = View.GONE

                isZoomEnabled = false  // Still off unless you re-enable above

                isManualFocus = false
                tvOPIC.visibility = GONE
                manualfocus.setBackgroundResource(R.drawable.record_button_ring1)
                manualfocus.setTextColor(Color.WHITE)
            }
        }

        li_Zoom.post {
            val width = li_Zoom.width
            val height = li_Zoom.height

            li_Zoom.setBackgroundDrawable(
                createGradientBorderBackground(width, height)

            )
        }

        setupZoomButtons()
        setupZoomDrag()
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


    fun createGradientRingDrawablebutton(width: Int, height: Int): Drawable {
        val strokeWidth = 1f
        val radius = height / 2f

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val rect = RectF(
            strokeWidth / 2,
            strokeWidth / 2,
            width - strokeWidth / 2,
            height - strokeWidth / 2
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.parseColor("#1CF3FF"),
                Color.parseColor("#FD2F55"),
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawRoundRect(rect, radius, radius, paint)

        return BitmapDrawable(Resources.getSystem(), bitmap)
    }


    fun createGradientBorderBackground(width: Int, height: Int): Drawable {
        val radius = height / 2f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.parseColor("#1CF3FF"),
                Color.parseColor("#FD2F55"),
                Shader.TileMode.CLAMP

            )

            maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL) // Optional soft glow

        }

        canvas.drawRoundRect(rect, radius, radius, paint)

        return BitmapDrawable(Resources.getSystem(), bitmap)
    }


    private fun hasPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val audioPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return cameraPermission == PackageManager.PERMISSION_GRANTED &&
                audioPermission == PackageManager.PERMISSION_GRANTED

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_LONG).show()

            }
        }
    }

    private fun bindVideoUseCase() {
        // Use Camera2Interop to configure Preview Builder
        val previewBuilder = Preview.Builder()
        val previewExtender = Camera2Interop.Extender(previewBuilder)
        previewExtender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_OFF
        )
        previewExtender.setCaptureRequestOption(
            CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_MODE_AUTO
        )
        val preview = previewBuilder
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            this, cameraSelector, preview, videoCapture

        )
        cameraControl = camera.cameraControl
        cameraInfo = camera.cameraInfo

        setupManualFocusRecyclerView()
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
                            recordedVideoUri = cacheUri
                            // Share straight from cache (no MediaStore copy)
//                            shareVideoToWhatsAppFromCache(cacheUri)
                            Toast.makeText(
                                this,
                                "Video saved to cache: " + recordedVideoUri,
                                Toast.LENGTH_SHORT
                            ).show()
                            // ✅ Call the compressor function
                            Log.d("VideoCompressor", "Orignal video saved at: $recordedVideoUri")
                            Log.d(
                                "VideoCompressor", "Orignal video size: ${
                                    getFileSize(
                                        recordedVideoUri!!
                                    )
                                }"
                            )
                            compressVideo(recordedVideoUri!!)
                        } else {
                            // Optionally delete a partial/corrupt file
                            if (outFile.exists()) outFile.delete()
                        }
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
                        inputUri!!
                    )
                }"
            )
            compressVideo(inputUri!!)


        }
    }

    private fun compressVideo(uri: Uri) {
        compressVideoToCacheUri(
            context = this,
            inputUri = uri,
            targetBitrate = 4_000_000, // 4 Mbps ~ good for 1080p
            preferHevc = true
        ) { outputUri ->
            if (outputUri != null) {
                // Success: do something with the Uri
                Log.d("VideoCompressor", "Compressed video saved at: $outputUri")
                Log.d("VideoCompressor", "Compressed video size: ${getFileSize(outputUri)}")


//                                    // Example: play it with a VideoView
                val videoView = VideoView(this)
                videoView.setVideoURI(outputUri)
                videoView.start()
                setContentView(videoView)
            } else {
                Log.e("VideoCompressor", "Compression failed")
            }
        }
    }

    private fun getCacheFileProviderUri(file: File): Uri {
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) {
            stopVideoRecording()
            Toast.makeText(
                this,
                "Recording stopped due to app going to background",
                Toast.LENGTH_SHORT
            ).show()
            isRecording = false
            isPaused = false
            //playButton.setImageResource(R.drawable.playbutton)

        }
    }

    fun getFileSize(uri: Uri): String {
        return try {
            val fileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return "0 B"
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
                timerView.timerText = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000)
            }

        }

    }

    private fun stopTimer() {
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
                    CameraSelector.Builder()
                        .requireLensFacing(it)
                        .build()
                }
            }
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }


    private fun setupZoomButtons() {
        val zoomButtons = listOf(
            //   Pair(findViewById<TextView>(R.id.zoom_5x), 5.0f),
            Pair(findViewById<TextView>(R.id.zoom_4x), 4.0f),
            Pair(findViewById<TextView>(R.id.zoom_3x), 3.0f),
            Pair(findViewById<TextView>(R.id.zoom_2x), 2.0f),
            Pair(findViewById<TextView>(R.id.zoom_18x), 1.8f),
            Pair(findViewById<TextView>(R.id.zoom_15x), 1.5f),
            Pair(findViewById<TextView>(R.id.zoom_12x), 1.2f),
            Pair(findViewById<TextView>(R.id.zoom_1x), 1.0f)

        )

        for ((textView, zoomValue) in zoomButtons) {
            textView.setOnClickListener {
                Log.d("CameraX", "zoomValue $zoomValue")
                cameraControl.setZoomRatio(zoomValue)
                highlightSelectedZoom(textView, zoomButtons.map { it.first })

            }

        }

        val defaultZoom = 1.2f

        val defaultZoomPair = zoomButtons.find { it.second == defaultZoom }
        defaultZoomPair?.let {
            highlightSelectedZoom(it.first, zoomButtons.map { pair -> pair.first })

        }
    }

    private lateinit var focusAdapter: ZoomAdapter

    @OptIn(ExperimentalCamera2Interop::class)
    fun setupManualFocusRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.zoomRecyclerView)
        val focusRing = findViewById<View>(R.id.focusRing)

        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
        val minFocusDistance = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )

        Log.d("ManualFocusCheck", "Min focus distance: $minFocusDistance")

        if (minFocusDistance == null || minFocusDistance == 0f) {
            Log.w("ManualFocusCheck", "Manual focus is NOT supported on this device.")
            recyclerView.visibility = View.GONE
            Toast.makeText(this, "Manual focus not supported", Toast.LENGTH_SHORT).show()
            return
        } else {
            Log.i("ManualFocusCheck", "Manual focus IS supported. Min distance: $minFocusDistance")
        }

        val camera2Control = Camera2CameraControl.from(camera.cameraControl)


        // Generate values from 0.0 to 1.0 (mapped later to minFocusDistance)
        val focusValues = (0.0f..1.0f).stepSafe(0.05f).map { String.format("%.2f", it) }

        focusAdapter = ZoomAdapter(focusValues) { selected ->
            val mapped = selected.toFloat() // 0.0 to 1.0
            val focusDistance = mapped * minFocusDistance
            Log.d("FocusSet", "Mapped: $mapped → FocusDistance: $focusDistance")

            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                .build()

            camera2Control.setCaptureRequestOptions(options)

            // Animate focus ring
            // showFocusRing(focusRing)
        }

        recyclerView.adapter = focusAdapter
        recyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        recyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, true).apply {
            stackFromEnd = true
        }
        recyclerView.onFlingListener = null
        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)

        // On scroll stop → apply focus
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val view = snapHelper.findSnapView(layoutManager)
                view?.let {
                    val pos = layoutManager.getPosition(it)
                    if (pos in focusValues.indices) {
                        val value = focusValues[pos].toFloat()
                        val focusDistance = value * minFocusDistance

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

                        focusAdapter.setSelectedZoom(value)

                        // Optional: vibrate every 5 steps
                        if (lastVibratedFocusIndex == -1 || kotlin.math.abs(pos - lastVibratedFocusIndex) >= 5) {
                            vibrateOnce()
                            lastVibratedFocusIndex = pos
                        }

                        // showFocusRing(focusRing)
                    }
                }
            }

        })

        // Scroll to default (0.5 mapped = mid-focus)
        val default = "0.50"
        val defaultIndex = focusValues.indexOf(default)
        if (defaultIndex != -1) {
            recyclerView.scrollToPosition(defaultIndex)
            focusAdapter.setSelectedZoom(default.toFloat())
        }
    }

    private fun showFocusRing(focusRing: View) {
        val centerX = previewView.width / 2f
        val centerY = previewView.height / 2f

        focusRing.translationX = centerX - focusRing.width / 2f
        focusRing.translationY = centerY - focusRing.height / 2f
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        focusRing.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .alpha(0f)
            .setDuration(800)
            .withEndAction { focusRing.visibility = View.GONE }
            .start()
    }


    private fun highlightSelectedZoom(selectedView: TextView, allViews: List<TextView>) {
        for (view in allViews) {
            view.background = null
            view.setTextColor(Color.WHITE)
            view.paint.shader = null // Clear any previous shader
        }

        selectedView.post {
            val drawable = createGradientRingDrawable(selectedView.width, selectedView.height)
            selectedView.background = drawable
            val shader = LinearGradient(
                0f, 0f, selectedView.width.toFloat(), 0f,
                Color.parseColor("#1CF3FF"),
                Color.parseColor("#FD2F55"),
                Shader.TileMode.CLAMP
            )
            selectedView.paint.shader = shader
            selectedView.invalidate()
        }
    }


    fun createGradientRingDrawable(width: Int, height: Int): Drawable {
        val strokeWidth = 1f
        val radius = height / 2f

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val rect = RectF(
            strokeWidth / 2,
            strokeWidth / 2,
            width - strokeWidth / 2,
            height - strokeWidth / 2
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.parseColor("#1CF3FF"),
                Color.parseColor("#FD2F55"),
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawRoundRect(rect, radius, radius, paint)

        return BitmapDrawable(Resources.getSystem(), bitmap)
    }


    private fun setupZoomDrag() {

        var lastZoom: Float? = null
        val zoomViews = listOf(
            //Pair(findViewById<TextView>(R.id.zoom_5x), 5.0f),
            Pair(findViewById<TextView>(R.id.zoom_4x), 4.0f),
            Pair(findViewById<TextView>(R.id.zoom_3x), 3.0f),
            Pair(findViewById<TextView>(R.id.zoom_2x), 2.0f),
            Pair(findViewById<TextView>(R.id.zoom_18x), 1.8f),
            Pair(findViewById<TextView>(R.id.zoom_15x), 1.5f),
            Pair(findViewById<TextView>(R.id.zoom_12x), 1.2f),
            Pair(findViewById<TextView>(R.id.zoom_1x), 1.0f)
        )
        findViewById<TextView>(R.id.zoom_4x).setOnClickListener {
            var zoomValue = 4.0f
            cameraControl.setZoomRatio(zoomValue)
            highlightSelectedZoom(findViewById<TextView>(R.id.zoom_4x), zoomViews.map { it.first })
            lastZoom = zoomValue
            vibrateOnce()
        }
        findViewById<TextView>(R.id.zoom_3x).setOnClickListener {
            var zoomValue = 3.0f
            cameraControl.setZoomRatio(zoomValue)
            highlightSelectedZoom(findViewById<TextView>(R.id.zoom_3x), zoomViews.map { it.first })
            lastZoom = zoomValue
            vibrateOnce()
        }
        findViewById<TextView>(R.id.zoom_2x).setOnClickListener {
            var zoomValue = 2.0f
            cameraControl.setZoomRatio(zoomValue)
            highlightSelectedZoom(findViewById<TextView>(R.id.zoom_2x), zoomViews.map { it.first })
            lastZoom = zoomValue
            vibrateOnce()
        }

        findViewById<TextView>(R.id.zoom_18x).setOnClickListener {
            var zoomValue = 1.8f
            cameraControl.setZoomRatio(zoomValue)
            highlightSelectedZoom(findViewById<TextView>(R.id.zoom_18x), zoomViews.map { it.first })
            lastZoom = zoomValue
            vibrateOnce()
        }
        findViewById<TextView>(R.id.zoom_15x).setOnClickListener {
            var zoomValue = 1.5f
            cameraControl.setZoomRatio(zoomValue)
            highlightSelectedZoom(findViewById<TextView>(R.id.zoom_15x), zoomViews.map { it.first })
            lastZoom = zoomValue
            vibrateOnce()
        }
        findViewById<TextView>(R.id.zoom_12x).setOnClickListener {
            var zoomValue = 1.2f
            cameraControl.setZoomRatio(zoomValue)
            highlightSelectedZoom(findViewById<TextView>(R.id.zoom_12x), zoomViews.map { it.first })
            lastZoom = zoomValue
            vibrateOnce()
        }
        findViewById<TextView>(R.id.zoom_1x).setOnClickListener {
            var zoomValue = 1.0f
            cameraControl.setZoomRatio(zoomValue)
            highlightSelectedZoom(findViewById<TextView>(R.id.zoom_1x), zoomViews.map { it.first })
            lastZoom = zoomValue
            vibrateOnce()
        }


//        overlay.setOnTouchListener { _, event ->
//            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
//                val screenY = event.rawY
//                val touchedView = zoomViews.minByOrNull { (view, _) ->
//                    val location = IntArray(2)
//                    view.getLocationOnScreen(location)
//                    val centerY = location[1] + view.height / 2
//                    kotlin.math.abs(screenY - centerY)
//                }
//
//                touchedView?.let { (view, zoomValue) ->
//                    if (lastZoom != zoomValue) {
//                        cameraControl.setZoomRatio(zoomValue)
//                        highlightSelectedZoom(view, zoomViews.map { it.first })
//                        lastZoom = zoomValue
//                        vibrateOnce()
//                    }
//                }
//            }
//            true
//        }
    }

    @SuppressLint("ServiceCast")
    private fun vibrateOnce() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }


    fun ClosedFloatingPointRange<Float>.stepSafe(step: Float): List<Float> {
        require(step > 0f)
        val steps = ((endInclusive - start) / step).toInt()
        return (0..steps).map { i ->
            String.format("%.2f", (start + i * step)).toFloat()
        }
    }

    private fun cacheVideoDir(): File {
        return externalCacheDir ?: cacheDir
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


    @OptIn(UnstableApi::class)
    fun compressVideoToCacheUri(
        context: Context,
        inputUri: Uri,
        targetBitrate: Int = 4_000_000, // ~4 Mbps default
        preferHevc: Boolean = true,
        onComplete: (Uri?) -> Unit
    ) {
        val outputFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        val outputPath = outputFile.absolutePath
        val mediaItem = MediaItem.fromUri(inputUri)

        // Request bitrate via VideoEncoderSettings -> DefaultEncoderFactory
        val videoSettings = VideoEncoderSettings.Builder()
            .setBitrate(targetBitrate)              // <— bitrate goes here (not on Transformer)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setEnableFallback(true)                // fallback to supported profiles/levels
            .build()

        fun startWith(mime: String) {
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(mime)             // prefer HEVC; fallback to AVC if it fails
                .build()

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onComplete(Uri.fromFile(outputFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException
                ) {
                    if (mime == MimeTypes.VIDEO_H265) {
                        startWith(MimeTypes.VIDEO_H264) // retry with AVC
                    } else {
                        outputFile.delete()
                        onComplete(null)
                    }
                }
            })

            // Start: MediaItem + output path (1.5.1 signature)
            transformer.start(mediaItem, outputPath)
        }

        startWith(if (preferHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
    }
}