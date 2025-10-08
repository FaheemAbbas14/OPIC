package com.opic3d.Spatial.trendingvideos.helper


/**
 * Created by Faheem Abbas on 29/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */
// StockSlowMoEncoder.kt

import android.media.*
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class StockSlowMoEncoder(
    private val width: Int,
    private val height: Int,
    private val captureFps: Int,      // e.g., 120 or 240 (sensor capture rate)
    private val playbackFps: Int = 30,// ~30 for stock-like slowmo
    private val bitrate: Int = 12_000_000, // tune per device/resolution
    private val iFrameIntervalSec: Int = 1 // frequent IDRs help editors
) {
    private val TAG = "StockSlowMoEncoder"

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false

    fun createOutputFile(folder: File): File {
        if (!folder.exists()) folder.mkdirs()
        val name = "SLOWMO_${System.currentTimeMillis()}.mp4"
        return File(folder, name)
    }

    fun getInputSurface(): Surface? = inputSurface

    fun start(outputFile: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, playbackFps)   // playback fps
            if (Build.VERSION.SDK_INT >= 23) {
                setFloat(MediaFormat.KEY_CAPTURE_RATE, captureFps.toFloat()) // real capture fps
            }
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            // Optional: high profile if supported
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger("level", MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun drainEncoder(endOfStream: Boolean = false) {
        val c = codec ?: return
        if (endOfStream) c.signalEndOfInputStream()

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = c.outputFormat
                trackIndex = muxer!!.addTrack(newFormat)
                muxer!!.start()
                muxerStarted = true
            } else if (outIndex >= 0) {
                val outBuf: ByteBuffer = c.getOutputBuffer(outIndex) ?: continue
                if (bufferInfo.size > 0 && muxerStarted) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer!!.writeSampleData(trackIndex, outBuf, bufferInfo)
                }
                c.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    fun stopAndRelease() {
        try {
            drainEncoder(true)
        } catch (e: Exception) {
            Log.w(TAG, "Final drain failed: ${e.message}")
        }
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        inputSurface = null

        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Exception) {}
        try {
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
        trackIndex = -1
    }
}
