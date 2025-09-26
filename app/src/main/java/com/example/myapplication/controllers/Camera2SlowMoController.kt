package com.example.myapplication.controllers

import android.Manifest
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
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
import kotlin.math.max

class Camera2SlowMoController(
    private val context: Context,
    private val previewView: PreviewView
) {
    private val TAG = "Camera2SlowMo"

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    private var option: SlowMoOption? = null
    @Volatile private var ready = false
    fun isReady() = ready

    private var deviceClosedLatch: CountDownLatch? = null
    private var sessionClosedLatch: CountDownLatch? = null

    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    private var aeCompRange: Range<Int>? = null
    private var currentEvSteps: Int = 0
    var manager:CameraManager?=null
    var onTooDark: (() -> Unit)? = null

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
            // AE support
            manager?.getCameraCharacteristics(opt.cameraId).let { chars ->
                aeCompRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                currentEvSteps = aeCompRange?.upper ?: 0
            }

            // Ensure preview surface ready
            previewSurface = waitForPreviewSurface(opt.size, 1200)
                ?: return onError(IllegalStateException("Preview surface not ready"))

            manager?.openCamera(opt.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    configurePreview(opt, onError)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    ready = false
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    ready = false
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

    private fun clampEv(ev: Int): Int {
        val r = aeCompRange ?: return 0
        return ev.coerceIn(r.lower, r.upper)
    }

    private fun configurePreview(opt: SlowMoOption, onError: (Throwable) -> Unit = {}) {
        val dev = cameraDevice ?: return
        val pSurf = previewSurface ?: return

        try {
            dev.createConstrainedHighSpeedCaptureSession(
                listOf(pSurf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(pSurf)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
//                                aeCompRange?.let {
//                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(currentEvSteps))
//                                }
                                val chars = manager?.getCameraCharacteristics(opt.cameraId)
                                val aeRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                               set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeRange?.upper)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 120))
                                // Flash/torch OFF intentionally
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            }
                            val hs = session as CameraConstrainedHighSpeedCaptureSession
                            val burst = hs.createHighSpeedRequestList(builder.build())
                            hs.setRepeatingBurst(burst, null, camHandler)
                            ready = true
                        } catch (e: Exception) {
                            onError(e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError(IllegalStateException("Preview configure failed"))
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        sessionClosedLatch?.countDown()
                    }
                }, camHandler
            )
        } catch (e: Exception) {
            onError(e)
        }
    }

    fun startRecording(onStarted: () -> Unit, onSaved: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        val dev = cameraDevice ?: return onError(IllegalStateException("Camera not ready"))
        val opt = option ?: return onError(IllegalStateException("No option bound"))

        val file = createOutputFile()
        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoFrameRate(opt.fpsRange.upper)
            setVideoSize(opt.size.width, opt.size.height)
            val targetBitrate = max(12_000_000, opt.size.width * opt.size.height * opt.fpsRange.upper / 2)
            setVideoEncodingBitRate(targetBitrate)
            prepare()
        }

        recorderSurface = mediaRecorder!!.surface

        closeSessionSync()

        dev.createConstrainedHighSpeedCaptureSession(
            listOf(previewSurface!!, recorderSurface!!),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(previewSurface!!)
                            addTarget(recorderSurface!!)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                           // aeCompRange?.let { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(currentEvSteps)) }
                            val chars = manager?.getCameraCharacteristics(opt.cameraId)
                            val aeRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeRange?.upper)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 120))
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }
                        val hs = session as CameraConstrainedHighSpeedCaptureSession
                        val reqList = hs.createHighSpeedRequestList(builder.build())
                        mediaRecorder?.start()
                        hs.setRepeatingBurst(reqList, null, camHandler)
                        onStarted()
                    } catch (e: Exception) {
                        onError(e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError(IllegalStateException("Failed to configure HS record"))
                }

                override fun onClosed(session: CameraCaptureSession) {
                    sessionClosedLatch?.countDown()
                }
            }, camHandler
        )
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
        option?.let { runCatching { configurePreview(it) {} } }
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
        mediaRecorder?.release()
        mediaRecorder = null
        previewSurface = null
        recorderSurface = null
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
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "SLOWMO_${ts}.mp4")
    }
}
