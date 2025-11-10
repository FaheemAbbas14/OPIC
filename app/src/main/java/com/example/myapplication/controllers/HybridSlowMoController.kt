package com.opic3d.Spatial.trendingvideos.controllers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import com.opic3d.Spatial.trendingvideos.model.SlowMoOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Delegates:
 *  - CameraXController → VIDEO + PHOTO + TIMELAPSE (post-retime)
 *  - Camera2SlowMoController → SLOWMO (true high-FPS capture)
 *
 * Single, smooth API for the Activity.
 */
class HybridSlowMoController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {

    enum class Mode { VIDEO, PHOTO, TIMELAPSE, SLOWMO, THREEDPHOTO, THREEDVIDEO }

    private val camX = CameraXController(context, lifecycleOwner, previewView)
    private val cam2 = Camera2Controller(context, previewView)

    private var mode: Mode = Mode.VIDEO
    private var slowMoOption: SlowMoOption? = null

    // Cache min-focus for the active Camera2 slow-mo camera
    private var cam2MinFocusDistance: Float = 0f

    /**
     * Base capture FPS used when converting time-lapse "multiplier" to captureFps for CameraX.
     * Example: base=30, multiplier=10x -> captureFps = 30 / 10 = 3.0 fps
     * Keep this in sync with camX.setDesiredFps(base).
     */
    private var baseFpsForTimelapse: Int = 30

    /** Optionally let caller align base FPS (and camX desired FPS). */
    fun setBaseFpsForTimelapse(fps: Int) {
        val clamped = fps.coerceIn(15, 60)
        baseFpsForTimelapse = clamped
        camX.setDesiredFps(clamped)
    }

    // ---------- Binding (manual) ----------

    suspend fun bindVideo() {
        mode = Mode.VIDEO
        cam2.release()
        camX.bind(photo = false, video = true)
    }

    suspend fun bindPhoto() {
        mode = Mode.PHOTO
        cam2.release()
        camX.bind(photo = true, video = false)
    }

    /** Bind Camera2 for slow-mo using the provided option (cameraId, size, fpsRange, selector). */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun bindSlowMo(option: SlowMoOption, onError: (Throwable) -> Unit = {}) {
        Log.d("Hybrid", "bindSlowMo: $option")
        mode = Mode.SLOWMO
        slowMoOption = option
        camX.release()
        // query and cache min focus for this Camera2 device
        cam2MinFocusDistance = queryMinFocusDistance(option.cameraId)
        cam2.bind(option, false, onError)
    }

