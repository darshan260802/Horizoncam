package com.darshan.horizoncam.media

import android.media.MediaActionSound
import android.os.Build

/** Whether the camera shutter sound may be turned off on this phone. */
object SoundPolicy {
    /** True where the law requires the shutter sound (Android 13+ reports this). */
    private val forcedValue: Boolean by lazy {
        Build.VERSION.SDK_INT >= 33 && try { MediaActionSound.mustPlayShutterSound() } catch (e: Throwable) { false }
    }

    fun forced(): Boolean = forcedValue
}
