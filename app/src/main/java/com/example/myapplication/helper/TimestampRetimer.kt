package com.opic3d.Spatial.trendingvideos.helper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max


/**
 * Created by Faheem Abbas on 03/10/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */
private const val TAG = "TimestampRetimer"

/**
 * Retime a recorded video in-place.
 *
 * @param inputPath       Path to recorded .mp4 file (will be replaced in-place).
 * @param targetFps       Output fps (e.g., 24/25/30); used to space video frames.
 * @param speedMultiplier How much faster than real-time to play (e.g., 15.0 = 15x).
 * @param removeAudio     If true, audio track is dropped.
 */
fun retime(
    inputPath: String,
    targetFps: Int,
    speedMultiplier: Double,
    removeAudio: Boolean = true
) {
    require(targetFps > 0) { "targetFps must be > 0" }
    require(speedMultiplier > 0.0) { "speedMultiplier must be > 0" }

    val inputFile = File(inputPath)
    if (!inputFile.exists()) error("Input file does not exist: $inputPath")

    val tempFile = File(inputFile.parentFile ?: File("."), inputFile.nameWithoutExtension + "_tmp.mp4")
    if (tempFile.exists()) tempFile.delete()

    val extractor = MediaExtractor()
    extractor.setDataSource(inputFile.absolutePath)

    var videoTrackIndex = -1
    var audioTrackIndex = -1

    // Determine tracks and capture rotation from the *video* track (if present).
    var rotationDegrees = 0
    var inVideoFormat: MediaFormat? = null
    var inAudioFormat: MediaFormat? = null

    for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/") && videoTrackIndex == -1) {
            videoTrackIndex = i
            inVideoFormat = MediaFormat.createVideoFormat(
                mime,
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT)
            ).apply {
                // Copy essential keys from source format (conservative)
                copyIfPresent(format, MediaFormat.KEY_COLOR_FORMAT)
                copyIfPresent(format, MediaFormat.KEY_BIT_RATE)
                copyIfPresent(format, MediaFormat.KEY_PROFILE)
                copyIfPresent(format, MediaFormat.KEY_LEVEL)
                copyIfPresent(format, MediaFormat.KEY_I_FRAME_INTERVAL)
                copyIfPresent(format, "csd-0")
                copyIfPresent(format, "csd-1")
                // Force target frame rate (some muxers/readers honor this metadata)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            }

            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                rotationDegrees = format.getInteger(MediaFormat.KEY_ROTATION)
            }
        } else if (mime.startsWith("audio/") && audioTrackIndex == -1 && !removeAudio) {
            audioTrackIndex = i
            inAudioFormat = format
        }
    }

    // Prepare muxer
    val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    if (rotationDegrees != 0) {
        // Preserve input rotation on output
        runCatching { muxer.setOrientationHint(rotationDegrees) }
    }

    var outVideoIndex = -1
    var outAudioIndex = -1

    if (videoTrackIndex != -1 && inVideoFormat != null) {
        outVideoIndex = muxer.addTrack(inVideoFormat!!)
    }
    if (audioTrackIndex != -1 && inAudioFormat != null) {
        outAudioIndex = muxer.addTrack(inAudioFormat!!)
    }

    muxer.start()

    try {
        // ----- Write VIDEO -----
        if (videoTrackIndex != -1) {
            extractor.selectTrack(videoTrackIndex)

            // Allocate a reasonably large buffer for samples
            val buffer = ByteBuffer.allocate(1 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            var frameIndex = 0L
            val frameDurationUs = (1_000_000.0 / targetFps / speedMultiplier) // compress time

            var lastPts = -1L
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break

                info.presentationTimeUs = (frameIndex * frameDurationUs).toLong()
                // enforce monotonic PTS strictly
                if (lastPts >= 0 && info.presentationTimeUs <= lastPts) {
                    info.presentationTimeUs = lastPts + 1
                }
                lastPts = info.presentationTimeUs

                info.flags = mapExtractorToCodecFlags(extractor.sampleFlags)

                muxer.writeSampleData(outVideoIndex, buffer, info)

                extractor.advance()
                frameIndex++
            }

            extractor.unselectTrack(videoTrackIndex)
        }

        // ----- Write AUDIO (optional) -----
        if (!removeAudio && audioTrackIndex != -1 && outAudioIndex != -1) {
            extractor.selectTrack(audioTrackIndex)

            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()

            var baseInputPts: Long? = null
            var lastPts = -1L

            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break

                val inputPts = extractor.sampleTime
                if (baseInputPts == null) baseInputPts = inputPts

                // Scale audio timestamps to match new (compressed) timeline
                val scaled = ((inputPts - baseInputPts!!) / speedMultiplier).toLong()
                info.presentationTimeUs = if (lastPts >= 0) max(scaled, lastPts + 1) else scaled
                lastPts = info.presentationTimeUs

                info.flags = mapExtractorToCodecFlags(extractor.sampleFlags)

                muxer.writeSampleData(outAudioIndex, buffer, info)

                extractor.advance()
            }

            extractor.unselectTrack(audioTrackIndex)
        }

    } finally {
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { extractor.release() }
    }

    // Replace original file atomically-ish
    if (inputFile.delete()) {
        if (!tempFile.renameTo(inputFile)) {
            Log.e(TAG, "Failed to rename temp to original path")
            // Try to restore
            tempFile.copyTo(inputFile, overwrite = true)
            tempFile.delete()
        }
    } else {
        Log.e(TAG, "Failed to delete original file; leaving temp at: ${tempFile.absolutePath}")
    }
}

