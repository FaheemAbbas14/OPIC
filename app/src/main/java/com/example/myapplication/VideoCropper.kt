package com.example.myapplication

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.daasuu.mp4compose.FillMode
import com.daasuu.mp4compose.FillModeCustomItem
import com.daasuu.mp4compose.Rotation
import com.daasuu.mp4compose.composer.Mp4Composer
import java.io.File
import java.io.FileOutputStream

object VideoCropper {

    /** Copy a content:// Uri to a local file so Mp4Composer can read it */
    private fun copyUriToFile(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open input stream for $uri")

        val outFile = File(
            context.getExternalFilesDir(null),
            "crop_in_${System.currentTimeMillis()}.mp4"
        )
        FileOutputStream(outFile).use { out ->
            inputStream.use { it.copyTo(out) }
        }
        return outFile.absolutePath
    }

    /** Make an app-private Movies output path (scoped-storage safe) */
    private fun makeOutputPath(context: Context): String {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, "Cropped")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "cropped_${System.currentTimeMillis()}.mp4").absolutePath
    }

    /**
     * Crop the LEFT 50% of a video and export to app's Movies/Cropped.
     */
    fun cropLeftHalf(
        context: Context,
        inputUri: Uri,
        onProgress: (Int) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val inputPath = if (inputUri.scheme == ContentResolver.SCHEME_CONTENT) {
                copyUriToFile(context, inputUri)
            } else {
                inputUri.path ?: throw IllegalArgumentException("Invalid URI")
            }

            // read dimensions
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(inputPath)
            val videoWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toInt() ?: 0
            val videoHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toInt() ?: 0
            retriever.release()

            if (videoWidth <= 0 || videoHeight <= 0) {
                throw IllegalStateException("Could not read video dimensions")
            }

            val outputWidth = videoWidth / 2
            val outputHeight = videoHeight

            // CUSTOM fill to keep LEFT half:
            val scaleX = 1f
            val scaleYSkew = 0f
            val translateX = 1f   // shift so right half is out, left half remains
            val translateY = 0f

            val outputPath = makeOutputPath(context)

            Mp4Composer(inputPath, outputPath)
                .rotation(Rotation.NORMAL)
                .size(outputWidth, outputHeight)
                .fillMode(FillMode.CUSTOM)
                .customFillMode(
                    FillModeCustomItem(
                        scaleX,
                        scaleYSkew,
                        translateX,
                        translateY,
                        videoWidth.toFloat(),
                        videoHeight.toFloat()
                    )
                )
                .listener(object : Mp4Composer.Listener {
                    override fun onProgress(progress: Double) {
                        onProgress((progress * 100).toInt().coerceIn(0, 100))
                    }

                    override fun onCompleted() {
                        Log.d("VideoCropper", "Cropping completed: $outputPath")
                        onComplete(outputPath)
                    }

                    override fun onFailed(exception: Exception) {
                        Log.e("VideoCropper", "Cropping failed", exception)
                        onError(exception)
                    }

                    override fun onCanceled() {
                        onError(IllegalStateException("Cropping canceled"))
                    }

                    override fun onCurrentWrittenVideoTime(timeUs: Long) { /* no-op */ }
                })
                .start()

        } catch (e: Exception) {
            onError(e)
        }
    }
}
