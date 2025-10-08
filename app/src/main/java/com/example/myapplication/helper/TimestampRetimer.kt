package com.opic3d.Spatial.trendingvideos.helper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File


/**
 * Created by Faheem Abbas on 03/10/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */

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
