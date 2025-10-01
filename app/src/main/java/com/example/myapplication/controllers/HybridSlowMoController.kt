package com.example.myapplication.controllers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.example.myapplication.model.SlowMoOption

/**
 * Hybrid controller:
 * - CameraX for normal video (<=60fps)
 * - Camera2 for high-speed slow-mo (>60fps)
 *
 * Exposes common API for Activity:
 * - bind(option)
 * - startRecording / stopRecording
 * - release
 * - isReady()
 * - setTorch(), setEv(), onTooDark callback
 */
class HybridSlowMoController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var cameraXController: CameraXSlowMoController? = null
    private var camera2Controller: Camera2SlowMoController? = null
    private var boundOption: SlowMoOption? = null

    @Volatile
    private var ready = false
    fun isReady() = ready

    /** optional low-light callback (set from Activity) */
    var onTooDark: (() -> Unit)? = null
        set(value) {
            field = value
            camera2Controller?.onTooDark = value
        }

    @SuppressLint("MissingPermission")
    suspend fun bind(option: SlowMoOption, onError: (Throwable) -> Unit = {}) {
        ready = false
        release()
        boundOption = option

        if (option.fpsRange.upper > 60) {
            // 🔥 Camera2 path
            Log.d("HybridController", "Binding Camera2 for ${option.label}")
            camera2Controller = Camera2SlowMoController(context, previewView).also {
                it.start()
                it.onTooDark = onTooDark
                it.setZoomLevel(1.2f)
                it.bind(option) { e ->
                    Log.e("HybridController", "Camera2 bind error: ${e.message}", e)
                    onError(e)
                }
            }
        } else {
            // ✅ CameraX path
            Log.d("HybridController", "Binding CameraX for ${option.label}")
            cameraXController = CameraXSlowMoController(context, lifecycleOwner, previewView).also {
                it.bind(option)
            }
        }
        ready = true
    }

    fun startRecording(
        withAudio: Boolean = false,
        onStarted: () -> Unit = {},
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        boundOption?.let { opt ->
            if (opt.fpsRange.upper > 60) {
                camera2Controller?.startRecording(
                    onStarted = { onStarted() },
                    onSaved = { uri -> onSaved(uri) },
                    onError = { e -> onError(e) }
                )
            } else {
                cameraXController?.startRecording(
                    withAudio = withAudio,
                    onStarted = onStarted,
                    onSaved = onSaved,
                    onError = onError
                )
            }
        } ?: onError(IllegalStateException("No option bound"))
    }

    fun stopRecording(
        onSaved: (Uri) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        boundOption?.let { opt ->
            if (opt.fpsRange.upper > 60) {
                camera2Controller?.stopRecording(onSaved, onError)
            } else {
                cameraXController?.stopRecording()
            }
        }
    }

    fun enableAutoFocus() {
        camera2Controller?.enableAutoFocus()
    }

    fun setManualFocus(distance: Float) {
        camera2Controller?.setManualFocus(distance)
    }

    fun setZoomLevel(zoom: Float) {
        camera2Controller?.setZoomLevel(zoom)
    }

    fun forceIndoorBrightMode(enable: Boolean, mainsHz: Int = 50) {
        camera2Controller?.forceIndoorBrightMode(enable, mainsHz)  // or 60 depending on your lights
        camera2Controller?.setUllRangePreferences(minLower = 15, maxUpper = 30)
        camera2Controller?.setPreferHfrOutdoors(true)   // smoother and sharper outdoors
        camera2Controller?.setOutdoorBrightnessBias(-3) // Footej-like darker sunlight
        camera2Controller?.setIndoorBrightnessBias(-3)  // keep indoor ULL slightly dimmer to reduce blur

    }

    fun release() {
        ready = false
        cameraXController?.release()
        camera2Controller?.release()
        cameraXController = null
        camera2Controller = null
        boundOption = null
    }
}
