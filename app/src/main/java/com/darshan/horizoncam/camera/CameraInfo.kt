package com.darshan.horizoncam.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import com.darshan.horizoncam.core.FrameOrientation
import com.darshan.horizoncam.core.HorizonMath
import com.darshan.horizoncam.core.LockMode
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Static description of one camera, extracted once from CameraCharacteristics. */
class CameraInfo(val id: String, val chars: CameraCharacteristics) {
    val facing: Int = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK
    val isFront: Boolean get() = facing == CameraMetadata.LENS_FACING_FRONT
    val sensorOrientation: Int = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val activeArray: Rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
    val hardwareLevel: Int = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    val hasFlash: Boolean = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    val afModes: IntArray = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    val awbModes: IntArray = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
    val maxAfRegions: Int = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxAeRegions: Int = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    val evRange: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    // Explicit numerator/denominator: Rational also has a hidden toFloat() that apps may not call.
    val evStep: Float = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        ?.let { if (it.denominator != 0) it.numerator.toFloat() / it.denominator else 0f } ?: 0f
    val fpsRanges: Array<Range<Int>> = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf()
    val videoStabModes: IntArray = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
    val oisModes: IntArray = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
    val focalLengths: FloatArray = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4f)
    val sensorSizeMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val maxDigitalZoom: Float = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val capabilities: IntArray = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    val noiseModes: IntArray = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
    val edgeModes: IntArray = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
    val aberrationModes: IntArray = chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: IntArray(0)
    val hotPixelModes: IntArray = chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: IntArray(0)
    val shadingModes: IntArray = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: IntArray(0)
    val toneMapModes: IntArray = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)

    val zoomRange: Range<Float>? =
        if (Build.VERSION.SDK_INT >= 30) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null

    val minZoom: Float get() = zoomRange?.lower ?: 1f
    val maxZoom: Float get() = zoomRange?.upper ?: maxDigitalZoom

    val backwardCompatible: Boolean
        get() = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)

    val previewStabilizationSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 33 &&
            videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)

    val videoStabilizationSupported: Boolean
        get() = videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)

    val oisSupported: Boolean
        get() = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

    /** Focal length relative to sensor width; smaller means a wider field of view. */
    val fovFactor: Float
        get() {
            val w = sensorSizeMm?.width ?: 5.6f
            return (focalLengths.minOrNull() ?: 4f) / w
        }

    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

    fun textureSizes(): List<Size> = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
    fun jpegSizes(): List<Size> = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()

    fun minFrameDurationNs(size: Size): Long = try {
        map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
    } catch (e: Exception) { 0L }

    /** Best AE target FPS range for a fixed video frame rate (exact if possible). */
    fun fpsRangeFor(fps: Int, video: Boolean): Range<Int>? {
        if (fpsRanges.isEmpty()) return null
        if (video) {
            fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
            fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }?.let { return it }
        } else {
            fpsRanges.filter { it.upper == 30 }.minByOrNull { it.lower }?.let { return it }
        }
        return fpsRanges.filter { it.upper <= fps }.maxByOrNull { it.upper * 100 + it.lower }
    }

    fun supportsFps(fps: Int): Boolean = fpsRanges.any { it.upper >= fps }

    /** Natural (device-upright) dimensions of a stream size. */
    fun naturalDims(s: Size): IntArray =
        if (sensorOrientation % 180 == 90) intArrayOf(s.height, s.width) else intArrayOf(s.width, s.height)

    /**
     * Ranks camera stream sizes for video. Quality first: the largest stream in the sensor's
     * native aspect ratio that runs at the requested frame rate, so the horizon-lock crop keeps
     * as much real detail as possible (the GPU then downsamples it with anti-aliasing).
     * Returns the best size first, then progressively safer fallbacks.
     */
    fun chooseVideoInputSizes(
        fps: Int,
        outShort: Int,
        aspect: Double,
        lockMode: LockMode,
        levelRange: Double,
        orientation: FrameOrientation
    ): List<Size> {
        val frameDuration = 1_000_000_000L / fps
        val maxArea = if (fps > 30) 3840L * 2160 else 4096L * 3072
        val all = textureSizes()
        fun fpsOk(s: Size) = minFrameDurationNs(s).let { it == 0L || it <= frameDuration * 105 / 100 }
        var cands = all.filter { s ->
            s.width >= 640 && s.height >= 480 && s.width.toLong() * s.height <= maxArea && fpsOk(s)
        }
        if (cands.isEmpty()) cands = all.filter { it.width.toLong() * it.height <= 1920 * 1440 }
        if (cands.isEmpty()) cands = all
        val range = HorizonMath.cropRange(lockMode, levelRange)
        val frames = when (orientation) {
            FrameOrientation.AUTO -> listOf(true, false)
            FrameOrientation.LANDSCAPE -> listOf(true)
            FrameOrientation.PORTRAIT -> listOf(false)
        }

        /** Image pixels per output pixel in the worst case (>= 1 means full detail). */
        fun detail(s: Size): Double {
            val nd = naturalDims(s)
            var worst = Double.MAX_VALUE
            for (landscape in frames) {
                val bases = when {
                    lockMode == LockMode.LOCK_360 -> listOf(0)
                    orientation == FrameOrientation.AUTO -> listOf(if (landscape) 90 else 0)
                    else -> listOf(0, 90)
                }
                val fs = HorizonMath.frameSize(outShort, aspect, landscape)
                for (b in bases) {
                    worst = min(worst, HorizonMath.fitScale(nd[0].toDouble(), nd[1].toDouble(), fs[0].toDouble(), fs[1].toDouble(), b.toDouble(), range))
                }
            }
            return worst
        }

        val arrAspect = activeArray.width().toDouble() / activeArray.height()
        fun native(s: Size) = abs(s.width.toDouble() / s.height - arrAspect) < 0.03
        fun area(s: Size) = s.width.toLong() * s.height
        val ranked = cands.sortedWith(
            compareByDescending<Size> { native(it) }
                .thenByDescending { area(it) }
        )
        val result = mutableListOf<Size>()
        ranked.firstOrNull()?.let { result.add(it) }
        // A smaller native-aspect stream that still gives full detail, in case the HAL rejects the largest one.
        ranked.filter { native(it) && area(it) <= 8_500_000L && detail(it) >= 1.0 }.lastOrNull()?.let { if (!result.contains(it)) result.add(it) }
        for (fb in listOf(Size(1920, 1440), Size(1920, 1080), Size(1440, 1080), Size(1280, 960), Size(1280, 720))) {
            if (cands.any { it == fb } && !result.contains(fb)) result.add(fb)
        }
        if (result.isEmpty() && all.isNotEmpty()) result.add(all.first())
        return result
    }

    /** Pixels of camera detail per output pixel for a stream (diagnostics / UI). */
    fun detailFor(s: Size, outShort: Int, aspect: Double, lockMode: LockMode, levelRange: Double): Double {
        val nd = naturalDims(s)
        val fs = HorizonMath.frameSize(outShort, aspect, true)
        return HorizonMath.fitScale(nd[0].toDouble(), nd[1].toDouble(), fs[0].toDouble(), fs[1].toDouble(), 0.0, HorizonMath.cropRange(lockMode, levelRange))
    }

    /** Preview stream + JPEG size for photo mode. */
    fun choosePhotoSizes(): Pair<Size, Size?> {
        val arrAspect = activeArray.width().toDouble() / activeArray.height()
        val jpegs = jpegSizes().filter { it.width.toLong() * it.height <= 16_800_000L }
        val jpeg = jpegs.filter { abs(it.width.toDouble() / it.height - arrAspect) < 0.02 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: jpegs.maxByOrNull { it.width.toLong() * it.height }
        val target = if (jpeg != null) jpeg.width.toDouble() / jpeg.height else arrAspect
        val tex = textureSizes()
        val preview = tex.filter {
            abs(it.width.toDouble() / it.height - target) < 0.02 && it.width.toLong() * it.height <= 1920L * 1440 &&
                minFrameDurationNs(it).let { d -> d == 0L || d <= 34_000_000L }
        }.maxByOrNull { it.width.toLong() * it.height }
            ?: tex.filter { it.width.toLong() * it.height <= 1920L * 1080 }.maxByOrNull { it.width.toLong() * it.height }
            ?: tex.firstOrNull() ?: Size(1280, 720)
        return preview to jpeg
    }

    fun describe(): String {
        val sb = StringBuilder()
        sb.append("Camera $id (${facingName(facing)})\n")
        sb.append("  level=${levelName(hardwareLevel)} orientation=$sensorOrientation fov=%.3f\n".format(fovFactor))
        sb.append("  activeArray=${activeArray.width()}x${activeArray.height()} zoom=${zoomRange ?: "n/a"} maxDigital=$maxDigitalZoom\n")
        sb.append("  focal=${focalLengths.joinToString()} sensor=${sensorSizeMm}\n")
        sb.append("  fps=${fpsRanges.joinToString()}\n")
        sb.append("  stab=${videoStabModes.joinToString()} ois=${oisModes.joinToString()} flash=$hasFlash\n")
        sb.append("  af=${afModes.joinToString()} regions af=$maxAfRegions ae=$maxAeRegions ev=$evRange step=$evStep\n")
        sb.append("  caps=${capabilities.joinToString()}\n")
        sb.append("  textureSizes=${textureSizes().joinToString { "${it.width}x${it.height}" }}\n")
        sb.append("  jpegSizes=${jpegSizes().take(8).joinToString { "${it.width}x${it.height}" }}\n")
        return sb.toString()
    }

    companion object {
        fun facingName(f: Int) = when (f) {
            CameraMetadata.LENS_FACING_FRONT -> "front"
            CameraMetadata.LENS_FACING_BACK -> "back"
            else -> "external"
        }

        fun levelName(l: Int) = when (l) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            4 -> "EXTERNAL"
            else -> "?$l"
        }

        fun loadAll(manager: CameraManager): List<CameraInfo> {
            val out = mutableListOf<CameraInfo>()
            val ids = try { manager.cameraIdList } catch (e: Exception) { emptyArray<String>() }
            for (id in ids) {
                try {
                    val info = CameraInfo(id, manager.getCameraCharacteristics(id))
                    if (info.backwardCompatible && info.facing != CameraMetadata.LENS_FACING_EXTERNAL) out.add(info)
                } catch (e: Exception) { /* skip broken camera */ }
            }
            return out
        }
    }
}

