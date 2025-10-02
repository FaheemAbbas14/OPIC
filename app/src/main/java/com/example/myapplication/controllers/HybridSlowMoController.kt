package com.example.myapplication.controllers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.example.myapplication.model.SlowMoOption

class HybridSlowMoController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var cameraXController: CameraXSlowMoController? = null
    private var camera2Controller: Camera2SlowMoController? = null
    private var boundOption: SlowMoOption? = null

    @Volatile private var ready = false
    fun isReady() = ready

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
            Log.d("HybridController", "Binding Camera2 for ${option.label}")
            camera2Controller = Camera2SlowMoController(context, previewView).also { c ->
                c.start()
                c.onTooDark = onTooDark
                c.setZoomLevel(1.2f)

                // Prefer 120-capable size indoors; brighter than 240
                c.setAutoSelectBestHfrSize(true)
                c.setAutoTorchEnabled(false)
                c.setIndoorHfrBaseBias(evSteps = 6, maxAutoDelta = 6)
                c.setMainsHz(50) // set 60 if applicable

                c.bind(option) { e ->
                    Log.e("HybridController", "Camera2 bind error: ${e.message}", e)
                    onError(e)
                }

                // Let controller auto-switch HFR<->STD60 and auto-adjust EV
                c.setAutoEnvironmentMode(true)
                c.setAutoExposureBias(true)
                // Optional: preview lift tweaks
                c.setPreviewBoostEnabled(true)
                c.setPostRawBoost(true, 240)
            }
        } else {
            Log.d("HybridController", "Binding CameraX for ${option.label}")
            cameraXController = CameraXSlowMoController(context, lifecycleOwner, previewView).also {
                it.bind(option)
            }
        }
        ready = true
    }

    fun startRecording(
        withAudio: Boolean = true,
        onStarted: () -> Unit = {},
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        boundOption?.let { opt ->
            if (opt.fpsRange.upper > 60) {
                camera2Controller?.startRecording(
                    onStarted = onStarted,
                    onSaved = onSaved,
                    onError = onError
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
        onError: (Throwable) -> Unit = {},
        slowMoPlaybackFps: Int? = 30, // 15 or 30; null to keep realtime
        keepAudio: Boolean = false
    ) {
        boundOption?.let { opt ->
            if (opt.fpsRange.upper > 60) {
                if (slowMoPlaybackFps != null) {
                    camera2Controller?.stopRecordingWithPlaybackFps(
                        targetFps = slowMoPlaybackFps,
                        onSaved = onSaved,
                        onError = onError,
                    )
                } else {
                    camera2Controller?.stopRecording(onSaved, onError)
                }
            } else {
                cameraXController?.stopRecording()
            }
        }
    }

    fun enableAutoFocus() { camera2Controller?.enableAutoFocus() }
    fun setManualFocus(distance: Float) { camera2Controller?.setManualFocus(distance) }
    fun setZoomLevel(zoom: Float) { camera2Controller?.setZoomLevel(zoom) }

    fun release() {
        ready = false
        cameraXController?.release()
        camera2Controller?.release()
        cameraXController = null
        camera2Controller = null
        boundOption = null
    }
}
