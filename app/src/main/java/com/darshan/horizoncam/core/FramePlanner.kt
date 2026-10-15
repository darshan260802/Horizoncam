package com.darshan.horizoncam.core

import kotlin.math.abs
import kotlin.math.hypot

/** Immutable rendering configuration, swapped atomically from the UI thread. */
data class RenderConfig(
    val lockMode: LockMode = LockMode.LOCK_360,
    val levelRange: Double = 30.0,
    val orientation: FrameOrientation = FrameOrientation.AUTO,
    val aspect: Double = 16.0 / 9.0,
    val outShort: Int = 1080,
    val smooth: Boolean = false,
    val previewStyle: PreviewStyle = PreviewStyle.GIMBAL,
    val grid: Boolean = false,
    val mirrorFront: Boolean = false,
    /** Unsharp-mask strength applied while resampling (0 = off). */
    val sharpness: Double = 0.3,
    /**
     * Crop exactly for the current angle instead of the worst case of the lock range.
     * Used for photos: a still only needs to be straightened for the moment it is taken.
     */
    val dynamicCrop: Boolean = false
)

/** Everything needed to plan one frame. Plain data so the logic can be unit tested on the JVM. */
class FrameInput(
    /** SurfaceTexture transform matrix (column-major 4x4). */
    val st: FloatArray,
    val bufW: Int,
    val bufH: Int,
    val isFront: Boolean,
    val cfg: RenderConfig,
    /** Device roll in degrees (see [FrameGeometry.rollDeg]). */
    val rollDev: Double,
    /** Live snapped device orientation. */
    val betaDev: Int,
    val recording: Boolean,
    val lockedBetaDev: Int,
    val encW: Int,
    val encH: Int,
    val encRot90: Boolean,
    val surfW: Int,
    val surfH: Int,
    /** Preview area in view coordinates (y down). */
    val areaLeft: Double,
    val areaTop: Double,
    val areaRight: Double,
    val areaBottom: Double,
    val displayDeg: Int
)

class FramePlan(
    val wn: Double,
    val hn: Double,
    val wo: Double,
    val ho: Double,
    val gammaDeg: Double,
    val scale: Double,
    val flip: Boolean,
    val trueView: Boolean,
    val cropZoom: Double,
    /** Preview viewport NDC -> natural texture coordinates. */
    val previewMap: Affine?,
    /** Preview viewport NDC -> output frame coordinates ([-1,1] inside). */
    val previewFrame: Affine?,
    /** View pixel (y down) -> natural texture coordinates. */
    val viewToNatural: Affine?,
    val previewOutside: Float,
    val previewBorder: Float,
    val lineX: Float,
    val lineY: Float,
    /** Encoder NDC -> natural texture coordinates. */
    val encoderMap: Affine?,
    val encoderFrame: Affine?
)

object FramePlanner {

