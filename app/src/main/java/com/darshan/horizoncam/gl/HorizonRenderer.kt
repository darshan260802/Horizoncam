package com.darshan.horizoncam.gl

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.darshan.horizoncam.core.Affine
import com.darshan.horizoncam.core.FrameGeometry
import com.darshan.horizoncam.core.FrameInput
import com.darshan.horizoncam.core.FramePlanner
import com.darshan.horizoncam.core.HorizonMath
import com.darshan.horizoncam.core.LockMode
import com.darshan.horizoncam.core.RenderConfig
import com.darshan.horizoncam.sensor.HorizonSensor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/** Snapshot of the renderer state for the UI (read from any thread). */
class RenderState(
    /** Device roll in degrees (device coordinates, 0 = upright portrait). */
    val rollDeg: Double,
    /** Snapped device orientation (-90, 0, 90, 180) used to rotate UI elements. */
    val betaDeg: Int,
    /** Deviation of the device from the nearest 90° orientation. */
    val tiltDeg: Double,
    /** Effective zoom caused by the horizon crop (diagonal ratio). */
    val cropZoom: Double,
    val frameW: Int,
    val frameH: Int,
    /** Camera points (almost) straight up or down; the horizon is undefined. */
    val unstable: Boolean,
    val fps: Float,
    val inputW: Int,
    val inputH: Int
)

/** Encoder hooks. Called on the GL thread. */
interface EncoderSink {
    /** First frame was encoded; [monoNs] is its capture time in System.nanoTime() base. */
    fun onFirstFrame(monoNs: Long)
    fun onFrameEncoded(ptsNs: Long)
}

/**
 * Owns the GL thread, the camera SurfaceTexture, the preview window surface and
 * (while recording) the encoder input surface. For every camera frame it looks up
 * the device orientation at the frame's exposure time and draws the horizon-locked
 * crop to the encoder and an informative preview to the screen.
 */
