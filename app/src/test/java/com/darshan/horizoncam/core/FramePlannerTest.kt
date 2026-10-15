package com.darshan.horizoncam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * End-to-end checks of the frame planner against a simulated phone:
 * the phone is rolled by `alpha` degrees (clockwise, as seen by the user), the camera
 * image is produced in the device's natural orientation exactly like Camera2 +
 * SurfaceTexture would, and we verify that the recorded frame's up/right axes
 * line up with the real world.
 */
class FramePlannerTest {

    /** SurfaceTexture matrix for a back camera with sensor orientation 90 (rotation + GL y flip). */
    private val backSt = floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    /** Front camera, sensor orientation 270, mirrored by the framework (extra reflection: det > 0). */
    private val frontMirroredSt = floatArrayOf(0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    /** Front camera without framework mirroring (det < 0). */
    private val frontTrueSt = floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    private fun rot(deg: Double, x: Double, y: Double): DoubleArray {
        val r = Math.toRadians(deg)
        return doubleArrayOf(x * cos(r) - y * sin(r), x * sin(r) + y * cos(r))
    }

    private fun input(
        st: FloatArray, front: Boolean, alpha: Double, cfg: RenderConfig,
        recording: Boolean = true, encW: Int = 1920, encH: Int = 1080, rot90: Boolean = false
    ): FrameInput {
        // Device "up" vector for a phone rolled clockwise by alpha.
        val up = rot(alpha, 0.0, 1.0)
        val roll = FrameGeometry.rollDeg(up[0], up[1])
        val beta = HorizonMath.snap90(roll)
        return FrameInput(
            st = st, bufW = 2944, bufH = 2208, isFront = front, cfg = cfg,
            rollDev = roll, betaDev = beta, recording = recording, lockedBetaDev = beta,
            encW = encW, encH = encH, encRot90 = rot90,
            surfW = 1080, surfH = 2400, areaLeft = 0.0, areaTop = 300.0, areaRight = 1080.0, areaBottom = 300.0 + 1440.0,
            displayDeg = 0
        )
    }

    /**
     * Maps an encoder NDC direction to a direction in the camera's world view.
     * World view = what the camera sees, x to the camera's right, y up.
     */
    private fun worldDir(plan: FramePlan, i: FrameInput, alpha: Double, dx: Double, dy: Double): DoubleArray {
        val m = plan.encoderMap!!
        val x0 = m.mapX(0.0, 0.0); val y0 = m.mapY(0.0, 0.0)
        val x1 = m.mapX(dx, dy); val y1 = m.mapY(dx, dy)
        // natural texture delta -> natural pixel delta
        val nx = (x1 - x0) * plan.wn
        val ny = (y1 - y0) * plan.hn
        // natural -> device coordinates (front camera preview is mirrored, i.e. device aligned)
        val det = i.st[0] * i.st[5] - i.st[4] * i.st[1]
        val deviceAligned = if (i.isFront) det > 0 else det < 0
        val dX = if (deviceAligned) nx else -nx
        // device -> user's world (undo the clockwise roll)
        val w = rot(-alpha, dX, ny)
        // The front camera looks back at the user: its right is the user's left.
        return if (i.isFront) doubleArrayOf(-w[0], w[1]) else w
    }

    private fun assertDir(d: DoubleArray, ex: Double, ey: Double, msg: String) {
        val n = hypot(d[0], d[1])
        assertEquals("$msg x", ex, d[0] / n, 1e-6)
        assertEquals("$msg y", ey, d[1] / n, 1e-6)
    }

    @Test
    fun lock360KeepsWorldUpAndRightBackCamera() {
        val cfg = RenderConfig(lockMode = LockMode.LOCK_360, orientation = FrameOrientation.LANDSCAPE)
        for (alpha in listOf(-175.0, -120.0, -90.0, -33.0, 0.0, 12.0, 45.0, 90.0, 150.0, 180.0)) {
            val i = input(backSt, false, alpha, cfg)
            val p = FramePlanner.plan(i)!!
            assertDir(worldDir(p, i, alpha, 0.0, 1.0), 0.0, 1.0, "up@$alpha")
            assertDir(worldDir(p, i, alpha, 1.0, 0.0), 1.0, 0.0, "right@$alpha")
        }
    }

    @Test
    fun lock360FrontCameraSavesTrueView() {
        val cfg = RenderConfig(lockMode = LockMode.LOCK_360, orientation = FrameOrientation.LANDSCAPE, mirrorFront = false)
        for (st in listOf(frontMirroredSt, frontTrueSt)) {
            for (alpha in listOf(-150.0, -60.0, 0.0, 25.0, 90.0, 170.0)) {
                val i = input(st, true, alpha, cfg)
                val p = FramePlanner.plan(i)!!
                assertDir(worldDir(p, i, alpha, 0.0, 1.0), 0.0, 1.0, "up@$alpha")
                assertDir(worldDir(p, i, alpha, 1.0, 0.0), 1.0, 0.0, "right@$alpha")
            }
        }
    }

    @Test
    fun mirrorFrontSettingMirrorsOutput() {
        val cfg = RenderConfig(lockMode = LockMode.LOCK_360, orientation = FrameOrientation.LANDSCAPE, mirrorFront = true)
        val i = input(frontMirroredSt, true, 30.0, cfg)
        val p = FramePlanner.plan(i)!!
        assertDir(worldDir(p, i, 30.0, 0.0, 1.0), 0.0, 1.0, "up")
        assertDir(worldDir(p, i, 30.0, 1.0, 0.0), -1.0, 0.0, "right")
    }

    @Test
    fun offModeFollowsSnappedOrientation() {
        val cfg = RenderConfig(lockMode = LockMode.OFF, orientation = FrameOrientation.AUTO)
        // Phone held sideways (rolled 80° clockwise): frame is landscape, rotated a full 90°,
        // so the remaining 10° tilt is NOT corrected (like a normal camera).
        val alpha = 80.0
        val i = input(backSt, false, alpha, cfg)
        val p = FramePlanner.plan(i)!!
        assertEquals(1920.0, p.wo, 0.0)
        val up = worldDir(p, i, alpha, 0.0, 1.0)
        val expected = rot(-(alpha - 90.0), 0.0, 1.0)
        assertDir(up, expected[0], expected[1], "up")
    }

    @Test
    fun levelModeClampsTilt() {
        val cfg = RenderConfig(lockMode = LockMode.LEVEL, levelRange = 30.0, orientation = FrameOrientation.AUTO)
        // 20° tilt is fully corrected...
        var i = input(backSt, false, 20.0, cfg, encW = 1080, encH = 1920)
        var p = FramePlanner.plan(i)!!
        assertDir(worldDir(p, i, 20.0, 0.0, 1.0), 0.0, 1.0, "up@20")
        // ...40° is corrected by 30°, leaving 10°.
        i = input(backSt, false, 40.0, cfg, encW = 1080, encH = 1920)
        p = FramePlanner.plan(i)!!
        val e = rot(-10.0, 0.0, 1.0)
        assertDir(worldDir(p, i, 40.0, 0.0, 1.0), e[0], e[1], "up@40")
    }

    @Test
    fun rotatedEncoderStillLevel() {
        // Portrait frame stored as a landscape buffer + 90° rotation hint.
        val cfg = RenderConfig(lockMode = LockMode.LOCK_360, orientation = FrameOrientation.PORTRAIT)
        val alpha = 37.0
        val i = input(backSt, false, alpha, cfg, encW = 1920, encH = 1080, rot90 = true)
        val p = FramePlanner.plan(i)!!
        assertEquals(1080.0, p.wo, 0.0)
        assertEquals(1920.0, p.ho, 0.0)
        // Buffer "right" (+x) is displayed pointing down after the player's 90° CW rotation.
        assertDir(worldDir(p, i, alpha, 1.0, 0.0), 0.0, -1.0, "buffer right")
        assertDir(worldDir(p, i, alpha, 0.0, 1.0), 1.0, 0.0, "buffer up")
    }

    @Test
    fun gimbalPreviewFrameIsCentredAndInside() {
        val cfg = RenderConfig(lockMode = LockMode.LOCK_360, orientation = FrameOrientation.LANDSCAPE, previewStyle = PreviewStyle.GIMBAL)
        val i = input(backSt, false, 33.0, cfg, recording = false)
        val p = FramePlanner.plan(i)!!
        val f = p.previewFrame!!
        // Centre of the preview area maps to the frame centre.
        val cyNdc = 1.0 - 2.0 * (300.0 + 720.0) / 2400.0
        assertEquals(0.0, f.mapX(0.0, cyNdc), 1e-9)
        assertEquals(0.0, f.mapY(0.0, cyNdc), 1e-9)
        // The view->natural mapping agrees with the preview mapping at the area centre.
        val v = p.viewToNatural!!
        assertEquals(0.5, v.mapX(540.0, 1020.0), 1e-9)
        assertEquals(0.5, v.mapY(540.0, 1020.0), 1e-9)
    }

    @Test
    fun outputPreviewIsUprightForUser() {
        val cfg = RenderConfig(lockMode = LockMode.LOCK_360, orientation = FrameOrientation.LANDSCAPE, previewStyle = PreviewStyle.OUTPUT)
        // Phone held sideways (rolled 90° clockwise). The frame's up must point to the user's up,
        // which on the screen is device -x.
        val i = input(backSt, false, 90.0, cfg, recording = false)
        val p = FramePlanner.plan(i)!!
        val pf = p.previewFrame!!
        // screen direction (NDC) that maps to frame +y
        val inv = pf.inverse()
        val dx = inv.mapX(0.0, 1.0) - inv.mapX(0.0, 0.0)
        val dy = inv.mapY(0.0, 1.0) - inv.mapY(0.0, 0.0)
        assertTrue("frame up should point to screen -x, got $dx,$dy", dx < 0 && kotlin.math.abs(dy) < 1e-6)
    }

    @Test
    fun photoPlanStraightens() {
        val cfg = RenderConfig(lockMode = LockMode.LOCK_360, orientation = FrameOrientation.LANDSCAPE, aspect = 4.0 / 3.0)
        val alpha = 25.0
        val up = rot(alpha, 0.0, 1.0)
        val plan = FramePlanner.planPhoto(3000, 4000, cfg, FrameGeometry.rollDeg(up[0], up[1]), 0, false)
        assertTrue(plan.width > plan.height)
        // Output "up" (y down: (0,-1)) must map to world up in the image (y down).
        val m = plan.outToImage
        val dx = m.mapX(0.0, -1.0) - m.mapX(0.0, 0.0)
        val dy = m.mapY(0.0, -1.0) - m.mapY(0.0, 0.0)
        // World up in natural image (y up) is `up`; in y-down image coordinates it is (up.x, -up.y).
        assertEquals(up[0], dx, 1e-9)
        assertEquals(-up[1], dy, 1e-9)
        // All output corners stay inside the image.
        for ((x, y) in listOf(0.0 to 0.0, plan.width.toDouble() to 0.0, 0.0 to plan.height.toDouble(), plan.width.toDouble() to plan.height.toDouble())) {
            val ix = m.mapX(x, y); val iy = m.mapY(x, y)
            assertTrue("corner $ix,$iy", ix in 0.0..3000.0 && iy in 0.0..4000.0)
        }
    }
}
