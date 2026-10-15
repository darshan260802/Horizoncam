package com.darshan.horizoncam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class HorizonMathTest {

    private fun close(a: Double, b: Double, eps: Double = 1e-6) = assertEquals(b, a, eps)

    @Test
    fun wrapAndSnap() {
        close(HorizonMath.wrapDeg(190.0), -170.0)
        close(HorizonMath.wrapDeg(-180.0), 180.0)
        assertEquals(90, HorizonMath.snap90(80.0))
        assertEquals(180, HorizonMath.snap90(-170.0))
        assertEquals(0, HorizonMath.snap90(-44.0))
        // Hysteresis keeps the current orientation slightly past 45°.
        assertEquals(0, HorizonMath.snapWithHysteresis(0, 50.0))
        assertEquals(90, HorizonMath.snapWithHysteresis(0, 60.0))
    }

    @Test
    fun fullLockFitsInscribedCircle() {
        val s = HorizonMath.fitScale(1440.0, 1920.0, 1920.0, 1080.0, 0.0, 180.0)
        close(s, 1440.0 / hypot(1920.0, 1080.0))
    }

    @Test
    fun offModeLandscapeInSidewaysPhone() {
        // Portrait natural image, landscape frame, phone held sideways (base 90°): no crop needed.
        val s = HorizonMath.fitScale(1440.0, 1920.0, 1920.0, 1440.0, 90.0, 0.0)
        close(s, 1.0)
    }

    @Test
    fun levelRangeCropsLessThanFullLock() {
        val full = HorizonMath.fitScale(3000.0, 4000.0, 1080.0, 1920.0, 0.0, 180.0)
        val level = HorizonMath.fitScale(3000.0, 4000.0, 1080.0, 1920.0, 0.0, 30.0)
        val off = HorizonMath.fitScale(3000.0, 4000.0, 1080.0, 1920.0, 0.0, 0.0)
        assertTrue(full < level)
        assertTrue(level < off)
    }

    @Test
    fun rollDirection() {
        // Phone rotated clockwise by 20°: world up appears up-left in device coordinates.
        val a = Math.toRadians(20.0)
        close(FrameGeometry.rollDeg(-sin(a), cos(a)), 20.0)
        // Upright portrait.
        close(FrameGeometry.rollDeg(0.0, 1.0), 0.0)
        // Rotated counter-clockwise to landscape: up points along +x.
        close(FrameGeometry.rollDeg(1.0, 0.0), -90.0)
    }

    @Test
    fun outputUpMapsToWorldUp() {
        for (deg in listOf(-170.0, -90.0, -30.0, 0.0, 15.0, 90.0, 135.0)) {
            val m = FrameGeometry.outputToNatural(deg, 1.0, false)
            val x = m.mapX(0.0, 1.0)
            val y = m.mapY(0.0, 1.0)
            val r = Math.toRadians(deg)
            close(x, -sin(r))
            close(y, cos(r))
            // ...and that is exactly the world-up vector whose roll angle is `deg`.
            close(FrameGeometry.rollDeg(x, y), HorizonMath.wrapDeg(deg))
        }
    }

    @Test
    fun lockedFrameStaysInsideImageForAllAngles() {
        val wn = 2208.0
        val hn = 2944.0
        val wo = 1920.0
        val ho = 1080.0
        val s = HorizonMath.fitScale(wn, hn, wo, ho, 0.0, 180.0) * 0.995
        val toTex = FrameGeometry.naturalToTex(wn, hn)
        var deg = -180.0
        while (deg <= 180.0) {
            val m = FrameGeometry.encoderNdcToOutput(1920, 1080, false)
                .then(FrameGeometry.outputToNatural(deg, s, false)).then(toTex)
            for ((x, y) in listOf(-1.0 to -1.0, 1.0 to -1.0, -1.0 to 1.0, 1.0 to 1.0)) {
                val u = m.mapX(x, y)
                val v = m.mapY(x, y)
                assertTrue("corner outside at $deg°: $u,$v", u in 0.0..1.0 && v in 0.0..1.0)
            }
            deg += 2.5
        }
    }

    @Test
    fun levelModeClampsAngle() {
        close(HorizonMath.lockAngle(LockMode.LEVEL, 50.0, 0, 30.0), 30.0)
        close(HorizonMath.lockAngle(LockMode.LEVEL, 100.0, 90, 30.0), 100.0)
        close(HorizonMath.lockAngle(LockMode.OFF, 12.0, 0, 30.0), 0.0)
        close(HorizonMath.lockAngle(LockMode.LOCK_360, 170.0, 180, 30.0), 170.0)
    }

    @Test
    fun rotatedEncoderBuffer() {
        // Portrait 1080x1920 frame encoded as a 1920x1080 buffer that players rotate 90° clockwise.
        val m = FrameGeometry.encoderNdcToOutput(1920, 1080, true)
        // Top-centre of the buffer is displayed at the right-centre of the portrait frame.
        close(m.mapX(0.0, 1.0), 540.0)
        close(m.mapY(0.0, 1.0), 0.0)
        // Right-centre of the buffer ends up at the bottom-centre.
        close(m.mapX(1.0, 0.0), 0.0)
        close(m.mapY(1.0, 0.0), -960.0)
    }

    @Test
    fun affineInverseRoundTrip() {
        val m = Affine.rotate(33.0).then(Affine.scale(2.5, 1.5)).then(Affine.translate(4.0, -7.0))
        val inv = m.inverse()
        val x = m.mapX(3.0, 5.0)
        val y = m.mapY(3.0, 5.0)
        close(inv.mapX(x, y), 3.0)
        close(inv.mapY(x, y), 5.0)
    }

    @Test
    fun gimbalPreviewCentreAndFit() {
        // 1080x1440 preview area showing a 1440x1920 natural image.
        val k = FrameGeometry.gimbalFit(1440.0, 1920.0, 1080.0, 1440.0, 0)
        close(k, 1440.0 / 1080.0)
        val ndcToScreen = FrameGeometry.viewportNdcToScreen(1080, 2400, 540.0, 1200.0)
        val m = ndcToScreen.then(FrameGeometry.gimbalScreenToNatural(k, 0)).then(FrameGeometry.naturalToTex(1440.0, 1920.0))
        close(m.mapX(0.0, 0.0), 0.5)
        close(m.mapY(0.0, 0.0), 0.5)
        // Left edge of the screen is the left edge of the image.
        close(m.mapX(-1.0, 0.0), 0.0)
    }

    @Test
    fun frameSizes() {
        val l = HorizonMath.frameSize(1080, 16.0 / 9.0, true)
        assertEquals(1920, l[0]); assertEquals(1080, l[1])
        val p = HorizonMath.frameSize(1080, 16.0 / 9.0, false)
        assertEquals(1080, p[0]); assertEquals(1920, p[1])
        val sq = HorizonMath.frameSize(720, 1.0, true)
        assertEquals(720, sq[0]); assertEquals(720, sq[1])
    }
}
