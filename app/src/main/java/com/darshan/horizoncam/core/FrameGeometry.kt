package com.darshan.horizoncam.core

import kotlin.math.atan2
import kotlin.math.max

/**
 * Builds the coordinate mappings used by the renderer.
 *
 * Spaces (all centred, y up, in pixels unless noted):
 *  - natural: camera image in the device's natural orientation (Wn x Hn)
 *  - output: the recorded frame (Wo x Ho); "up" is the world horizon's up when locked
 *  - screen: preview viewport, relative to the centre of the preview area
 *  - tex:    natural image texture coordinates in [0,1] (before the SurfaceTexture matrix)
 *  - frame:  output frame normalised to [-1,1]
 */
object FrameGeometry {

    /** Roll angle of the device (degrees) from the "up" vector expressed in device coordinates. */
    fun rollDeg(ux: Double, uy: Double): Double = Math.toDegrees(atan2(-ux, uy))

    /** output px -> natural px: Q = s * Rot(gamma) * F * P */
    fun outputToNatural(gammaDeg: Double, scale: Double, flipX: Boolean): Affine =
        Affine.scale(if (flipX) -1.0 else 1.0, 1.0)
            .then(Affine.rotate(gammaDeg))
            .then(Affine.scale(scale))

    fun naturalToTex(wn: Double, hn: Double): Affine =
        Affine.scale(1.0 / wn, 1.0 / hn).then(Affine.translate(0.5, 0.5))

    fun outputToFrame(wo: Double, ho: Double): Affine = Affine.scale(2.0 / wo, 2.0 / ho)

    /** Encoder NDC -> output px. When the encoder buffer is rotated (portrait fallback) the player rotates it 90° CW. */
    fun encoderNdcToOutput(encW: Int, encH: Int, rotate90: Boolean): Affine {
        val toPx = Affine.scale(encW / 2.0, encH / 2.0)
        return if (rotate90) toPx.then(Affine.rotate(-90.0)) else toPx
    }

    /** Viewport NDC -> screen px relative to the preview area centre (GL coordinates, y up). */
    fun viewportNdcToScreen(surfW: Int, surfH: Int, centerXGl: Double, centerYGl: Double): Affine =
        Affine(surfW / 2.0, 0.0, 0.0, surfH / 2.0, surfW / 2.0 - centerXGl, surfH / 2.0 - centerYGl)

    /** Scale (natural px per screen px) that fits the whole natural image into the area. */
    fun gimbalFit(wn: Double, hn: Double, areaW: Double, areaH: Double, displayDeg: Int): Double {
        val q = HorizonMath.isQuarterTurn(displayDeg)
        val w = if (q) hn else wn
        val h = if (q) wn else hn
        return max(w / areaW, h / areaH)
    }

    /** screen px -> natural px for the gimbal (full view) preview. */
    fun gimbalScreenToNatural(k: Double, displayDeg: Int): Affine =
        Affine.rotate(-displayDeg.toDouble()).then(Affine.scale(k))

    /** Scale (output px per screen px) that fits the (rotated) output frame into the area. */
    fun outputFit(wo: Double, ho: Double, areaW: Double, areaH: Double, uprightDeg: Int, displayDeg: Int): Double {
        val q = HorizonMath.isQuarterTurn(uprightDeg + displayDeg)
        val w = if (q) ho else wo
        val h = if (q) wo else ho
        return max(w / areaW, h / areaH)
    }

    /** screen px -> output px for the output preview, rotated so the frame is upright for the user. */
    fun outputScreenToOutput(c: Double, uprightDeg: Int, displayDeg: Int): Affine =
        Affine.rotate(-(uprightDeg + displayDeg).toDouble()).then(Affine.scale(c))
}
