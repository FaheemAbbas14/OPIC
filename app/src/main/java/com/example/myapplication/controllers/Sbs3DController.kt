package com.example.myapplication.controllers


/**
 * Created by Faheem Abbas on 08/11/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.opic3d.Spatial.trendingvideos.controllers.gl.SbsGlComposer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Standalone controller for FULL-SBS 3D:
 *  - 3D video recording (two streams -> GL -> single MP4)
 *  - 3D photo capture (two JPEGs -> side-by-side JPG)
 *
 * No preview UI, no overlay. The outer controller (Camera2Controller)
 * is responsible for freezing & restoring its own preview.
 */
class Sbs3DController(
    private val context: Context
) {
    private val TAG = "Sbs3DController"

    private val sbsSize = Size(1920, 1080)
    private var sbsFps: Range<Int> = Range(30, 30)

    // threading
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // camera manager / state
    private val manager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // GL compositor
    private var sbsComposer: SbsGlComposer? = null
    private var sbsLeftSurface: Surface? = null
    private var sbsRightSurface: Surface? = null

    // recording
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    // logical multi-cam path
    private var sbsSession: CameraCaptureSession? = null
    private var sbsLogicalId: String? = null
    private var sbsLeftPhysicalId: String? = null
    private var sbsRightPhysicalId: String? = null
    private var sbsUsingLogical: Boolean = true

    // concurrent fallback path
    private var sbsLeftDev: CameraDevice? = null
    private var sbsRightDev: CameraDevice? = null
    private var sbsLeftSession: CameraCaptureSession? = null
    private var sbsRightSession: CameraCaptureSession? = null

    // single device used for logical mode
    private var logicalDevice: CameraDevice? = null

    @Volatile
    private var cameraOpening = false

    private fun startThreadIfNeeded() {
        if (camThread != null) return
        camThread = HandlerThread("SBS3D").also { it.start() }
        camHandler = Handler(camThread!!.looper)
    }

    private fun stopThread() {
        camThread?.quitSafely()
        camThread = null
        camHandler = null
    }

    // ---------- public capability check ----------

    fun canDoSbs3D(): Boolean {
        // logical multi-cam?
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            val caps =
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val physicals = ch.physicalCameraIds
            val isLogical =
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (isLogical && physicals != null && physicals.size >= 2) return true
        }
        // concurrent fallback? (API 29+)
        return if (Build.VERSION.SDK_INT >= 29) {
            val sets: List<Set<String>> =
                manager.concurrentCameraIds?.map { it.toSet() }?.toList() ?: emptyList()
            sets.any { it.size >= 2 }
        } else false
    }

    // ---------- error helper ----------

    private fun explainCamError(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
        else -> "ERROR_UNKNOWN"
    }

    // ---------- logical & concurrent helpers ----------

    private fun findLogicalBackWithTwoPhysicals(): Pair<String, List<String>>? {
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val caps =
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val physicals = ch.physicalCameraIds?.toList() ?: emptyList()
            val isLogical =
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && isLogical && physicals.size >= 2) {
                return id to physicals
            }
        }
        return null
    }

    @RequiresApi(29)
    private fun findConcurrentPairBack(): Pair<String, String>? {
        val sets: List<Set<String>> =
            manager.concurrentCameraIds?.map { it.toSet() }?.toList() ?: return null

        if (sets.isEmpty()) return null

        fun isBack(id: String): Boolean {
            val ch = manager.getCameraCharacteristics(id)
            return ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

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

    // ---------- utility: file creation ----------

    @Suppress("DEPRECATION")
    private fun createOutputFile(
        prefix: String,
        extension: String
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val internalDir = File(context.cacheDir, "opic_temp_sbs").apply {
            if (!exists()) mkdirs()
        }
        val tempFile = File(internalDir, "${prefix}_$ts$extension")
        try {
            tempFile.parentFile?.mkdirs()
            tempFile.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create internal file", e)
        }
        return tempFile
    }

    // ---------- safe open camera ----------

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun safeOpenCamera(id: String, cb: CameraDevice.StateCallback) {
        startThreadIfNeeded()
        if (cameraOpening) {
            Log.w(TAG, "Camera $id is already opening — skipping")
            return
        }
        cameraOpening = true
        val handler = camHandler ?: Handler(Looper.getMainLooper())
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    cameraOpening = false
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

    // ---------- public: 3D VIDEO ----------

    /**
     * Start FULL-SBS 3D recording.
     *
     * No preview is shown; just GL -> recorder.
     * The caller (Camera2Controller) is responsible for UI overlay & rebind.
     */
    @RequiresApi(28)
    @RequiresPermission(Manifest.permission.CAMERA)
    fun start3DRecording(
        onStarted: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 28) {
            onError(UnsupportedOperationException("SBS requires API 28+")); return
        }
        if (isRecording) {
            onError(IllegalStateException("Already recording")); return
        }

        startThreadIfNeeded()

        // Prepare recorder
        val file = createOutputFile("SBS3D_Video", ".mp4")
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
            val usingHevc = trySetHevc(this).also {
                if (!it) setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            }
            setVideoFrameRate(sbsFps.upper)
            setVideoSize(outW, outH)
            val bitrate = computeTargetBitrate(
                outW,
                outH,
                sbsFps.upper,
                usingHevc
            )
            setVideoEncodingBitRate(bitrate)
            prepare()
        }
        outputFile = file
        val recSurface = mediaRecorder!!.surface

        // GL compositor
        try {
            sbsComposer = SbsGlComposer(
                outW = outW,
                outH = outH,
                outSurface = recSurface,
                eyeSize = sbsSize,
                previewSurface = null
            )
            sbsLeftSurface = sbsComposer!!.leftSurface
            sbsRightSurface = sbsComposer!!.rightSurface
        } catch (t: Throwable) {
            onError(RuntimeException("Failed to init SbsGlComposer: ${t.message}", t))
            return
        }

        // Try logical first
        val logical = findLogicalBackWithTwoPhysicals()
        if (logical != null) {
            sbsUsingLogical = true
            val (logicalId, physicals) = logical
            val leftId = physicals[0]
            val rightId = physicals.firstOrNull { it != leftId } ?: run {
                onError(IllegalStateException("No second physical camera under $logicalId")); return
            }
            sbsLogicalId = logicalId
            sbsLeftPhysicalId = leftId
            sbsRightPhysicalId = rightId

            val cb = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    logicalDevice = device
                    createSbsSessionLogical(device, onStarted, onError)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    onError(RuntimeException("SBS logical camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val why = explainCamError(error)
                    device.close()
                    onError(RuntimeException("SBS logical open failed: $why ($error)"))
                }
            }
            safeOpenCamera(logicalId, cb)
            return
        }

        // Fallback concurrent
        if (Build.VERSION.SDK_INT >= 29) {
            val pair = findConcurrentPairBack()
            if (pair != null) {
                sbsUsingLogical = false
                val (leftId, rightId) = pair
                openConcurrentDualSessions(
                    leftId, rightId, sbsSize, sbsFps,
                    onStarted = {
                        isRecording = true
                        sbsComposer?.start()
                        mediaRecorder?.start()
                        onStarted()
                    },
                    onError = onError
                )
                return
            }
        }

        onError(IllegalStateException("No SBS-capable logical or concurrent configuration found"))
    }

    fun stop3DRecording(
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (!isRecording && mediaRecorder == null) {
            onError(IllegalStateException("Not recording")); return
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
        outputFile = null

        releaseSbsInternal()

        if (err != null) {
            onError(err!!)
            return
        }
        if (recorded == null || !recorded.exists()) {
            onError(IllegalStateException("No output file"))
            return
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(recorded.absolutePath),
            arrayOf("video/mp4"),
            null
        )
        onSaved(Uri.fromFile(recorded))
    }

    // ---------- public: 3D PHOTO ----------

    /**
     * Capture one FULL-SBS JPG in a background session.
     * Caller handles preview overlay & mono camera rebind.
     */
    @RequiresApi(28)
    @RequiresPermission(Manifest.permission.CAMERA)
        fun capture3DPhoto(
            jpegQuality: Int = 92,
            onSaved: (Uri) -> Unit,
            onError: (Throwable) -> Unit
        ) {
            startThreadIfNeeded()
            val logical = findLogicalBackWithTwoPhysicals()
                ?: return onError(IllegalStateException("No logical back camera with 2 physicals found"))
            val (logicalId, physicals) = logical
            val leftId = physicals.firstOrNull()
                ?: return onError(IllegalStateException("Missing left camera"))
            val rightId = physicals.getOrNull(1)
                ?: return onError(IllegalStateException("Missing right camera"))

            val leftReader = ImageReader.newInstance(sbsSize.width, sbsSize.height, ImageFormat.JPEG, 2)
            val rightReader =
                ImageReader.newInstance(sbsSize.width, sbsSize.height, ImageFormat.JPEG, 2)
            val leftSurface = leftReader.surface
            val rightSurface = rightReader.surface

            val leftLatch = CountDownLatch(1)
            val rightLatch = CountDownLatch(1)
            val leftBytes = AtomicReference<ByteArray>()
            val rightBytes = AtomicReference<ByteArray>()

            fun acquire(reader: ImageReader, target: AtomicReference<ByteArray>, latch: CountDownLatch) {
                reader.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    val arr = ByteArray(buf.remaining())
                    buf.get(arr)
                    target.set(arr)
                }
                latch.countDown()
            }

            leftReader.setOnImageAvailableListener(
                { acquire(leftReader, leftBytes, leftLatch) },
                camHandler
            )
            rightReader.setOnImageAvailableListener(
                { acquire(rightReader, rightBytes, rightLatch) },
                camHandler
            )

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
                                    onError(RuntimeException("SBS3D photo session config failed"))
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

            safeOpenCamera(logicalId, stateCb)

            Thread {
                try {
                    leftLatch.await(3, TimeUnit.SECONDS)
                    rightLatch.await(3, TimeUnit.SECONDS)

                    val l = leftBytes.get() ?: throw IllegalStateException("Left image missing")
                    val r = rightBytes.get() ?: throw IllegalStateException("Right image missing")

                    val leftBmp = BitmapFactory.decodeByteArray(l, 0, l.size)
                    val rightBmp = BitmapFactory.decodeByteArray(r, 0, r.size)

                    val outW = leftBmp.width + rightBmp.width
                    val outH = max(leftBmp.height, rightBmp.height)
                    val combined =
                        Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    Canvas(combined).apply {
                        drawBitmap(leftBmp, 0f, 0f, null)
                        drawBitmap(rightBmp, leftBmp.width.toFloat(), 0f, null)
                    }

                    val file = createOutputFile("SBS3D_Photo", ".jpg")
                    FileOutputStream(file).use {
                        combined.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)
                    }

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

        // ---------- internal SBS cleanup ----------

        fun release() {
            releaseSbsInternal()
            stopThread()
        }

                private fun releaseSbsInternal() {
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

            try {
                sbsComposer?.stop()
            } catch (_: Throwable) {
            }
            try {
                sbsComposer?.release()
            } catch (_: Throwable) {
            }
            sbsComposer = null

            try {
                sbsLeftSurface?.release()
            } catch (_: Throwable) {
            }
            try {
                sbsRightSurface?.release()
            } catch (_: Throwable) {
            }
            sbsLeftSurface = null
            sbsRightSurface = null

            if (sbsUsingLogical) {
                try {
                    sbsSession?.close()
                } catch (_: Throwable) {
                }
                sbsSession = null
                try {
                    logicalDevice?.close()
                } catch (_: Throwable) {
                }
                logicalDevice = null
            } else {
                try {
                    sbsLeftSession?.close()
                } catch (_: Throwable) {
                }
                try {
                    sbsRightSession?.close()
                } catch (_: Throwable) {
                }
                sbsLeftSession = null
                sbsRightSession = null
                try {
                    sbsLeftDev?.close()
                } catch (_: Throwable) {
                }
                try {
                    sbsRightDev?.close()
                } catch (_: Throwable) {
                }
                sbsLeftDev = null
                sbsRightDev = null
            }
        }

        // ---------- concurrent dual sessions ----------

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
                    val leftCb = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sbsLeftSession = session
                            val b =
                                sbsLeftDev!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    .apply {
                                        addTarget(leftIn)
                                        set(
                                            CaptureRequest.CONTROL_MODE,
                                            CaptureRequest.CONTROL_MODE_AUTO
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                            fps
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AWB_MODE,
                                            CaptureRequest.CONTROL_AWB_MODE_AUTO
                                        )
                                        set(
                                            CaptureRequest.NOISE_REDUCTION_MODE,
                                            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                                        )
                                        set(
                                            CaptureRequest.EDGE_MODE,
                                            CaptureRequest.EDGE_MODE_HIGH_QUALITY
                                        )
                                        set(
                                            CaptureRequest.FLASH_MODE,
                                            CaptureRequest.FLASH_MODE_OFF
                                        )
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
                                sbsRightDev!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    .apply {
                                        addTarget(rightIn)
                                        set(
                                            CaptureRequest.CONTROL_MODE,
                                            CaptureRequest.CONTROL_MODE_AUTO
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                            fps
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AWB_MODE,
                                            CaptureRequest.CONTROL_AWB_MODE_AUTO
                                        )
                                        set(
                                            CaptureRequest.NOISE_REDUCTION_MODE,
                                            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                                        )
                                        set(
                                            CaptureRequest.EDGE_MODE,
                                            CaptureRequest.EDGE_MODE_HIGH_QUALITY
                                        )
                                        set(
                                            CaptureRequest.FLASH_MODE,
                                            CaptureRequest.FLASH_MODE_OFF
                                        )
                                    }
                            session.setRepeatingRequest(b.build(), null, camHandler)
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
                    sbsLeftDev = device
                    leftOpened = true
                    tryStartSessions()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close(); onError(RuntimeException("Left camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val why = explainCamError(error)
                    device.close()
                    onError(RuntimeException("Left camera open failed: $why ($error)"))
                }
            }

            val rightOpenCb = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    sbsRightDev = device
                    rightOpened = true
                    tryStartSessions()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close(); onError(RuntimeException("Right camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val why = explainCamError(error)
                    device.close()
                    onError(RuntimeException("Right camera open failed: $why ($error)"))
                }
            }

            safeOpenCamera(leftId, leftOpenCb)
            safeOpenCamera(rightId, rightOpenCb)
        }

        // ---------- logical session ----------

        @RequiresApi(28)
        private fun createSbsSessionLogical(
            dev: CameraDevice,
            onStarted: () -> Unit,
            onError: (Throwable) -> Unit
        ) {
            val leftIn = sbsLeftSurface ?: return onError(IllegalStateException("No left surface"))
            val rightIn = sbsRightSurface ?: return onError(IllegalStateException("No right surface"))

            val outLeft = OutputConfiguration(leftIn).apply { setPhysicalCameraId(sbsLeftPhysicalId) }
            val outRight =
                OutputConfiguration(rightIn).apply { setPhysicalCameraId(sbsRightPhysicalId) }

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
                        isRecording = true
                        sbsComposer?.start()
                        mediaRecorder?.start()
                        onStarted()
                    } catch (t: Throwable) {
                        onError(t)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError(IllegalStateException("SBS logical session configure failed"))
                }
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outLeft, outRight),
                { runnable -> (camHandler ?: mainHandler).post(runnable) },
                sessionCallback
            )

            try {
                dev.createCaptureSession(sessionConfig)
            } catch (t: Throwable) {
                onError(t)
            }
        }

                // ---------- encoding helpers ----------

                private fun trySetHevc(rec: MediaRecorder): Boolean = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC); true
            } else false
        } catch (_: Exception) {
            false
        }

                private fun computeTargetBitrate(
            w: Int,
            h: Int,
            fps: Int,
            usingHevc: Boolean
        ): Int {
            val baseBppf = 0.24
            val bppf = if (usingHevc) baseBppf * 0.6 else baseBppf
            val est = (w.toLong() * h.toLong() * fps * bppf).toLong()
            val min = if (usingHevc) 6_000_000L else 10_000_000L
            val maxB = 80_000_000L
            return est.coerceIn(min, maxB).toInt()
        }
}