// --- helpers ---

private fun mapExtractorToCodecFlags(sampleFlags: Int): Int {
    var out = 0
    // Map MediaExtractor flags -> MediaCodec BufferInfo flags
    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
        out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
        out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    // SAMPLE_FLAG_ENCRYPTED has no direct BUFFER_FLAG_* equivalent for muxer; ignore.
    return out
}

private fun MediaFormat.copyIfPresent(src: MediaFormat, key: String) {
    if (src.containsKey(key)) {
        when (val value = src.getValue(key)) {
            is Int -> setInteger(key, value)
            is Long -> setLong(key, value)
            is Float -> setFloat(key, value)
            is String -> setString(key, value)
            is ByteBuffer -> setByteBuffer(key, value)
        }
    }
}

// Safer getter that doesn’t throw if key missing; used in copyIfPresent
private fun MediaFormat.getValue(key: String): Any? {
    return try {
        when {
            containsKey(key) -> {
                // Try common types; order matters
                when {
                    key == "csd-0" || key == "csd-1" -> getByteBuffer(key)
                    else -> when {
                        // Heuristics: attempt in safe order
                        runCatching { getInteger(key) }.isSuccess -> getInteger(key)
                        runCatching { getLong(key) }.isSuccess -> getLong(key)
                        runCatching { getFloat(key) }.isSuccess -> getFloat(key)
                        runCatching { getString(key) }.isSuccess -> getString(key)
                        runCatching { getByteBuffer(key) }.isSuccess -> getByteBuffer(key)
                        else -> null
                    }
                }
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}
/* ===========================================================
 * TimestampRetimer
 * - Retimes to an exact playback fps (10..60) by rewriting PTS (no re-encode).
 * - Keeps video cadence intact; scales audio timestamps to end exactly with video.
 * - Replaces the source file (same name).
 * =========================================================== */


    fun retieToFixedFps(
        src: File,
        targetFps: Int,
        keepAudio: Boolean = true
    ): File {
       val TAG = "TimestampRe timer"
        require(targetFps in 10..60) { "targetFps must be in [10..60]" }
        Log.d(TAG, "Video saved with $targetFps")

        // Temp out; then replace original
        val finalPath = src.absolutePath
        val tmpOutFile = File(src.parentFile, src.nameWithoutExtension + "_retime_tmp.mp4")
        if (tmpOutFile.exists()) runCatching { tmpOutFile.delete() }

        val extractor = MediaExtractor()
        extractor.setDataSource(src.absolutePath)
        val muxer = MediaMuxer(tmpOutFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackCount = extractor.trackCount
        val outTrackIndex = IntArray(trackCount) { -1 }

        var videoTrack = -1
        var audioTrack = -1
        var orientationHint: Int? = null

        // Add tracks; set video "frame-rate" metadata to targetFps; keep rotation
        for (i in 0 until trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            val isVideo = mime.startsWith("video/")
            val isAudio = mime.startsWith("audio/")

            if (isVideo) {
                videoTrack = i
                try {
                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fmt.setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        targetFps
                    )
                    if (fmt.containsKey("frame-rate")) fmt.setInteger("frame-rate", targetFps)
                } catch (_: Throwable) {
                }
                try {
                    if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        orientationHint = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    }
                } catch (_: Throwable) {
                }
                outTrackIndex[i] = muxer.addTrack(fmt)
            } else if (isAudio && keepAudio) {
                audioTrack = i
                outTrackIndex[i] = muxer.addTrack(fmt)
            }
        }
        require(videoTrack >= 0) { "No video track found" }
        runCatching { orientationHint?.let { muxer.setOrientationHint(it) } }

        muxer.start()

        val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()

        // Video: rewrite PTS uniformly at targetFps
        val stepUs = 1_000_000L / targetFps
        var ptsUs = 0L
        for (i in 0 until trackCount) extractor.unselectTrack(i)
        extractor.selectTrack(videoTrack)
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            info.presentationTimeUs = ptsUs
            muxer.writeSampleData(outTrackIndex[videoTrack], buffer, info)
            ptsUs += stepUs
            extractor.advance()
        }
        extractor.unselectTrack(videoTrack)

        val videoDurationUs = ptsUs // one step past last frame

        // Audio: scale PTS to fill [0 .. videoDurationUs]
        if (keepAudio && audioTrack >= 0) {
            // probe first/last audio pts
            val probe = MediaExtractor()
            probe.setDataSource(src.absolutePath)
            var aProbeTrack = -1
            for (i in 0 until probe.trackCount) {
                val mime = probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    aProbeTrack = i; break
                }
            }
            var a0 = 0L
            var a1 = 0L
            if (aProbeTrack >= 0) {
                probe.selectTrack(aProbeTrack)
                val tmpBuf = java.nio.ByteBuffer.allocate(1 shl 16)
                var firstPts: Long? = null
                var lastPts: Long? = null
                while (true) {
                    tmpBuf.clear()
                    val size = probe.readSampleData(tmpBuf, 0)
                    if (size < 0) break
                    val pts = probe.sampleTime
                    if (pts >= 0) {
                        if (firstPts == null) firstPts = pts
                        lastPts = pts
                    }
                    probe.advance()
                }
                probe.release()
                a0 = firstPts ?: 0L
                a1 = lastPts ?: a0
            }

            val audioSpanUs = (a1 - a0).coerceAtLeast(1L)
            val scale =
                if (videoDurationUs <= 0) 1.0 else videoDurationUs.toDouble() / audioSpanUs.toDouble()

            // rewrite audio with scaled timestamps
            val aReader = MediaExtractor()
            aReader.setDataSource(src.absolutePath)
            var aReaderTrack = -1
            for (i in 0 until aReader.trackCount) {
                val mime = aReader.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    aReaderTrack = i; break
                }
            }
            if (aReaderTrack >= 0) {
                aReader.selectTrack(aReaderTrack)
                val aBuf = java.nio.ByteBuffer.allocate(1 shl 20)
                val aInfo = MediaCodec.BufferInfo()
                var lastOutPts = -1L
                while (true) {
                    aBuf.clear()
                    val size = aReader.readSampleData(aBuf, 0)
                    if (size < 0) break

                    val inPts = aReader.sampleTime
                    val relPts = (inPts - a0).coerceAtLeast(0L)
                    val outPts = (relPts.toDouble() * scale)
                        .toLong()
                        .coerceAtMost(if (videoDurationUs > 0) videoDurationUs - 1 else 0L)

                    aInfo.offset = 0
                    aInfo.size = size
                    aInfo.flags = 0
                    aInfo.presentationTimeUs = if (outPts <= lastOutPts) lastOutPts + 1 else outPts
                    lastOutPts = aInfo.presentationTimeUs

                    muxer.writeSampleData(outTrackIndex[audioTrack], aBuf, aInfo)
                    aReader.advance()
                }
            }
            aReader.release()
        }

        // Cleanup
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { extractor.release() }

        // Replace original file with temp (same name as src)
        val finalFile = File(finalPath)
        try {
            if (finalFile.exists()) {
                if (!finalFile.delete()) {
                    Log.w(
                        TAG,
                        "Could not delete original before replace: ${finalFile.absolutePath}"
                    )
                }
            }
            val renamed = tmpOutFile.renameTo(finalFile)
            if (!renamed) {
                tmpOutFile.inputStream().use { input ->
                    finalFile.outputStream().use { output -> input.copyTo(output) }
                }
                runCatching { tmpOutFile.delete() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "File replace failed; returning temp file", t)
            return tmpOutFile
        }

        Log.d(TAG, "Retimed to fixed fps=$targetFps → ${finalFile.absolutePath}")
        return finalFile
    }