    fun plan(i: FrameInput): FramePlan? {
        val st = i.st
        val cfg = i.cfg
        val det = st[0] * st[5] - st[4] * st[1]
        // Back camera: image x == device x when the transform keeps GL's usual y-flip (det < 0).
        // Front camera: the framework mirrors the preview, which also makes image x == device x.
        val deviceAligned = if (i.isFront) det > 0 else det < 0
        val sigma = if (deviceAligned) 1 else -1
        val trueView = if (i.isFront) !deviceAligned else deviceAligned
        val flip = trueView == (i.isFront && cfg.mirrorFront)
        val swapped = abs(st[0]) < abs(st[4])
        val wn = (if (swapped) i.bufH else i.bufW).toDouble()
        val hn = (if (swapped) i.bufW else i.bufH).toDouble()
        if (wn <= 0 || hn <= 0) return null

        val baseDev = if (i.recording) i.lockedBetaDev else i.betaDev
        val rollImg = sigma * i.rollDev
        val baseImg = HorizonMath.snap90((sigma * baseDev).toDouble())

        val wo: Double
        val ho: Double
        if (i.recording && i.encW > 0) {
            wo = (if (i.encRot90) i.encH else i.encW).toDouble()
            ho = (if (i.encRot90) i.encW else i.encH).toDouble()
        } else {
            val landscape = when (cfg.orientation) {
                FrameOrientation.AUTO -> HorizonMath.isQuarterTurn(baseDev)
                FrameOrientation.LANDSCAPE -> true
                FrameOrientation.PORTRAIT -> false
            }
            val fs = HorizonMath.frameSize(cfg.outShort, cfg.aspect, landscape)
            wo = fs[0].toDouble(); ho = fs[1].toDouble()
        }
        val gamma = HorizonMath.lockAngle(cfg.lockMode, rollImg, baseImg, cfg.levelRange)
        val cropRange = HorizonMath.cropRange(cfg.lockMode, cfg.levelRange)
        val scale = if (cfg.dynamicCrop && !i.recording) {
            HorizonMath.fitScale(wn, hn, wo, ho, gamma, 0.0) * 0.995
        } else {
            HorizonMath.fitScale(wn, hn, wo, ho, baseImg.toDouble(), cropRange) * 0.995
        }
        val outToNat = FrameGeometry.outputToNatural(gamma, scale, flip)
        val natToTex = FrameGeometry.naturalToTex(wn, hn)
        val outToFrame = FrameGeometry.outputToFrame(wo, ho)

        var previewMap: Affine? = null
        var previewFrame: Affine? = null
        var viewToNatural: Affine? = null
        var outside = 1f
        var border = 0f
        var lineX = 0f
        var lineY = 0f
        if (i.surfW > 0 && i.surfH > 0) {
            val hasArea = i.areaRight - i.areaLeft > 1 && i.areaBottom - i.areaTop > 1
            val areaW = if (hasArea) i.areaRight - i.areaLeft else i.surfW.toDouble()
            val areaH = if (hasArea) i.areaBottom - i.areaTop else i.surfH.toDouble()
            val cxView = if (hasArea) (i.areaLeft + i.areaRight) / 2 else i.surfW / 2.0
            val cyView = if (hasArea) (i.areaTop + i.areaBottom) / 2 else i.surfH / 2.0
            val ndcToScreen = FrameGeometry.viewportNdcToScreen(i.surfW, i.surfH, cxView, i.surfH - cyView)
            val viewToScreen = Affine(1.0, 0.0, 0.0, -1.0, -cxView, cyView)
            val screenToNat: Affine
            if (cfg.previewStyle == PreviewStyle.GIMBAL) {
                val k = FrameGeometry.gimbalFit(wn, hn, areaW, areaH, i.displayDeg)
                screenToNat = FrameGeometry.gimbalScreenToNatural(k, i.displayDeg)
                val ndcToNat = ndcToScreen.then(screenToNat)
                previewMap = ndcToNat.then(natToTex)
                previewFrame = ndcToNat.then(outToNat.inverse()).then(outToFrame)
                outside = 0.32f
                border = 1f
                lineX = (1.6 / (wo / 2 * scale / k)).toFloat()
                lineY = (1.6 / (ho / 2 * scale / k)).toFloat()
            } else {
                val c = FrameGeometry.outputFit(wo, ho, areaW, areaH, i.betaDev, i.displayDeg)
                val screenToOut = FrameGeometry.outputScreenToOutput(c, i.betaDev, i.displayDeg)
                screenToNat = screenToOut.then(outToNat)
                val ndcToOut = ndcToScreen.then(screenToOut)
                previewMap = ndcToOut.then(outToNat).then(natToTex)
                previewFrame = ndcToOut.then(outToFrame)
                outside = 0f
                border = 0f
                lineX = (1.2 / (wo / 2 / c)).toFloat()
                lineY = (1.2 / (ho / 2 / c)).toFloat()
            }
            viewToNatural = viewToScreen.then(screenToNat).then(natToTex)
        }

        var encoderMap: Affine? = null
        var encoderFrame: Affine? = null
        if (i.recording && i.encW > 0 && i.encH > 0) {
            val ndcToOut = FrameGeometry.encoderNdcToOutput(i.encW, i.encH, i.encRot90)
            encoderMap = ndcToOut.then(outToNat).then(natToTex)
            encoderFrame = ndcToOut.then(outToFrame)
        }

        val cropZoom = hypot(wn, hn) / (scale * hypot(wo, ho))
        return FramePlan(
            wn, hn, wo, ho, gamma, scale, flip, trueView, cropZoom,
            previewMap, previewFrame, viewToNatural, outside, border, lineX, lineY,
            encoderMap, encoderFrame
        )
    }

