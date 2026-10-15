package com.darshan.horizoncam.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.darshan.horizoncam.core.WbPreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What to open: camera, stream sizes and capture mode. */
data class SessionSpec(
    val cameraId: String,
    val video: Boolean,
    val inputSizes: List<Size>,
    val jpegSize: Size?,
    val fps: Int,
    val stabilization: Boolean,
    val zoomRatio: Float
)

/**
 * Camera2 wrapper. All camera calls run on a dedicated thread; listener callbacks are
 * delivered on that thread too (callers post to the UI thread as needed).
 */
class CameraController(private val manager: CameraManager, private val listener: Listener) {

    interface Listener {
        fun onCameraOpened(info: CameraInfo, inputSize: Size)
        fun onCameraError(message: String)
        fun onFocusState(focused: Boolean?, x: Float, y: Float)
        fun onExposureInfo(exposureNs: Long, iso: Int, rollingShutterSkewNs: Long)
    }

    fun interface SurfaceProvider {
        fun provide(size: Size): Surface?
    }

    fun interface PhotoCallback {
        fun onPhoto(jpeg: ByteArray?, timestampNs: Long, error: String?)
    }

    private val thread = HandlerThread("Camera").also { it.start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var info: CameraInfo? = null
    private var spec: SessionSpec? = null
    private var inputSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var surfaceProvider: SurfaceProvider? = null
    private var pendingSpec: SessionSpec? = null
    /** True between open() and close(): the app wants a running camera. */
    private var active = false
    /** openCamera() was called and onOpened/onError has not arrived yet. */
    private var opening = false
    /** Device we asked to close and whose onClosed has not arrived yet. */
    private var closingDevice: CameraDevice? = null
    private var sizeIndex = 0
    private var generation = 0

    // controls
    private var zoomRatio = 1f
    private var torch = false
    private var evIndex = 0
    private var wb = WbPreset.AUTO
    private var aeAfLocked = false
    private var meteringRegion: MeteringRectangle? = null
    private var focusPoint: FloatArray? = null
    private var awaitingFocus = false
    private var photoCallback: PhotoCallback? = null

    private val resetFocusRunnable = Runnable { clearFocusInternal() }

    val currentInfo: CameraInfo? get() = info

    // ------------------------------------------------------------------ open / close

    fun open(newSpec: SessionSpec, provider: SurfaceProvider) {
        handler.post {
            active = true
            surfaceProvider = provider
            pendingSpec = newSpec
            proceed()
        }
    }

    fun close() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            active = false
            pendingSpec = null
            shutdownDevice()
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    /** Moves the state machine forward; called whenever an async camera step finishes. */
    private fun proceed() {
        if (opening || closingDevice != null) return // continues from the pending callback
        if (device != null) {
            if (pendingSpec != null || !active) shutdownDevice()
            return
        }
        if (active && pendingSpec != null) openPending()
    }

    private fun closeReader() {
        try { jpegReader?.close() } catch (e: Exception) { }
        jpegReader = null
    }

    private fun shutdownDevice() {
        handler.removeCallbacks(resetFocusRunnable)
        awaitingFocus = false
        photoCallback?.onPhoto(null, 0, "Camera closed")
        photoCallback = null
        try { session?.close() } catch (e: Exception) { }
        session = null
        val d = device
        if (d == null) {
            if (closingDevice == null) closeReader()
            return
        }
        device = null
        closingDevice = d
        try {
            d.close()
        } catch (e: Exception) {
            closingDevice = null
            closeReader()
            proceed()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openPending() {
        val s = pendingSpec ?: return
        pendingSpec = null
        spec = s
        sizeIndex = 0
        generation++
        val ci = try {
            CameraInfo(s.cameraId, manager.getCameraCharacteristics(s.cameraId))
        } catch (e: Exception) {
            listener.onCameraError("Camera ${s.cameraId} unavailable: ${e.message}")
            return
        }
        info = ci
        zoomRatio = s.zoomRatio.coerceIn(ci.minZoom, max(ci.minZoom, ci.maxZoom))
        meteringRegion = null
        focusPoint = null
        aeAfLocked = false
        opening = true
        try {
            manager.openCamera(s.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    device = camera
                    if (!active || pendingSpec != null) {
                        // Superseded while opening: close and continue with the newer request.
                        shutdownDevice()
                        return
                    }
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) = lost(camera, "Camera disconnected (another app may be using it)")

                override fun onError(camera: CameraDevice, error: Int) = lost(camera, "Camera error $error")

                override fun onClosed(camera: CameraDevice) {
                    if (closingDevice !== camera) return
                    closingDevice = null
                    closeReader()
                    proceed()
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            listener.onCameraError("Cannot open camera: ${e.message}")
        }
    }

    private fun lost(camera: CameraDevice, message: String) {
        val wasCurrent = device === camera || (opening && device == null)
        opening = false
        if (closingDevice === camera) return // we are closing it anyway; onClosed follows
        try { camera.close() } catch (e: Exception) { }
        if (device === camera) {
            device = null
            session = null
            closeReader()
        }
        if (wasCurrent && active && pendingSpec == null) listener.onCameraError(message)
        proceed()
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val d = device ?: return
        val s = spec ?: return
        val ci = info ?: return
        if (sizeIndex >= s.inputSizes.size) {
            listener.onCameraError("No working camera configuration found")
            return
        }
        val size = s.inputSizes[sizeIndex]
        val surface = surfaceProvider?.provide(size)
        if (surface == null) {
            listener.onCameraError("Preview surface unavailable")
            return
        }
        inputSurface = surface
        val outputs = mutableListOf(surface)
        jpegReader?.close(); jpegReader = null
        if (!s.video && s.jpegSize != null) {
            val r = ImageReader.newInstance(s.jpegSize.width, s.jpegSize.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ reader ->
                val img = try { reader.acquireNextImage() } catch (e: Exception) { null }
                if (img != null) {
                    val cb = photoCallback
                    photoCallback = null
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        cb?.onPhoto(bytes, img.timestamp, null)
                    } catch (e: Exception) {
                        cb?.onPhoto(null, 0, e.message)
                    } finally {
                        img.close()
                    }
                }
            }, handler)
            jpegReader = r
            outputs.add(r.surface)
        }
        val gen = generation
        try {
            d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    if (gen != generation || device !== d) { sess.close(); return }
                    session = sess
                    applyRepeating()
                    listener.onCameraOpened(ci, size)
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    Log.w(TAG, "configure failed for $size")
                    if (gen != generation) return
                    sizeIndex++
                    handler.post { if (device === d) createSession() }
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "createCaptureSession threw", e)
            sizeIndex++
            handler.post { if (device === d) createSession() }
        }
    }

    // ------------------------------------------------------------------ requests

    private fun buildRequest(template: Int): CaptureRequest.Builder? {
        val d = device ?: return null
        val s = spec ?: return null
        val ci = info ?: return null
        val b = d.createCaptureRequest(template)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Auto-focus
        val region = meteringRegion
        if (region != null && ci.maxAfRegions > 0 && ci.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        } else {
            val cont = if (s.video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            when {
                ci.afModes.contains(cont) -> b.set(CaptureRequest.CONTROL_AF_MODE, cont)
                ci.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                else -> {}
            }
        }
        // Exposure
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (region != null && ci.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        ci.fpsRangeFor(s.fps, s.video)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (ci.evRange.lower != ci.evRange.upper) {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex.coerceIn(ci.evRange.lower, ci.evRange.upper))
        }
        b.set(CaptureRequest.CONTROL_AE_LOCK, aeAfLocked)
        // White balance
        val awb = when (wb) {
            WbPreset.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            WbPreset.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            WbPreset.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WbPreset.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            WbPreset.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, if (ci.awbModes.contains(awb)) awb else CameraMetadata.CONTROL_AWB_MODE_AUTO)
        // Torch
        b.set(CaptureRequest.FLASH_MODE, if (torch && ci.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        // Stabilisation
        if (ci.oisSupported) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (s.video && s.stabilization) {
            if (ci.previewStabilizationSupported && Build.VERSION.SDK_INT >= 33) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            } else if (ci.videoStabilizationSupported) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
        } else {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
        // Zoom
        applyZoom(b, ci)
        return b
    }

    private fun applyZoom(b: CaptureRequest.Builder, ci: CameraInfo) {
        if (Build.VERSION.SDK_INT >= 30 && ci.zoomRange != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRegionFor(ci, zoomRatio))
        }
    }

    private fun cropRegionFor(ci: CameraInfo, z: Float): Rect {
        val a = ci.activeArray
        val zz = z.coerceIn(1f, max(1f, ci.maxDigitalZoom))
        val w = (a.width() / zz).roundToInt()
        val h = (a.height() / zz).roundToInt()
        val l = (a.width() - w) / 2
        val t = (a.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
            listener.onExposureInfo(exp, iso, skew)
            if (awaitingFocus) {
                val st = result.get(CaptureResult.CONTROL_AF_STATE)
                val fp = focusPoint
                if (st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || st == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                    awaitingFocus = false
                    if (fp != null) listener.onFocusState(st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, fp[0], fp[1])
                }
            }
        }
    }

    private fun applyRepeating() {
        val sess = session ?: return
        val s = spec ?: return
        try {
            val b = buildRequest(if (s.video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW) ?: return
            inputSurface?.let { b.addTarget(it) }
            sess.setRepeatingRequest(b.build(), repeatingCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest failed", e)
            listener.onCameraError("Camera request failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ controls

    fun setZoom(ratio: Float) = handler.post {
        val ci = info ?: return@post
        zoomRatio = ratio.coerceIn(ci.minZoom, max(ci.minZoom, ci.maxZoom))
        applyRepeating()
    }

    fun setTorch(on: Boolean) = handler.post { torch = on; applyRepeating() }

    fun setEv(index: Int) = handler.post { evIndex = index; applyRepeating() }

    fun setWhiteBalance(p: WbPreset) = handler.post { wb = p; applyRepeating() }

    /**
     * Focus/meter at a point given in sensor-normalised coordinates (0..1, sensor orientation).
     * [uiX]/[uiY] are echoed back to the listener for drawing the focus ring.
     */
    fun focusAt(sensorX: Float, sensorY: Float, lock: Boolean, uiX: Float, uiY: Float) = handler.post {
        val ci = info ?: return@post
        val s = spec ?: return@post
        val sess = session ?: return@post
        if (ci.maxAfRegions <= 0 && ci.maxAeRegions <= 0) {
            listener.onFocusState(null, uiX, uiY)
            return@post
        }
        val region = meteringRect(ci, s, sensorX, sensorY)
        meteringRegion = region
        focusPoint = floatArrayOf(uiX, uiY)
        aeAfLocked = lock
        handler.removeCallbacks(resetFocusRunnable)
        try {
            // Cancel any ongoing AF, then trigger a new scan at the region.
            sess.stopRepeating()
            val cancel = buildRequest(if (s.video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW) ?: return@post
            inputSurface?.let { cancel.addTarget(it) }
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            sess.capture(cancel.build(), null, handler)
            applyRepeating()
            if (ci.maxAfRegions > 0 && ci.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                val trig = buildRequest(if (s.video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW) ?: return@post
                inputSurface?.let { trig.addTarget(it) }
                trig.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                sess.capture(trig.build(), repeatingCallback, handler)
                awaitingFocus = true
            } else {
                listener.onFocusState(null, uiX, uiY)
            }
        } catch (e: Exception) {
            Log.w(TAG, "focus failed", e)
        }
        if (!lock) handler.postDelayed(resetFocusRunnable, 5000)
    }

    fun clearFocus() = handler.post { clearFocusInternal() }

    private fun clearFocusInternal() {
        handler.removeCallbacks(resetFocusRunnable)
        if (meteringRegion == null && !aeAfLocked) return
        meteringRegion = null
        focusPoint = null
        aeAfLocked = false
        awaitingFocus = false
        val sess = session ?: return
        val s = spec ?: return
        try {
            val cancel = buildRequest(if (s.video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW) ?: return
            inputSurface?.let { cancel.addTarget(it) }
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            sess.capture(cancel.build(), null, handler)
        } catch (e: Exception) { }
        applyRepeating()
    }

    private fun meteringRect(ci: CameraInfo, s: SessionSpec, sx: Float, sy: Float): MeteringRectangle {
        val a = ci.activeArray
        // Visible field of view (absolute coords when zoom uses the crop region).
        val base: Rect = if (Build.VERSION.SDK_INT >= 30 && ci.zoomRange != null) {
            Rect(0, 0, a.width(), a.height())
        } else cropRegionFor(ci, zoomRatio)
        val size = s.inputSizes.getOrNull(sizeIndex) ?: Size(a.width(), a.height())
        val streamAspect = size.width.toDouble() / size.height
        val baseAspect = base.width().toDouble() / base.height()
        var vw = base.width().toDouble()
        var vh = base.height().toDouble()
        if (streamAspect > baseAspect) vh = vw / streamAspect else vw = vh * streamAspect
        val vl = base.left + (base.width() - vw) / 2
        val vt = base.top + (base.height() - vh) / 2
        val cx = vl + sx * vw
        val cy = vt + sy * vh
        val half = max(20.0, min(vw, vh) * 0.08)
        val l = (cx - half).roundToInt().coerceIn(0, a.width() - 2)
        val t = (cy - half).roundToInt().coerceIn(0, a.height() - 2)
        val r = (cx + half).roundToInt().coerceIn(l + 1, a.width() - 1)
        val btm = (cy + half).roundToInt().coerceIn(t + 1, a.height() - 1)
        return MeteringRectangle(l, t, r - l, btm - t, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    // ------------------------------------------------------------------ photo

    fun capturePhoto(cb: PhotoCallback) = handler.post {
        val sess = session
        val reader = jpegReader
        val ci = info
        if (sess == null || reader == null || ci == null) {
            cb.onPhoto(null, 0, "Camera not ready")
            return@post
        }
        try {
            val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: run { cb.onPhoto(null, 0, "Camera not ready"); return@post }
            b.addTarget(reader.surface)
            inputSurface?.let { b.addTarget(it) }
            b.set(CaptureRequest.JPEG_ORIENTATION, ci.sensorOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            photoCallback = cb
            sess.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    val c = photoCallback
                    photoCallback = null
                    c?.onPhoto(null, 0, "Capture failed (${failure.reason})")
                }
            }, handler)
        } catch (e: Exception) {
            photoCallback = null
            cb.onPhoto(null, 0, "Capture error: ${e.message}")
        }
    }

    fun currentZoom(): Float = zoomRatio

    companion object {
        private const val TAG = "CameraController"

        /** Natural-image coordinates (x right, y down, true view) to sensor-normalised coordinates. */
        fun naturalToSensor(nx: Float, ny: Float, sensorOrientation: Int): FloatArray = when ((sensorOrientation % 360 + 360) % 360) {
            90 -> floatArrayOf(ny, 1f - nx)
            180 -> floatArrayOf(1f - nx, 1f - ny)
            270 -> floatArrayOf(1f - ny, nx)
            else -> floatArrayOf(nx, ny)
        }

        @Suppress("unused")
        fun lensFacing(chars: CameraCharacteristics): Int = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK
    }
}
