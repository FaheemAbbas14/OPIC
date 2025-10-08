package com.opic3d.Spatial.trendingvideos.helper


/**
 * Created by Faheem Abbas on 19/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ImageSplitter {

    enum class Side { LEFT, RIGHT }

    /**
     * Splits the image into two halves and returns the requested side as Bitmap.
     *
     * @param context Context for opening the Uri
     * @param inputUri Uri of the image (content:// or file://)
     * @param side Which half to return (LEFT or RIGHT)
     */
    fun splitHalfToUri(
        context: Context,
        inputUri: Uri,
        side: Side
    ): Uri {
        // Decode original image
        val bitmap = context.contentResolver.openInputStream(inputUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalArgumentException("Cannot decode image from $inputUri")

        val width = bitmap.width
        val height = bitmap.height
        val halfWidth = width / 2

        val x = if (side == Side.LEFT) 0 else halfWidth

        // Crop the requested half
        val cropped = Bitmap.createBitmap(bitmap, x, 0, halfWidth, height)

        // Save to cache
        val outFile = File(
            context.cacheDir,
            "split_${side.name.lowercase()}_${System.currentTimeMillis()}.jpg"
        )

        FileOutputStream(outFile).use { fos ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }

        cropped.recycle()
        bitmap.recycle()

        return Uri.fromFile(outFile) // If sharing outside, wrap with FileProvider
    }

}