/** A selectable lens: a camera id plus the zoom ratio to apply on it. */
data class LensOption(val cameraId: String, val zoomRatio: Float, val label: String, val relative: Float)

object LensCatalog {
    /** Builds lens buttons for the back side (e.g. 0.6x, 1x, 2x). */
    fun backLenses(cameras: List<CameraInfo>): List<LensOption> {
        val backs = cameras.filter { it.facing == CameraMetadata.LENS_FACING_BACK }
        if (backs.isEmpty()) return emptyList()
        val primary = backs.first()
        val out = mutableListOf<LensOption>()
        val zr = primary.zoomRange
        if (zr != null && zr.lower < 0.95f) {
            out.add(LensOption(primary.id, zr.lower, fmt(zr.lower), zr.lower))
        } else {
            // Separate ultra-wide camera id (common on older devices).
            val wide = backs.drop(1).filter { it.fovFactor < primary.fovFactor * 0.85f }.minByOrNull { it.fovFactor }
            if (wide != null) {
                val rel = wide.fovFactor / primary.fovFactor
                out.add(LensOption(wide.id, wide.minZoom.coerceAtLeast(1f), fmt(rel), rel))
            }
        }
        out.add(LensOption(primary.id, 1f, "1×", 1f))
        val maxZ = primary.maxZoom
        if (maxZ >= 2f) out.add(LensOption(primary.id, 2f, "2×", 2f))
        if (zr == null) {
            val tele = backs.drop(1).filter { it.fovFactor > primary.fovFactor * 1.6f }.minByOrNull { it.fovFactor }
            if (tele != null) {
                val rel = tele.fovFactor / primary.fovFactor
                out.add(LensOption(tele.id, 1f, fmt(rel), rel))
            }
        } else if (maxZ >= 5f) {
            out.add(LensOption(primary.id, 5f, "5×", 5f))
        }
        return out.sortedBy { it.relative }
    }

    fun frontLens(cameras: List<CameraInfo>): LensOption? {
        val f = cameras.firstOrNull { it.facing == CameraMetadata.LENS_FACING_FRONT } ?: return null
        val z = if ((f.zoomRange?.lower ?: 1f) <= 1f) 1f else f.zoomRange!!.lower
        return LensOption(f.id, z, "1×", 1f)
    }

    private fun fmt(v: Float): String {
        val r = (v * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) "${r.toInt()}×" else "$r×".replace("0.", ".")
    }
}
