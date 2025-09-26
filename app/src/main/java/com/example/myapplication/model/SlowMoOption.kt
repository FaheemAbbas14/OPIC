package com.example.myapplication.model

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector

/**
 * Represents one concrete slow/fast video mode backed by Camera2/HS session.
 */
data class SlowMoOption(
    val label: String,                // e.g. "1920x1080 @ 120fps"
    val fpsRange: Range<Int>,         // fixed range (e.g., 120..120 or 60..60)
    val size: Size,                   // output resolution supported for that HS range
    val cameraId: String,             // Camera2 ID (string)
    val selector: CameraSelector      // for your CameraX path
)

/**
 * Enumerate **back-camera** high-speed (and 60fps) options.
 *
 * - Reads SCALER_STREAM_CONFIGURATION_MAP.highSpeedVideoSizes
 * - For each size, reads getHighSpeedVideoFpsRangesFor(size)
 * - Filters to fixed fps ranges (lower == upper), keeps 60/120/240 etc
 * - Sorts by fps desc, then resolution desc
 * - Falls back to normal 30/60 if no HS modes are found
 */
suspend fun listBackCameraSlowMoOptions(
    context: Context,
    minFps: Int = 61              // only include FPS > 60 for slow-mo
): List<SlowMoOption> {
    val out = mutableListOf<SlowMoOption>()
    val seen = HashSet<String>()

    val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val backIds = mgr.cameraIdList.filter { id ->
        mgr.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    for (id in backIds) {
        val chars = mgr.getCameraCharacteristics(id)
        val map: StreamConfigurationMap =
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

        val hsSizes = map.highSpeedVideoSizes ?: emptyArray()
        for (size in hsSizes) {
            val fpsRanges = map.getHighSpeedVideoFpsRangesFor(size) ?: continue
            for (range in fpsRanges) {
                // Only fixed-fps ranges and above minFps
                if (range.lower != range.upper) continue
                val f = range.upper
                if (f < minFps) continue

                val key = "$id|${size.width}x${size.height}|$f"
                if (seen.add(key)) {
                    out += SlowMoOption(
                        label = "${size.width}x${size.height} @ ${f}fps",
                        fpsRange = Range(f, f),
                        size = size,
                        cameraId = id,
                        selector = CameraSelector.DEFAULT_BACK_CAMERA
                    )
                }
            }
        }

        // Only use first back camera
        break
    }

    return out.sortedWith(
        compareByDescending<SlowMoOption> { it.fpsRange.upper }
            .thenByDescending { it.size.width.toLong() * it.size.height.toLong() }
    )
}