    /**
     * Texture-space step of one viewport pixel along x and y, written to [out] as
     * (dxS, dxT, dyS, dyT). [ndcToNat] maps viewport NDC to natural texture coordinates
     * and [st] is the SurfaceTexture matrix (column-major) applied after it.
     */
    fun pixelSteps(ndcToNat: Affine, st: FloatArray, vpW: Int, vpH: Int, out: FloatArray) {
        val mxX = ndcToNat.a * 2.0 / vpW
        val myX = ndcToNat.c * 2.0 / vpW
        val mxY = ndcToNat.b * 2.0 / vpH
        val myY = ndcToNat.d * 2.0 / vpH
        out[0] = (st[0] * mxX + st[4] * myX).toFloat()
        out[1] = (st[1] * mxX + st[5] * myX).toFloat()
        out[2] = (st[0] * mxY + st[4] * myY).toFloat()
        out[3] = (st[1] * mxY + st[5] * myY).toFloat()
    }

    /** Supersampling taps (1, 2 or 3 per axis) for a given texels-per-pixel footprint. */
    fun tapsFor(steps: FloatArray, texW: Int, texH: Int): Int {
        val rx = hypot(steps[0] * texW.toDouble(), steps[1] * texH.toDouble())
        val ry = hypot(steps[2] * texW.toDouble(), steps[3] * texH.toDouble())
        val r = maxOf(rx, ry)
        return when {
            r < 1.15 -> 1
            r < 2.2 -> 2
            else -> 3
        }
    }

    /**
     * Photo straightening: maps output pixels (y down, origin top-left) to pixels of the
     * natural-orientation, true-view JPEG (y down). Returns the transform and output size.
     */
    class PhotoPlan(val outToImage: Affine, val width: Int, val height: Int, val gammaDeg: Double, val flip: Boolean)

    fun planPhoto(
        wn: Int, hn: Int, cfg: RenderConfig, rollDev: Double, betaDev: Int, isFront: Boolean
    ): PhotoPlan {
        // A JPEG is the true (un-mirrored) view: for the front camera its x axis is opposite to the device's.
        val sigma = if (isFront) -1 else 1
        val rollImg = sigma * rollDev
        val baseImg = HorizonMath.snap90((sigma * betaDev).toDouble())
        val landscape = when (cfg.orientation) {
            FrameOrientation.AUTO -> HorizonMath.isQuarterTurn(betaDev)
            FrameOrientation.LANDSCAPE -> true
            FrameOrientation.PORTRAIT -> false
        }
        val gamma = HorizonMath.lockAngle(cfg.lockMode, rollImg, baseImg, cfg.levelRange)
        val unitW = if (landscape) cfg.aspect else 1.0
        val unitH = if (landscape) 1.0 else cfg.aspect
        // A photo only has to be straightened for its own angle, so crop exactly for it
        // (much larger than the worst-case crop a video needs to stay stable).
        val s = HorizonMath.fitScale(wn.toDouble(), hn.toDouble(), unitW, unitH, gamma, 0.0) * 0.995
        val wo = maxOf(16, (s * unitW).toInt())
        val ho = maxOf(16, (s * unitH).toInt())
        val flip = isFront && cfg.mirrorFront
        // y-down output -> y-up centred -> natural (y-up centred) -> y-down image.
        val m = Affine(1.0, 0.0, 0.0, -1.0, -wo / 2.0, ho / 2.0)
            .then(FrameGeometry.outputToNatural(gamma, 1.0, flip))
            .then(Affine(1.0, 0.0, 0.0, -1.0, wn / 2.0, hn / 2.0))
        return PhotoPlan(m, wo, ho, gamma, flip)
    }
}