    /** Bind CameraX for **time-lapse** (video-only). Call this before startLapseVideo(...). */
    suspend fun bindTimeLapse() {
        mode = Mode.TIMELAPSE
        cam2.release() // ensure Camera2 is not holding the camera
        camX.bind(photo = false, video = true)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun bindTHREED(option: SlowMoOption, onError: (Throwable) -> Unit = {}) {
        mode = Mode.THREEDVIDEO
        slowMoOption = option
        camX.release()
        // query and cache min focus for this Camera2 device
        cam2MinFocusDistance = queryMinFocusDistance(option.cameraId)
        cam2.bind(option, true, onError)
    }
    // ---------- Smooth mode switching (freeze frame + crossfade) ----------

    @Volatile
    private var switching = false

    data class UiState(
        val zoom: Float? = null,
        val torch: Boolean? = null
        // add other state you want to persist across binds
    )

    private fun captureUiState(): UiState = UiState(
        zoom = null,  // if you track current zoom externally, put it here
        torch = null  // store torch state here if you track it
    )

    private fun applyUiState(state: UiState) {
        state.zoom?.let { setZoomLevel(it) }
        state.torch?.let { setTorch(it) }
    }

    /**
     * Freeze current preview, rebind to target mode, then crossfade out the frozen frame.
     * Call from a coroutine (e.g., lifecycleScope.launch { switchMode(...) }).
     *
     * @param overlay An ImageView stacked above PreviewView (match_parent x match_parent).
     *                We'll put previewView.bitmap into it and fade it out after rebind.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun switchMode(
        target: Mode,
        overlay: ImageView?,
        onPreviewResumed: () -> Unit = {}
    ) {
        if (switching || mode == target) return
        switching = true

        val needsRebind = when {
            mode == target -> false
            mode == Mode.SLOWMO || target == Mode.SLOWMO -> true // Camera2 <-> CameraX swap
            else -> true // CameraX rebind (fast but we still freeze to hide the jump)
        }

        if (needsRebind) freezePreviewInto(overlay)

        // Preserve simple UI state you care about (zoom/torch/etc.)
        val state = captureUiState()

        try {
            when (target) {
                Mode.VIDEO -> {
                    cam2.release()
                    camX.bind(photo = false, video = true)
                }

                Mode.PHOTO -> {
                    cam2.release()
                    camX.bind(photo = true, video = false)
                }

                Mode.TIMELAPSE -> {
                    cam2.release()
                    camX.bind(photo = false, video = true)
                }

                Mode.SLOWMO -> {
                    val opt = slowMoOption ?: throw IllegalStateException("SlowMoOption not set")
                    camX.release()
                    cam2.bind(opt, false) { /* onError -> log/surface from caller if needed */ }
                }

                Mode.THREEDVIDEO, Mode.THREEDPHOTO -> {
                    val opt = slowMoOption ?: throw IllegalStateException("3D not set")
                    camX.release()
                    cam2.bind(opt, true) {
                    /* onError -> log/surface from caller if needed */
                    }
                }
            }
            mode = target

            // Allow a brief moment for new frames, then fade overlay
            if (needsRebind && overlay != null) {
                withContext(Dispatchers.Main) {
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(180L)
                        .withEndAction { overlay.setImageDrawable(null) }
                        .start()
                }
            }

            applyUiState(state)
            onPreviewResumed()
        } finally {
            switching = false
        }
    }

    /** Grab a bitmap from PreviewView and place it into the overlay for a smooth crossfade. */
    private fun freezePreviewInto(overlay: ImageView?) {
        if (overlay == null) return
        val bmp = previewView.bitmap
        if (bmp == null) {
            overlay.setImageDrawable(null)
            overlay.alpha = 0f
            return
        }
        overlay.setImageBitmap(bmp)
        overlay.alpha = 1f
    }

    // ---------- Video actions ----------

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(
        withAudio: Boolean = true,
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        when (mode) {
            Mode.VIDEO -> {
                if (!camX.isVideoReady()) {
                    onError(IllegalStateException("Video not bound. Call bindVideo() before startRecording()."))
                    return
                }
                camX.startRecording(withAudio, onStarted, onSaved, onError)
            }

            Mode.SLOWMO -> cam2.startRecording(onStarted, onSaved, onError)
            Mode.THREEDVIDEO -> {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }

                    cam2.start3DRecordingSbs(
                        onStarted = { /* UI */ },
                        onSaved = onSaved,
                        onError = onError
                    )

            }

            Mode.TIMELAPSE -> onError(IllegalStateException("Use startLapseVideo() while in TIMELAPSE mode"))
            Mode.PHOTO -> onError(IllegalStateException("Photo mode has no recording"))
            Mode.THREEDPHOTO -> onError(IllegalStateException("Photo mode has no recording"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun take3DPhoto(
        withAudio: Boolean = true,
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        cam2.capture3DPhotoSbs(
            context=context,
            jpegQuality = 92,
            onSaved = onSaved,
            onError = onError
        )

    }

    /**
     * For VIDEO mode: waits for finalize, then invokes callbacks.
     * For SLOWMO mode: delegates to Camera2 controller (optionally keeping audio at playback fps).
     */
    fun stopRecording(
        keepAudio: Boolean = true,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        when (mode) {
            Mode.VIDEO -> camX.stopRecording { result ->
                result.onSuccess(onSaved).onFailure(onError)
            }

            Mode.SLOWMO -> cam2.stopRecordingWithPlaybackFps(keepAudio, onSaved, onError)

            Mode.THREEDVIDEO -> // Stop SBS
                cam2.stop3DRecordingSbs(
                    onSaved = onSaved,
                    onError = onError
                )


            else -> { /* no-op for PHOTO / TIMELAPSE here */
            }
        }
    }


    // --------- camera helpers ----------
    private fun findBackCameraId(): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.first { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun pickStereoPair(): Pair<String, String> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val rears = cm.cameraIdList.filter { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        }
        require(rears.size >= 2) { "Device needs at least two back cameras for 3D" }
        // Example: choose the first two
        return rears[0] to rears[1]
    }
    // ---------- Time-lapse (CameraX) ----------

    /**
     * Start a time-lapse using a speed multiplier (e.g., 10x).
     * Internally converts to captureFps = baseFpsForTimelapse / multiplier.
     *
     * @param multiplier e.g. 10.0 for 10x speed
     * @param playbackFps container/output fps (24/25/30/50/60 typical)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startLapseVideo(
        multiplier: Double,
        playbackFps: Int = 30,
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        mode = Mode.TIMELAPSE

        // Must be bound for TL: use bindTimeLapse() (suspend) or switchMode(...)
        if (!camX.isVideoReady()) {
            onError(IllegalStateException("Time-lapse not bound. Call bindTimeLapse() or switchMode(TIMELAPSE) before startLapseVideo()."))
            return
        }

        val safeMultiplier = if (multiplier <= 0.0) 1.0 else multiplier
        val captureFps = max(0.1, baseFpsForTimelapse / safeMultiplier)

        camX.startTimeLapseRecording(
            captureFps = captureFps,
            playbackFps = playbackFps,
            onStarted = onStarted,
            onSaved = onSaved,
            onError = onError
        )
    }

    /**
     * Stop the current time-lapse (CameraX) and get the retimed file.
     * @param normalizePlaybackFps snap playbackFps to a nearby standard (24/25/30/48/50/60/120).
     */
    fun stopLapseVideo(
        normalizePlaybackFps: Boolean = false,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        camX.stopTimeLapse(normalizePlaybackFps, onSaved, onError)
    }

    fun getZoomLevels(): MutableList<Float> {
        when (mode) {
            Mode.VIDEO, Mode.PHOTO, Mode.TIMELAPSE -> return camX.checkZoomValues()
            Mode.SLOWMO, Mode.THREEDVIDEO, Mode.THREEDPHOTO -> return cam2.checkZoomValues()
        }
    }

    // ---------- Photo (CameraX) ----------

    fun takePhoto(
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        camX.takePhoto(onSaved, onError)
    }

    // ---------- Controls (Zoom / Focus / Torch) ----------

    @RequiresApi(Build.VERSION_CODES.P)
    fun setZoomLevel(ratio: Float) {
        when (mode) {
            Mode.VIDEO, Mode.PHOTO, Mode.TIMELAPSE -> camX.setZoomLevel(ratio)
            Mode.SLOWMO, Mode.THREEDVIDEO, Mode.THREEDPHOTO -> cam2.setZoomLevel(ratio)
        }
    }

    fun setTorch(enabled: Boolean) {
        when (mode) {
            Mode.VIDEO, Mode.PHOTO, Mode.TIMELAPSE -> camX.setTorch(enabled)
            Mode.SLOWMO, Mode.THREEDVIDEO, Mode.THREEDPHOTO -> cam2.setTorch(enabled)
        }
    }

    fun enableAutoFocus() {
        when (mode) {
            Mode.VIDEO, Mode.PHOTO, Mode.TIMELAPSE -> camX.enableAutoFocus()
            Mode.SLOWMO, Mode.THREEDVIDEO, Mode.THREEDPHOTO -> cam2.enableAutoFocus()
        }
    }

    /**
     * Manual focus using normalized [0..1]:
     *  - 0 → infinity (distance 0)
     *  - 1 → macro (distance = minFocusDistance)
     */
    fun setManualFocusNormalized(normalized: Float) {
        val n = normalized.coerceIn(0f, 1f)
        when (mode) {
            Mode.VIDEO, Mode.PHOTO, Mode.TIMELAPSE -> camX.setManualFocusNormalized(n)
            Mode.SLOWMO, Mode.THREEDVIDEO, Mode.THREEDPHOTO -> {
                val min = ensureCam2MinFocus()
                if (min > 0f) {
                    val distance = (1f - n) * min
                    cam2.setManualFocus(distance)
                }
            }
        }
    }

    /** For UI rulers to initialize range/labels. */
    fun getMinFocusDistance(): Float = when (mode) {
        Mode.VIDEO, Mode.PHOTO, Mode.TIMELAPSE -> camX.getMinFocusDistance()
        Mode.SLOWMO, Mode.THREEDVIDEO, Mode.THREEDPHOTO -> ensureCam2MinFocus()
    }

    fun getLastManualFocusNormalized(): Float = when (mode) {
        Mode.VIDEO, Mode.PHOTO, Mode.TIMELAPSE -> camX.getLastManualFocusNormalized()
        Mode.SLOWMO, Mode.THREEDVIDEO, Mode.THREEDPHOTO -> 0f // store externally if you want parity
    }

    fun isReady(): Boolean = when (mode) {
        Mode.SLOWMO -> cam2.isReady()
        else -> true
    }

    fun release() {
        camX.release()
        cam2.release()
    }

    // ---------- Internals ----------

    private fun ensureCam2MinFocus(): Float {
        if (cam2MinFocusDistance > 0f) return cam2MinFocusDistance
        val id = slowMoOption?.cameraId ?: return 0f
        cam2MinFocusDistance = queryMinFocusDistance(id)
        return cam2MinFocusDistance
    }

    private fun queryMinFocusDistance(cameraId: String): Float {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cm.getCameraCharacteristics(cameraId)
            val v = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            v ?: 0f
        } catch (_: Throwable) {
            0f
        }
    }

}
