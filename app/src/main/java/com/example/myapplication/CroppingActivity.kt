package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

import android.os.Environment
import android.util.Log
import com.daasuu.mp4compose.FillMode
import com.daasuu.mp4compose.composer.Mp4Composer
import java.io.File

class CroppingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.cropping_activity)

        fun cropVideo(inputPath: String, outputFileName: String) {
            val outputPath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                outputFileName
            ).absolutePath

            Mp4Composer(inputPath, outputPath)
                .size(720, 720) // output resolution
                .fillMode(FillMode.CUSTOM) // built-in center crop
                .listener(object : Mp4Composer.Listener {
                    override fun onProgress(progress: Double) {
                        Log.d("VideoCrop", "Progress: $progress")
                    }

                    override fun onCurrentWrittenVideoTime(timeUs: Long) {

                    }

                    override fun onCompleted() {
                        Log.d("VideoCrop", "Cropping completed: $outputPath")
                    }

                    override fun onCanceled() {
                        Log.d("VideoCrop", "Cropping canceled")
                    }

                    override fun onFailed(exception: Exception) {
                        Log.e("VideoCrop", "Cropping failed", exception)
                    }
                })
                .start()

        }

    }

}