class HorizonRenderer(private val sensor: HorizonSensor) : SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("HorizonGL").also { it.start() }
    private val handler = Handler(thread.looper)

    // --- GL-thread state ---
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: HorizonProgram? = null
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var bufW = 0
    private var bufH = 0

    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0
    private var previewH = 0

    private var encEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encW = 0
    private var encH = 0
    private var encRot90 = false
    private var encSink: EncoderSink? = null
    private var encTimelapse = 1
    private var encFps = 30
    private var encFrameCounter = 0L
    private var encCount = 0L
    private var encT0Mono = -1L
    private var lastPts = -1L
    private var lockedBetaDev = 0
    private var recording = false

    private val stMatrix = FloatArray(16)
    private val up = FloatArray(3)
    private val params = HorizonProgram.Params()

    private var filtX = 0.0
    private var filtY = 1.0
    private var hasFilt = false
    private var lastFrameRt = 0L
    private var betaDev = 0
    private var betaInit = false
    private var camClockRealtime = true
    private var camClockChecked = false

    private var fpsCount = 0
    private var fpsStart = 0L
    private var fps = 0f

    // --- shared state ---
    @Volatile var config = RenderConfig()
    @Volatile var isFront = false
    @Volatile private var area = RectF()
    @Volatile private var displayDeg = 0
    /** Half exposure + half rolling shutter skew, updated by the camera. */
    @Volatile var exposureMidNs = 10_000_000L
    @Volatile var state: RenderState? = null; private set
    @Volatile private var viewToNatural: Affine? = null
    /** Whether the preview's natural image is the un-mirrored ("true") view. */
    @Volatile var naturalTrueView = true; private set
    @Volatile var error: String? = null; private set
    @Volatile var frameListener: (() -> Unit)? = null
    /** Called (GL thread) when the first frame of a new camera session reaches the preview. */
    @Volatile var sessionFrameListener: (() -> Unit)? = null
    @Volatile private var notifySessionFrame = false

    init {
        runSync {
            try {
                val core = EglCore()
                egl = core
                pbuffer = core.createPbufferSurface(1, 1)
                core.makeCurrent(pbuffer)
                program = HorizonProgram()
                texId = HorizonProgram.createOesTexture()
                val st = SurfaceTexture(texId)
                st.setOnFrameAvailableListener(this, handler)
                surfaceTexture = st
            } catch (e: Exception) {
                Log.e(TAG, "GL init failed", e)
                error = "OpenGL init failed: ${e.message}"
            }
        }
    }

    // ---------------------------------------------------------------- public API

    /** Creates (or recreates) the camera input surface with the given buffer size. */
    fun prepareInputSurface(w: Int, h: Int): Surface? {
        var result: Surface? = null
        runSync {
            val st = surfaceTexture ?: return@runSync
            inputSurface?.release()
            st.setDefaultBufferSize(w, h)
            bufW = w; bufH = h
            camClockChecked = false
            notifySessionFrame = true
            val s = Surface(st)
            inputSurface = s
            result = s
        }
        return result
    }

    fun setPreviewSurface(surface: Surface?, w: Int, h: Int) {
        runSync {
            val core = egl ?: return@runSync
            if (previewEgl != EGL14.EGL_NO_SURFACE) {
                core.makeCurrent(pbuffer)
                core.releaseSurface(previewEgl)
                previewEgl = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid) {
                try {
                    previewEgl = core.createWindowSurface(surface)
                    previewW = w; previewH = h
                } catch (e: Exception) {
                    Log.e(TAG, "preview surface failed", e)
                    previewEgl = EGL14.EGL_NO_SURFACE
                }
            }
        }
    }

    /** Preview area (view coordinates of the full-screen SurfaceView) and display rotation. */
    fun setPreviewArea(rect: RectF, displayRotationDeg: Int) {
        area = RectF(rect)
        displayDeg = displayRotationDeg
    }

    fun startEncoding(surface: Surface, w: Int, h: Int, rotate90: Boolean, timelapse: Int, fps: Int, sink: EncoderSink): Boolean {
        var ok = false
        runSync {
            val core = egl ?: return@runSync
            try {
                encEgl = core.createWindowSurface(surface)
                encW = w; encH = h; encRot90 = rotate90
                encSink = sink
                encTimelapse = maxOf(1, timelapse)
                encFps = fps
                encFrameCounter = 0
                encCount = 0
                encT0Mono = -1
                lastPts = -1
                lockedBetaDev = betaDev
                recording = true
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "encoder surface failed", e)
            }
        }
        return ok
    }

    /** Stops feeding the encoder. Returns once the GL thread no longer touches the encoder surface. */
    fun stopEncoding() {
        runSync {
            val core = egl ?: return@runSync
            recording = false
            encSink = null
            if (encEgl != EGL14.EGL_NO_SURFACE) {
                core.makeCurrent(pbuffer)
                core.releaseSurface(encEgl)
                encEgl = EGL14.EGL_NO_SURFACE
            }
        }
    }

    /** Orientation captured at the start of the current/last recording. */
    val recordingBeta: Int get() = lockedBetaDev

    /** Maps a point of the preview view to normalised natural image coordinates (x right, y up). */
    fun viewToNatural(x: Float, y: Float): FloatArray? {
        val m = viewToNatural ?: return null
        val nx = m.mapX(x.toDouble(), y.toDouble())
        val ny = m.mapY(x.toDouble(), y.toDouble())
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null
        return floatArrayOf(nx.toFloat(), ny.toFloat())
    }

    fun release() {
        runSync {
            val core = egl
            if (core != null) {
                try {
                    core.makeCurrent(pbuffer)
                } catch (e: Exception) { /* ignore */ }
                if (previewEgl != EGL14.EGL_NO_SURFACE) core.releaseSurface(previewEgl)
                if (encEgl != EGL14.EGL_NO_SURFACE) core.releaseSurface(encEgl)
                previewEgl = EGL14.EGL_NO_SURFACE
                encEgl = EGL14.EGL_NO_SURFACE
                program?.release()
                if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                surfaceTexture?.release()
                inputSurface?.release()
                if (pbuffer != EGL14.EGL_NO_SURFACE) core.releaseSurface(pbuffer)
                core.release()
            }
            egl = null
            surfaceTexture = null
            inputSurface = null
        }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- frame loop

    override fun onFrameAvailable(st: SurfaceTexture) {
        try {
            drawFrame(st)
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame failed", e)
            error = "Render error: ${e.message}"
        }
    }

    private fun drawFrame(st: SurfaceTexture) {
        val core = egl ?: return
        val prog = program ?: return
        if (st !== surfaceTexture) return
        core.makeCurrent(pbuffer)
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        val tCam = st.timestamp
        val nowRt = SystemClock.elapsedRealtimeNanos()
        val nowMono = System.nanoTime()
        val rtMinusMono = nowRt - nowMono
        if (!camClockChecked && tCam > 0) {
            camClockRealtime = abs(nowRt - tCam) <= abs(nowMono - tCam)
            camClockChecked = true
        }
        val tRt = when {
            tCam <= 0 -> nowRt
            camClockRealtime -> tCam
            else -> tCam + rtMinusMono
        }
        val tMono = tRt - rtMinusMono

        val cfg = config

        // ---- orientation ----
        if (!sensor.upAt(tRt + exposureMidNs, up)) {
            up[0] = 0f; up[1] = 1f; up[2] = 0f
        }
        val ux = up[0].toDouble()
        val uy = up[1].toDouble()
        val xy = hypot(ux, uy)
        val weight = ((xy - 0.15) / 0.25).coerceIn(0.0, 1.0)
        val dt = if (lastFrameRt > 0) (tRt - lastFrameRt) / 1e9 else 0.033
        lastFrameRt = tRt
        val alpha = if (cfg.smooth) 1.0 - exp(-dt.coerceIn(0.0, 0.2) / 0.15) else 1.0
        if (!hasFilt) {
            if (xy > 0.05) { filtX = ux / xy; filtY = uy / xy } else { filtX = 0.0; filtY = 1.0 }
            hasFilt = true
        } else if (xy > 1e-6) {
            val k = weight * alpha
            var nx = filtX + (ux / xy - filtX) * k
            var ny = filtY + (uy / xy - filtY) * k
            val n = sqrt(nx * nx + ny * ny)
            if (n > 1e-6) { nx /= n; ny /= n; filtX = nx; filtY = ny }
        }
        val rollDev = FrameGeometry.rollDeg(filtX, filtY)
        if (!betaInit) { betaDev = HorizonMath.snap90(rollDev); betaInit = true }
        betaDev = HorizonMath.snapWithHysteresis(betaDev, rollDev)

        // ---- geometry (pure, unit-tested in FramePlanner) ----
        val a = area
        val plan = FramePlanner.plan(
            FrameInput(
                st = stMatrix, bufW = bufW, bufH = bufH, isFront = isFront, cfg = cfg,
                rollDev = rollDev, betaDev = betaDev, recording = recording, lockedBetaDev = lockedBetaDev,
                encW = encW, encH = encH, encRot90 = encRot90,
                surfW = if (previewEgl != EGL14.EGL_NO_SURFACE) previewW else 0,
                surfH = if (previewEgl != EGL14.EGL_NO_SURFACE) previewH else 0,
                areaLeft = a.left.toDouble(), areaTop = a.top.toDouble(), areaRight = a.right.toDouble(), areaBottom = a.bottom.toDouble(),
                displayDeg = displayDeg
            )
        ) ?: return

        // ---- preview ----
        val pm = plan.previewMap
        val pf = plan.previewFrame
        if (previewEgl != EGL14.EGL_NO_SURFACE && pm != null && pf != null) {
            pm.toGl(params.map)
            pf.toGl(params.frame)
            setSampling(pm, previewW, previewH, cfg.sharpness * 0.6)
            params.outside = plan.previewOutside
            params.border = plan.previewBorder
            params.lineX = plan.lineX
            params.lineY = plan.lineY
            params.grid = if (cfg.grid) 1f else 0f
            if (recording) {
                params.borderR = 1f; params.borderG = 0.27f; params.borderB = 0.23f
            } else if (cfg.lockMode == LockMode.OFF) {
                params.borderR = 1f; params.borderG = 1f; params.borderB = 1f
            } else {
                params.borderR = 1f; params.borderG = 0.8f; params.borderB = 0.2f
            }
            viewToNatural = plan.viewToNatural
            naturalTrueView = plan.trueView

            try {
                core.makeCurrent(previewEgl)
                GLES20.glViewport(0, 0, previewW, previewH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                prog.draw(texId, stMatrix, params)
                core.swapBuffers(previewEgl)
                if (notifySessionFrame) {
                    notifySessionFrame = false
                    sessionFrameListener?.invoke()
                }
            } catch (e: Exception) {
                Log.w(TAG, "preview draw failed, dropping preview surface", e)
                core.makeCurrent(pbuffer)
                core.releaseSurface(previewEgl)
                previewEgl = EGL14.EGL_NO_SURFACE
            }
        }

        // ---- encoder ----
        val em = plan.encoderMap
        val ef = plan.encoderFrame
        if (recording && encEgl != EGL14.EGL_NO_SURFACE && em != null && ef != null) {
            val encode = (encFrameCounter % encTimelapse) == 0L
            encFrameCounter++
            if (encode) {
                if (encT0Mono < 0) { encT0Mono = tMono; encSink?.onFirstFrame(tMono) }
                val pts = if (encTimelapse > 1) encCount * 1_000_000_000L / encFps else tMono - encT0Mono
                if (pts > lastPts) {
                    lastPts = pts
                    em.toGl(params.map)
                    ef.toGl(params.frame)
                    setSampling(em, encW, encH, cfg.sharpness)
                    params.outside = 1f
                    params.border = 0f
                    params.grid = 0f
                    params.lineX = 0f; params.lineY = 0f
                    try {
                        core.makeCurrent(encEgl)
                        GLES20.glViewport(0, 0, encW, encH)
                        prog.draw(texId, stMatrix, params)
                        core.setPresentationTime(encEgl, pts)
                        if (!core.swapBuffers(encEgl)) Log.w(TAG, "encoder swap failed")
                        encCount++
                        encSink?.onFrameEncoded(pts)
                    } catch (e: Exception) {
                        Log.e(TAG, "encoder draw failed", e)
                        error = "Encoder surface error: ${e.message}"
                        core.makeCurrent(pbuffer)
                        core.releaseSurface(encEgl)
                        encEgl = EGL14.EGL_NO_SURFACE
                    }
                }
            }
        }

        // ---- stats ----
        fpsCount++
        if (fpsStart == 0L) fpsStart = nowMono
        val el = nowMono - fpsStart
        if (el >= 1_000_000_000L) {
            fps = fpsCount * 1e9f / el
            fpsCount = 0
            fpsStart = nowMono
        }
        val tilt = HorizonMath.wrapDeg(rollDev - betaDev)
        state = RenderState(rollDev, betaDev, tilt, plan.cropZoom, plan.wo.toInt(), plan.ho.toInt(), xy < 0.3, fps, bufW, bufH)
        frameListener?.invoke()
    }

    // ---------------------------------------------------------------- helpers

    private val steps = FloatArray(4)

    /** Anti-aliasing taps and sharpening for a draw with the given NDC -> natural mapping. */
    private fun setSampling(ndcToNatTex: Affine, vpW: Int, vpH: Int, sharpen: Double) {
        FramePlanner.pixelSteps(ndcToNatTex, stMatrix, vpW, vpH, steps)
        params.dx[0] = steps[0]; params.dx[1] = steps[1]
        params.dy[0] = steps[2]; params.dy[1] = steps[3]
        params.taps = FramePlanner.tapsFor(steps, bufW, bufH).toFloat()
        params.sharpen = sharpen.toFloat()
    }

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try { block() } finally { latch.countDown() }
        }
        if (posted && !latch.await(4, TimeUnit.SECONDS)) {
            Log.w(TAG, "GL thread task timed out")
        }
    }

    companion object {
        private const val TAG = "HorizonRenderer"
    }
}
