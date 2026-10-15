package com.darshan.horizoncam.core

import android.content.Context
import android.content.SharedPreferences

enum class CaptureMode(val label: String) { PHOTO("PHOTO"), VIDEO("VIDEO"), TIMELAPSE("TIMELAPSE") }

enum class FrameOrientation(val label: String) { AUTO("Auto"), LANDSCAPE("Landscape"), PORTRAIT("Portrait") }

enum class PreviewStyle(val label: String) { GIMBAL("Gimbal view"), OUTPUT("Output view") }

enum class AspectRatio(val ratio: Double, val label: String) {
    R16_9(16.0 / 9.0, "16:9"),
    R4_3(4.0 / 3.0, "4:3"),
    R1_1(1.0, "1:1")
}

enum class VideoQuality(val shortSide: Int, val label: String) {
    HD(720, "720p"),
    FHD(1080, "1080p"),
    QHD(1440, "1440p"),
    UHD(2160, "4K")
}

enum class VolumeAction(val label: String) { SHUTTER("Shutter"), ZOOM("Zoom"), OFF("Off") }

enum class WbPreset(val label: String) { AUTO("Auto"), DAYLIGHT("Daylight"), CLOUDY("Cloudy"), INCANDESCENT("Tungsten"), FLUORESCENT("Fluorescent") }

/** Persistent user settings (SharedPreferences backed). */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("horizoncam", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumPref(key: String, def: T): T {
        val name = p.getString(key, null) ?: return def
        return try { java.lang.Enum.valueOf(T::class.java, name) } catch (e: Exception) { def }
    }

    private fun putEnum(key: String, v: Enum<*>) = p.edit().putString(key, v.name).apply()
    private fun putBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()
    private fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()

    var captureMode: CaptureMode
        get() = enumPref("captureMode", CaptureMode.VIDEO)
        set(v) = putEnum("captureMode", v)

    var lockMode: LockMode
        get() = enumPref("lockMode", LockMode.LOCK_360)
        set(v) = putEnum("lockMode", v)

    var levelRange: Int
        get() = p.getInt("levelRange", 30)
        set(v) = putInt("levelRange", v)

    var frameOrientation: FrameOrientation
        get() = enumPref("frameOrientation", FrameOrientation.AUTO)
        set(v) = putEnum("frameOrientation", v)

    var videoAspect: AspectRatio
        get() = enumPref("videoAspect", AspectRatio.R16_9)
        set(v) = putEnum("videoAspect", v)

    var photoAspect: AspectRatio
        get() = enumPref("photoAspect", AspectRatio.R4_3)
        set(v) = putEnum("photoAspect", v)

    var videoQuality: VideoQuality
        get() = enumPref("videoQuality", VideoQuality.FHD)
        set(v) = putEnum("videoQuality", v)

    var fps: Int
        get() = p.getInt("fps", 30)
        set(v) = putInt("fps", v)

    var hevc: Boolean
        get() = p.getBoolean("hevc", false)
        set(v) = putBool("hevc", v)

    var highBitrate: Boolean
        get() = p.getBoolean("highBitrate", false)
        set(v) = putBool("highBitrate", v)

    var recordAudio: Boolean
        get() = p.getBoolean("recordAudio", true)
        set(v) = putBool("recordAudio", v)

    var cameraStabilization: Boolean
        get() = p.getBoolean("cameraStabilization", true)
        set(v) = putBool("cameraStabilization", v)

    var timelapseSpeed: Int
        get() = p.getInt("timelapseSpeed", 10)
        set(v) = putInt("timelapseSpeed", v)

    /** 0 = auto (smooth when camera stabilisation is on), 1 = locked (rigid), 2 = smooth. */
    var response: Int
        get() = p.getInt("response", 0)
        set(v) = putInt("response", v)

    var preferUltraWide: Boolean
        get() = p.getBoolean("preferUltraWide", false)
        set(v) = putBool("preferUltraWide", v)

    /** 0 = off, 1 = natural, 2 = crisp. */
    var sharpness: Int
        get() = p.getInt("sharpness", 1)
        set(v) = putInt("sharpness", v)

    var previewStyle: PreviewStyle
        get() = enumPref("previewStyle", PreviewStyle.GIMBAL)
        set(v) = putEnum("previewStyle", v)

    var grid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) = putBool("grid", v)

    var levelIndicator: Boolean
        get() = p.getBoolean("levelIndicator", true)
        set(v) = putBool("levelIndicator", v)

    var volumeAction: VolumeAction
        get() = enumPref("volumeAction", VolumeAction.SHUTTER)
        set(v) = putEnum("volumeAction", v)

    var shutterSound: Boolean
        get() = p.getBoolean("shutterSound", true)
        set(v) = putBool("shutterSound", v)

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = putBool("haptics", v)

    var mirrorFront: Boolean
        get() = p.getBoolean("mirrorFront", false)
        set(v) = putBool("mirrorFront", v)

    var timerSec: Int
        get() = p.getInt("timerSec", 0)
        set(v) = putInt("timerSec", v)

    var useFront: Boolean
        get() = p.getBoolean("useFront", false)
        set(v) = putBool("useFront", v)

    var showHints: Boolean
        get() = p.getBoolean("showHints", true)
        set(v) = putBool("showHints", v)

    var lastSavedUri: String?
        get() = p.getString("lastSavedUri", null)
        set(v) = p.edit().putString("lastSavedUri", v).apply()

    var lastSavedIsVideo: Boolean
        get() = p.getBoolean("lastSavedIsVideo", false)
        set(v) = putBool("lastSavedIsVideo", v)
}
