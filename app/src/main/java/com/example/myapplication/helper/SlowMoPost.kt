package com.opic3d.Spatial.trendingvideos.helper


/**
 * Created by Faheem Abbas on 29/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object SlowMoPost {
    private const val TAG = "SlowMoPost"

    // -------- Public APIs --------

    /**
     * Create a slow-motion copy by rewriting PTS for the VIDEO track only (no audio).
     * Fast, no re-encode. Playback fps controls slow-down factor: factor = captureFps / playbackFps.
     * E.g., 120 / 30 => ~4× slower playback.
     */
    fun makeSlowMoCopy(
        context: Context,
        inputUri: Uri,
        captureFps: Int = 120,
        playbackFps: Int = 30,
        outDir: File = File(context.getExternalFilesDir(null), "slowmo_out")
    ): File? = remuxSlowMo(
        context = context,
        inputUri = inputUri,
        captureFps = captureFps,
        playbackFps = playbackFps,
        keepAudio = false,
        outDir = outDir
    )

    /**
     * Create a slow-motion copy with VIDEO slowed (PTS stretched) and AUDIO copied unchanged.
     * Audio remains real-time (typical stock behavior is to mute; this keeps it).
     */
    fun makeSlowMoCopyKeepAudio(
        context: Context,
        inputUri: Uri,
        captureFps: Int = 120,
        playbackFps: Int = 30,
        outDir: File = File(context.getExternalFilesDir(null), "slowmo_out")
    ): File? = remuxSlowMo(
        context = context,
        inputUri = inputUri,
        captureFps = captureFps,
        playbackFps = playbackFps,
        keepAudio = true,
        outDir = outDir
    )

    // -------- Core Remux --------

    private fun remuxSlowMo(
        context: Context,
        inputUri: Uri,
        captureFps: Int,
        playbackFps: Int,
        keepAudio: Boolean,
        outDir: File
    ): File? {
        val factor = captureFps.toDouble() / playbackFps.toDouble()
        if (factor <= 1.0) {
            Log.w(TAG, "Nothing to slow (capture=$captureFps, playback=$playbackFps)")
            return null
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource failed", e)
            return null
        }

        val videoTrack = findTrack(extractor, "video/") ?: run {
            Log.e(TAG, "No video track")
            extractor.release()
            return null
        }
        val audioTrack = if (keepAudio) findTrack(extractor, "audio/") else null

        val inVFormat = extractor.getTrackFormat(videoTrack)
        val inAFormat = audioTrack?.let { extractor.getTrackFormat(it) }

        if (!outDir.exists()) outDir.mkdirs()
        val base = (queryDisplayName(context, inputUri) ?: "clip.mp4").removeSuffix(".mp4")
        val suffix = if (keepAudio) "_slow_audio.mp4" else "_slow.mp4"
        val outFile = File(outDir, base + suffix)

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVTrack = muxer.addTrack(inVFormat)
        val outATrack = inAFormat?.let { muxer.addTrack(it) }

        muxer.start()

        // --- Write video with stretched PTS ---
        extractor.selectTrack(videoTrack)
        val vMax = inVFormat.getIntegerOr(default = 2 * 1024 * 1024, key = MediaFormat.KEY_MAX_INPUT_SIZE)
        val vBuf = ByteBuffer.allocateDirect(vMax)
        val vInfo = MediaCodec.BufferInfo()
        val frameDurUs = (1_000_000.0 / playbackFps.toDouble()).toLong()
        var frameIndex = 0L

        try {
            while (true) {
                vBuf.clear()
                val size = extractor.readSampleData(vBuf, 0)
                if (size < 0) break
                if (extractor.sampleTrackIndex != videoTrack) {
                    extractor.advance()
                    continue
                }

                vInfo.size = size
                vInfo.offset = 0
                vInfo.flags = translateFlags(extractor.sampleFlags)
                vInfo.presentationTimeUs = frameIndex * frameDurUs

                muxer.writeSampleData(outVTrack, vBuf, vInfo)

                frameIndex++
                extractor.advance()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video write failed", e)
            safeStopRelease(muxer)
            extractor.release()
            return null
        }

        // --- Write audio unchanged (optional) ---
        if (audioTrack != null && outATrack != null) {
            try {
                extractor.unselectTrack(videoTrack)
                extractor.selectTrack(audioTrack)

                val aMax = inAFormat!!.getIntegerOr(default = 256 * 1024, key = MediaFormat.KEY_MAX_INPUT_SIZE)
                val aBuf = ByteBuffer.allocateDirect(aMax)
                val aInfo = MediaCodec.BufferInfo()

                while (true) {
                    aBuf.clear()
                    val size = extractor.readSampleData(aBuf, 0)
                    if (size < 0) break
                    if (extractor.sampleTrackIndex != audioTrack) {
                        extractor.advance()
                        continue
                    }

                    aInfo.size = size
                    aInfo.offset = 0
                    aInfo.flags = translateFlags(extractor.sampleFlags)
                    aInfo.presentationTimeUs = extractor.sampleTime

                    muxer.writeSampleData(outATrack, aBuf, aInfo)
                    extractor.advance()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio write failed", e)
                // Continue; output will just have shorter/ no audio.
            }
        }

        safeStopRelease(muxer)
        extractor.release()

        Log.d(TAG, "Slow-mo created: ${outFile.absolutePath}  (capture=$captureFps, playback=$playbackFps, factor=${"%.2f".format(factor)}x)")
        return outFile
    }

    // -------- Helpers --------

    /** Map MediaExtractor SAMPLE_FLAG_* to MediaCodec BUFFER_FLAG_* */
    private fun translateFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME // = SYNC_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        // SAMPLE_FLAG_ENCRYPTED has no BufferInfo equivalent; protected content shouldn't be remuxed.
        return flags
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }

    private fun MediaFormat.getIntegerOr(default: Int, key: String): Int =
        if (containsKey(key)) try { getInteger(key) } catch (_: Exception) { default } else default

    private fun safeStopRelease(muxer: MediaMuxer) {
        try { muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
    }
}
