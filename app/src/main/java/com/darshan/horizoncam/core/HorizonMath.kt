package com.darshan.horizoncam.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 2D affine transform: x' = a*x + b*y + tx, y' = c*x + d*y + ty.
 * All horizon-lock geometry is expressed with these (y axis pointing up).
 */
class Affine(
    @JvmField var a: Double = 1.0,
    @JvmField var b: Double = 0.0,
    @JvmField var c: Double = 0.0,
    @JvmField var d: Double = 1.0,
    @JvmField var tx: Double = 0.0,
    @JvmField var ty: Double = 0.0
) {
    /** Returns the transform that applies `this` first and then [o]. */
    fun then(o: Affine): Affine = Affine(
        o.a * a + o.b * c,
        o.a * b + o.b * d,
        o.c * a + o.d * c,
        o.c * b + o.d * d,
        o.a * tx + o.b * ty + o.tx,
        o.c * tx + o.d * ty + o.ty
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return Affine()
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    fun mapX(x: Double, y: Double): Double = a * x + b * y + tx
    fun mapY(x: Double, y: Double): Double = c * x + d * y + ty

    /** Writes this transform as a column-major 4x4 GL matrix. */
    fun toGl(out: FloatArray) {
        out.fill(0f)
        out[0] = a.toFloat(); out[1] = c.toFloat()
        out[4] = b.toFloat(); out[5] = d.toFloat()
        out[10] = 1f
        out[12] = tx.toFloat(); out[13] = ty.toFloat()
        out[15] = 1f
    }

    override fun toString(): String = "Affine(a=%.4f b=%.4f c=%.4f d=%.4f tx=%.4f ty=%.4f)".format(a, b, c, d, tx, ty)

    companion object {
        fun scale(sx: Double, sy: Double = sx) = Affine(sx, 0.0, 0.0, sy)
        fun translate(x: Double, y: Double) = Affine(tx = x, ty = y)

        /** Counter-clockwise rotation (y up). */
        fun rotate(deg: Double): Affine {
            val r = Math.toRadians(deg)
            val cs = cos(r)
            val sn = sin(r)
            // Snap tiny values so 90° steps are exact.
            val c2 = if (abs(cs) < 1e-12) 0.0 else cs
            val s2 = if (abs(sn) < 1e-12) 0.0 else sn
            return Affine(c2, -s2, s2, c2)
        }
    }
}

object HorizonMath {

    /** Wraps an angle into (-180, 180]. */
    fun wrapDeg(a: Double): Double {
        var x = a % 360.0
        if (x > 180.0) x -= 360.0
        if (x <= -180.0) x += 360.0
        return x
    }

    /** Nearest multiple of 90 in {-90, 0, 90, 180}. */
    fun snap90(a: Double): Int {
        val w = wrapDeg(a)
        val s = (w / 90.0).roundToInt() * 90
        return if (s == -180) 180 else s
    }

    /** Snapped orientation with hysteresis so the frame does not flicker around 45°. */
    fun snapWithHysteresis(current: Int, angle: Double, hysteresisDeg: Double = 12.0): Int {
        val diff = abs(wrapDeg(angle - current))
        return if (diff > 45.0 + hysteresisDeg) snap90(angle) else current
    }

    fun isQuarterTurn(deg: Int): Boolean = Math.floorMod(deg, 180) == 90

    /**
     * Largest scale `s` (image pixels per output pixel) such that an output rectangle of
     * `wo x ho` rotated by every angle in `[base - range, base + range]` around the image centre
     * stays fully inside a `wn x hn` image. `range >= 180` means a full 360° lock, where the
     * rectangle has to fit inside the image's inscribed circle.
     */
    fun fitScale(wn: Double, hn: Double, wo: Double, ho: Double, baseDeg: Double, rangeDeg: Double): Double {
        if (wn <= 0 || hn <= 0 || wo <= 0 || ho <= 0) return 1.0
        if (rangeDeg >= 180.0) return min(wn, hn) / hypot(wo, ho)
        var best = Double.MAX_VALUE
        fun eval(deg: Double) {
            val r = Math.toRadians(deg)
            val c = abs(cos(r))
            val s = abs(sin(r))
            val sx = wn / (wo * c + ho * s)
            val sy = hn / (wo * s + ho * c)
            best = min(best, min(sx, sy))
        }
        val range = max(0.0, rangeDeg)
        if (range == 0.0) {
            eval(baseDeg)
            return best
        }
        val steps = max(8, (range * 8).toInt())
        for (i in 0..steps) eval(baseDeg - range + 2.0 * range * i / steps)
        // Critical angles where the bounding box is largest.
        val crit1 = Math.toDegrees(atan(ho / wo))
        val crit2 = Math.toDegrees(atan(wo / ho))
        for (k in -4..4) {
            for (cr in doubleArrayOf(crit1, crit2)) {
                val ang = cr + 90.0 * k
                if (abs(wrapDeg(ang - baseDeg)) <= range) eval(ang)
            }
        }
        return best
    }

    /** Effective rotation (image space) applied for a given lock mode. */
    fun lockAngle(mode: LockMode, fullDeg: Double, baseDeg: Int, rangeDeg: Double): Double = when (mode) {
        LockMode.LOCK_360 -> fullDeg
        LockMode.OFF -> baseDeg.toDouble()
        LockMode.LEVEL -> {
            val dev = wrapDeg(fullDeg - baseDeg)
            baseDeg + dev.coerceIn(-rangeDeg, rangeDeg)
        }
    }

    /** Range used for crop computation for a lock mode. */
    fun cropRange(mode: LockMode, levelRange: Double): Double = when (mode) {
        LockMode.LOCK_360 -> 180.0
        LockMode.LEVEL -> levelRange
        LockMode.OFF -> 0.0
    }

    /** Even integer helper for encoder sizes. */
    fun even(v: Double): Int = max(2, (floor(v / 2.0) * 2.0).toInt())

    /**
     * Output frame size for a preset.
     * @param shortSide e.g. 1080 for 1080p
     * @param aspect long/short ratio (16/9, 4/3, 1)
     */
    fun frameSize(shortSide: Int, aspect: Double, landscape: Boolean): IntArray {
        val longSide = even(shortSide * aspect)
        val shortE = even(shortSide.toDouble())
        return if (landscape) intArrayOf(longSide, shortE) else intArrayOf(shortE, longSide)
    }
}

enum class LockMode { LOCK_360, LEVEL, OFF }
