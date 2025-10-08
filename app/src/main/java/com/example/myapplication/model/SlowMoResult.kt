package com.opic3d.Spatial.trendingvideos.model


/**
 * Created by Faheem Abbas on 29/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */
data class SlowMoResult(
    val playbackFps: Double?,   // computed avg fps (or null)
    val captureFps: Int,        // what you *intended* to capture at (e.g., 120)
    val factor: Double?,        // captureFps / playbackFps
    val isSlowMo: Boolean       // factor > 1.5 => slow-mo
)
