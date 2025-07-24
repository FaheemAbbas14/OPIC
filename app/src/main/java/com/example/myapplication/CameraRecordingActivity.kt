package com.example.myapplication
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.provider.MediaStore
import android.content.ContentValues
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ScaleGestureDetector
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
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
    lateinit var zoombutton:ImageButton
    private var timerJob: Job? = null
    private var startTime: Long = 0L
    private var lastVibratedFocusIndex: Int = -1
    //lateinit var playButton:ImageButton
    lateinit var videobuttonRecording:ImageButton
    //lateinit var photoButton:ImageButton
    private lateinit var camera: Camera
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo
    private lateinit var outputDirectory: File
    private var isRecording = false
    private var isPaused = false
    private var pausedTime = 0L
    private var isZoomButtonSelected = false
    lateinit var stopButton:TextView
    lateinit var flashBtn:ImageView
    private var isFlashOn = false
    private var isManualFocus = false
    lateinit var timerImage:ImageView
    lateinit var manualfocus:TextView
    lateinit var li_Zoom:LinearLayout
    lateinit var tvOPIC:OpicTextView
    lateinit var timerView:GlowingTimerView
    private var isManualFocusEnabled = false
lateinit var overlay:View
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var isZoomEnabled = true
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
        stopButton=findViewById(R.id.stopButton)
        flashBtn=findViewById(R.id.hdrIcon)
        zoombutton=findViewById(R.id.videobuttonblack)
        li_Zoom=findViewById(R.id.li_Zoom)
        timerImage=findViewById(R.id.micIcon)
        manualfocus=findViewById(R.id.stopButton)
        tvOPIC=findViewById(R.id.tv_opic_spartial)
         //tvOPIC = findViewById<TextView>(R.id.tv_opic_spartial)
         timerView = findViewById<GlowingTimerView>(R.id.glowTimer)
         overlay = findViewById<View>(R.id.zoomDragOverlay)
       // timerView.timerText = "00:01:23"

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
            Log.d("checkkSxc","yess2")
            tvOPIC.visibility= GONE
            zoombutton.visibility= VISIBLE
            stopButton.visibility= VISIBLE

            when {
                !isRecording -> {
                    videobuttonRecording.setBackgroundResource(R.drawable.record_button_ring)
                    videobuttonRecording.setImageResource(R.drawable.recordicon)
                   // videobuttonRecording.setBackgroundColor(Color.TRANSPARENT)
                    videobuttonRecording.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    videobuttonRecording.setPadding(32, 32, 32, 32)
                    timerView.visibility = View.VISIBLE
                    recyclerView.visibility= GONE
                    zoombutton.visibility= GONE
                    manualfocus.visibility= GONE

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
                    tvOPIC.visibility= VISIBLE
                    zoombutton.visibility= GONE
                    stopButton.visibility= GONE
                    recyclerView.visibility= GONE
                    zoombutton.visibility= VISIBLE
                    manualfocus.visibility= VISIBLE
                    li_Zoom.visibility= GONE
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
                    tvOPIC.visibility= GONE
                    li_Zoom.visibility= GONE
                    zoombutton.visibility= VISIBLE
                    stopButton.visibility= VISIBLE
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
                tvOPIC.visibility= GONE
                overlay.visibility= VISIBLE
                overlay.isEnabled=true
                li_Zoom.visibility= VISIBLE
                li_Zoom.isEnabled = false
                li_Zoom.isClickable = false
                recyclerView.visibility= GONE

                // Optional: cameraControl.setZoomRatio(2.0f)
            } else {

                zoombutton.setBackgroundResource(R.drawable.record_button_ring1)
                zoombutton.setImageResource(R.drawable.zoomwhite)

                tvOPIC.visibility= GONE
                li_Zoom.visibility= GONE
                li_Zoom.isEnabled = false
                li_Zoom.isClickable = false
                overlay.visibility= GONE
                overlay.isEnabled=false

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
                overlay.visibility= GONE
                overlay.isEnabled=false
                isZoomEnabled = false  // ✅ Disable all zoom-related logic
                tvOPIC.visibility= GONE
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
                tvOPIC.visibility= GONE
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

    }

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
        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
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
        val videoCapture = this.videoCapture ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CameraXVideos")
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues) // ✅ ensure new file
            .build()

        pausedTime = 0L // ✅ reset on every new recording
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
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
                            Toast.makeText(this, "Video saved successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) {
            stopVideoRecording()
            Toast.makeText(this, "Recording stopped due to app going to background", Toast.LENGTH_SHORT).show()
            isRecording = false
            isPaused = false
            //playButton.setImageResource(R.drawable.playbutton)

        }
    }

    private fun stopVideoRecording() {
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
            Pair(findViewById<TextView>(R.id.zoom_13x), 1.8f),
            Pair(findViewById<TextView>(R.id.zoom_10x), 1.5f),
            Pair(findViewById<TextView>(R.id.zoom_08x), 1.2f),
            Pair(findViewById<TextView>(R.id.zoom_05x), 1.0f)
            
        )

        for ((textView, zoomValue) in zoomButtons) {
            textView.setOnClickListener {
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
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
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
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
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

        val zoomViews = listOf(
            //Pair(findViewById<TextView>(R.id.zoom_5x), 5.0f),
            Pair(findViewById<TextView>(R.id.zoom_4x), 4.0f),
            Pair(findViewById<TextView>(R.id.zoom_3x), 3.0f),
            Pair(findViewById<TextView>(R.id.zoom_2x), 2.0f),
            Pair(findViewById<TextView>(R.id.zoom_13x), 1.2f),
            Pair(findViewById<TextView>(R.id.zoom_10x), 1.0f),
            Pair(findViewById<TextView>(R.id.zoom_08x), 0.8f),
            Pair(findViewById<TextView>(R.id.zoom_05x), 0.5f)
        )

        var lastZoom: Float? = null

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                val screenY = event.rawY
                val touchedView = zoomViews.minByOrNull { (view, _) ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val centerY = location[1] + view.height / 2
                    kotlin.math.abs(screenY - centerY)
                }

                touchedView?.let { (view, zoomValue) ->
                    if (lastZoom != zoomValue) {
                        cameraControl.setZoomRatio(zoomValue)
                        highlightSelectedZoom(view, zoomViews.map { it.first })
                        lastZoom = zoomValue
                        vibrateOnce()
                    }
                }
            }
            true
        }
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

}