package com.darshan.horizoncam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.darshan.horizoncam.camera.CameraController
import com.darshan.horizoncam.camera.CameraInfo
import com.darshan.horizoncam.camera.LensCatalog
import com.darshan.horizoncam.camera.LensOption
import com.darshan.horizoncam.camera.SessionSpec
import com.darshan.horizoncam.core.AspectRatio
import com.darshan.horizoncam.core.CaptureMode
import com.darshan.horizoncam.core.FrameOrientation
import com.darshan.horizoncam.core.HorizonMath
import com.darshan.horizoncam.core.LockMode
import com.darshan.horizoncam.core.PreviewStyle
import com.darshan.horizoncam.core.RenderConfig
import com.darshan.horizoncam.core.Settings
import com.darshan.horizoncam.core.VideoQuality
import com.darshan.horizoncam.core.VolumeAction
import com.darshan.horizoncam.core.WbPreset
import com.darshan.horizoncam.gl.HorizonRenderer
import com.darshan.horizoncam.media.EncoderSupport
import com.darshan.horizoncam.media.PhotoProcessor
import com.darshan.horizoncam.media.VideoRecorder
import com.darshan.horizoncam.sensor.HorizonSensor
import com.darshan.horizoncam.ui.ActionRow
import com.darshan.horizoncam.ui.OverlayView
import com.darshan.horizoncam.ui.SettingRow
import com.darshan.horizoncam.ui.SettingsPanel
import com.darshan.horizoncam.ui.ShutterButton
import com.darshan.horizoncam.util.CrashLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener, VideoRecorder.Listener {

    private lateinit var settings: Settings
    private lateinit var sensor: HorizonSensor
    private lateinit var renderer: HorizonRenderer
    private lateinit var camera: CameraController
    private lateinit var recorder: VideoRecorder
    private lateinit var cameraManager: CameraManager

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var sound: MediaActionSound? = null

    // views
    private lateinit var root: View
    private lateinit var preview: SurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var topBar: LinearLayout
    private lateinit var statusRow: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var btnSettings: ImageButton
    private lateinit var chipLock: TextView
    private lateinit var chipFrame: TextView
    private lateinit var btnTorch: ImageButton
    private lateinit var btnTimer: ImageButton
    private lateinit var btnEv: ImageButton
    private lateinit var recTime: TextView
    private lateinit var infoText: TextView
    private lateinit var evPanel: LinearLayout
    private lateinit var evLabel: TextView
    private lateinit var evSeek: SeekBar
    private lateinit var wbRow: LinearLayout
    private lateinit var zoomRow: LinearLayout
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var modeTimelapse: TextView
    private lateinit var thumb: ImageView
    private lateinit var shutter: ShutterButton
    private lateinit var btnSwitch: ImageButton
    private lateinit var toastView: TextView
    private lateinit var permissionPanel: View
    private lateinit var settingsHost: android.widget.FrameLayout
    private var settingsPanel: SettingsPanel? = null

    // state
    private var cameras: List<CameraInfo> = emptyList()
    private var backLenses: List<LensOption> = emptyList()
    private var frontLens: LensOption? = null
    private var currentLens: LensOption? = null
    private var zoomRatio = 1f
    private var torchOn = false
    private var evIndex = 0
    private var wb = WbPreset.AUTO
    private var cameraReady = false
    private var resumed = false
    private var lastSpec: SessionSpec? = null
    private var sessionInput: Size? = null
    private var sessionInfo: CameraInfo? = null
    private var sessionFps = 30
    private var uiRotation = 0f
    private var countdownLeft = 0
    private var photoBusy = false
    private var exposureNs = 0L
    private var iso = 0
    private var lastSavedUri: Uri? = null
    private var lastSavedVideo = false
    private var insetTop = 0
    private var insetBottom = 0
    private var permissionAsked = false
    /** False when onCreate bailed out early (crash report shown instead). */
    private var initialized = false

    private val dp get() = resources.displayMetrics.density

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show the report of a previous crash first (in its own, minimal activity).
        val crash = CrashLogger.unseenCrash(this)
        val unfinished = CrashLogger.consumeUnfinishedStartup(this)
        if (!intent.getBooleanExtra(EXTRA_SKIP_REPORT, false)) {
            val report = crash ?: unfinished?.let { CrashLogger.unfinishedStartupReport(this, it) }
            if (report != null) {
                startActivity(Intent(this, CrashReportActivity::class.java).putExtra(CrashReportActivity.EXTRA_REPORT, report))
                finish()
                return
            }
        }
        CrashLogger.step(this, "onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupEdgeToEdge()
        CrashLogger.step(this, "inflate layout")
        setContentView(R.layout.activity_main)
        bindViews()

        settings = Settings(this)
        CrashLogger.step(this, "motion sensor")
        sensor = HorizonSensor(this)
        CrashLogger.step(this, "OpenGL renderer")
        renderer = HorizonRenderer(sensor)
        renderer.frameListener = {
            renderer.frameListener = null
            CrashLogger.step(this, "first camera frame")
            main.postDelayed({ CrashLogger.startupDone(this) }, 1500)
        }
        CrashLogger.step(this, "camera service")
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        camera = CameraController(cameraManager, this)
        recorder = VideoRecorder(this, renderer, this)
        sound = try {
            MediaActionSound().apply {
                load(MediaActionSound.SHUTTER_CLICK)
                load(MediaActionSound.START_VIDEO_RECORDING)
                load(MediaActionSound.STOP_VIDEO_RECORDING)
            }
        } catch (e: Exception) { null }

        CrashLogger.step(this, "camera list")
        try {
            cameras = CameraInfo.loadAll(cameraManager)
            backLenses = LensCatalog.backLenses(cameras)
            frontLens = LensCatalog.frontLens(cameras)
        } catch (e: Exception) {
            CrashLogger.nonFatal(this, "camera list", e)
        }
        currentLens = if (settings.useFront && frontLens != null) frontLens else defaultBackLens()
        zoomRatio = currentLens?.zoomRatio ?: 1f

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                renderer.setPreviewSurface(holder.surface, width, height)
                updatePreviewArea()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.setPreviewSurface(null, 0, 0)
            }
        })

        overlay.stateSource = OverlayView.StateSource { renderer.state }
        overlay.onLevelReached = {
            if (settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        CrashLogger.step(this, "controls")
        setupListeners()
        setupGestures()
        setupInsets()
        buildWbRow()
        rebuildLensButtons()
        applyRenderConfig()
        refreshUi()
        main.post(uiTicker)

        lastSavedUri = settings.lastSavedUri?.let { Uri.parse(it) }
        lastSavedVideo = settings.lastSavedIsVideo
        loadThumbnailFor(lastSavedUri, lastSavedVideo)

        if (renderer.error != null) toast(renderer.error!!)
        if (!sensor.available) toast("No motion sensor found: horizon lock is unavailable")
        initialized = true
        CrashLogger.step(this, "onCreate done")
    }

    override fun onResume() {
        super.onResume()
        if (!initialized) return
        resumed = true
        CrashLogger.step(this, "onResume")
        hideSystemBars()
        if (hasCameraPermission()) {
            permissionPanel.visibility = View.GONE
            CrashLogger.step(this, "start motion sensor")
            sensor.start()
            CrashLogger.step(this, "open camera")
            openCamera(force = true)
            maybeShowWelcome()
        } else {
            permissionPanel.visibility = View.VISIBLE
            if (!permissionAsked) requestPermissions()
        }
    }

    override fun onPause() {
        if (!initialized) {
            super.onPause()
            return
        }
        // Leaving normally is not a start-up crash.
        CrashLogger.startupDone(this)
        resumed = false
        cancelCountdown()
        if (recorder.isRecording) stopRecording()
        cameraReady = false
        camera.close()
        lastSpec = null
        sensor.stop()
        if (torchOn) {
            torchOn = false
            camera.setTorch(false)
            refreshUi()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (!initialized) {
            super.onDestroy()
            return
        }
        main.removeCallbacksAndMessages(null)
        camera.release()
        renderer.release()
        worker.shutdown()
        sound?.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && initialized) hideSystemBars()
    }

    /** Closes the top-most panel. Returns false when nothing was open. */
    private fun handleBack(): Boolean = when {
        settingsHost.visibility == View.VISIBLE -> { closeSettings(); true }
        evPanel.visibility == View.VISIBLE -> { setEvPanelVisible(false); true }
        else -> false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!initialized || !handleBack()) super.onBackPressed()
    }

    private var backCallback: Any? = null

    /** Android 13+: predictive back only reaches the app through a registered callback. */
    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val needed = settingsHost.visibility == View.VISIBLE || evPanel.visibility == View.VISIBLE
        val dispatcher = onBackInvokedDispatcher
        if (needed && backCallback == null) {
            val cb = android.window.OnBackInvokedCallback { handleBack() }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!needed && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback(backCallback as android.window.OnBackInvokedCallback)
            backCallback = null
        }
    }

    private fun setEvPanelVisible(visible: Boolean) {
        evPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refreshEvPanel()
        updateBackCallback()
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        topBar = findViewById(R.id.topBar)
        statusRow = findViewById(R.id.statusRow)
        bottomPanel = findViewById(R.id.bottomPanel)
        btnSettings = findViewById(R.id.btnSettings)
        chipLock = findViewById(R.id.chipLock)
        chipFrame = findViewById(R.id.chipFrame)
        btnTorch = findViewById(R.id.btnTorch)
        btnTimer = findViewById(R.id.btnTimer)
        btnEv = findViewById(R.id.btnEv)
        recTime = findViewById(R.id.recTime)
        infoText = findViewById(R.id.infoText)
        evPanel = findViewById(R.id.evPanel)
        evLabel = findViewById(R.id.evLabel)
        evSeek = findViewById(R.id.evSeek)
        wbRow = findViewById(R.id.wbRow)
        zoomRow = findViewById(R.id.zoomRow)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        modeTimelapse = findViewById(R.id.modeTimelapse)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)
        btnSwitch = findViewById(R.id.btnSwitch)
        toastView = findViewById(R.id.toast)
        permissionPanel = findViewById(R.id.permissionPanel)
        settingsHost = findViewById(R.id.settingsHost)
    }

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    @Suppress("DEPRECATION")
    private fun setupInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val cut = insets.getInsets(WindowInsets.Type.displayCutout())
                insetTop = max(cut.top, 0)
                insetBottom = bars.bottom
            } else {
                insetTop = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
                insetBottom = insets.systemWindowInsetBottom
            }
            topBar.setPadding(topBar.paddingLeft, insetTop + (8 * dp).toInt(), topBar.paddingRight, topBar.paddingBottom)
            (statusRow.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = insetTop + (62 * dp).toInt()
            statusRow.requestLayout()
            bottomPanel.setPadding(0, 0, 0, insetBottom + (12 * dp).toInt())
            settingsHost.setPadding(0, insetTop, 0, insetBottom)
            root.post { updatePreviewArea() }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePreviewArea() }
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !permissionAsked) {
                requestPermissions()
            } else {
                startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        btnSettings.setOnClickListener { openSettings() }
        chipLock.setOnClickListener {
            if (recorder.isRecording) return@setOnClickListener
            settings.lockMode = when (settings.lockMode) {
                LockMode.LOCK_360 -> LockMode.LEVEL
                LockMode.LEVEL -> LockMode.OFF
                LockMode.OFF -> LockMode.LOCK_360
            }
            toast(lockDescription())
            onLockModeChanged()
        }
        chipFrame.setOnClickListener {
            if (recorder.isRecording) return@setOnClickListener
            settings.frameOrientation = when (settings.frameOrientation) {
                FrameOrientation.AUTO -> FrameOrientation.LANDSCAPE
                FrameOrientation.LANDSCAPE -> FrameOrientation.PORTRAIT
                FrameOrientation.PORTRAIT -> FrameOrientation.AUTO
            }
            toast(when (settings.frameOrientation) {
                FrameOrientation.AUTO -> "Frame follows how you hold the phone"
                FrameOrientation.LANDSCAPE -> "Always landscape, however you hold the phone"
                FrameOrientation.PORTRAIT -> "Always portrait, however you hold the phone"
            })
            reconfigure()
        }
        btnTorch.setOnClickListener {
            val info = camera.currentInfo
            if (info == null || !info.hasFlash) {
                toast("No flash on this camera")
                return@setOnClickListener
            }
            torchOn = !torchOn
            camera.setTorch(torchOn)
            refreshUi()
        }
        btnTimer.setOnClickListener {
            settings.timerSec = when (settings.timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            toast(if (settings.timerSec == 0) "Timer off" else "Timer ${settings.timerSec}s")
            refreshUi()
        }
        btnEv.setOnClickListener { setEvPanelVisible(evPanel.visibility != View.VISIBLE) }
        evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val info = camera.currentInfo ?: return
                evIndex = info.evRange.lower + progress
                camera.setEv(evIndex)
                refreshEvPanel()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        evLabel.setOnClickListener {
            evIndex = 0
            camera.setEv(0)
            refreshEvPanel()
        }
        modePhoto.setOnClickListener { setMode(CaptureMode.PHOTO) }
        modeVideo.setOnClickListener { setMode(CaptureMode.VIDEO) }
        modeTimelapse.setOnClickListener { setMode(CaptureMode.TIMELAPSE) }
        shutter.setOnClickListener { onShutter() }
        btnSwitch.setOnClickListener { switchCamera() }
        thumb.setOnClickListener { openLastCapture() }
    }

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val info = camera.currentInfo ?: return true
                zoomRatio = (zoomRatio * detector.scaleFactor).coerceIn(info.minZoom, max(info.minZoom, info.maxZoom))
                camera.setZoom(zoomRatio)
                refreshLensButtons()
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (evPanel.visibility == View.VISIBLE) {
                    setEvPanelVisible(false)
                    return true
                }
                focusAt(e.x, e.y, false)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                focusAt(e.x, e.y, true)
                if (settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || recorder.isRecording) return false
                val dx = e2.x - e1.x
                if (abs(dx) > 120 * dp && abs(vx) > abs(vy) * 1.5f) {
                    val modes = CaptureMode.values()
                    val i = settings.captureMode.ordinal + if (dx < 0) 1 else -1
                    if (i in modes.indices) setMode(modes[i])
                    return true
                }
                return false
            }
        })
        overlay.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) gestures.onTouchEvent(ev)
            true
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasAudioPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionAsked = true
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS || !initialized) return
        if (hasCameraPermission()) {
            permissionPanel.visibility = View.GONE
            if (resumed) {
                sensor.start()
                openCamera(force = true)
                maybeShowWelcome()
            }
        } else {
            permissionPanel.visibility = View.VISIBLE
            findViewById<Button>(R.id.btnGrant).text = getString(
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) R.string.grant else R.string.open_settings
            )
        }
    }

    // ------------------------------------------------------------------ camera

    private fun defaultBackLens(): LensOption? {
        if (backLenses.isEmpty()) return frontLens
        if (settings.lockMode == LockMode.LOCK_360 && settings.preferUltraWide) return backLenses.first()
        return backLenses.firstOrNull { it.relative == 1f } ?: backLenses.first()
    }

    private fun infoFor(lens: LensOption?): CameraInfo? = cameras.firstOrNull { it.id == lens?.cameraId }

    private fun videoMode() = settings.captureMode != CaptureMode.PHOTO

    private fun buildSpec(): SessionSpec? {
        val lens = currentLens ?: return null
        val info = infoFor(lens) ?: return null
        return if (!videoMode()) {
            val (prev, jpeg) = info.choosePhotoSizes()
            val fallbacks = listOf(prev, Size(1440, 1080), Size(1280, 960), Size(1280, 720))
                .filter { s -> info.textureSizes().contains(s) }.distinct()
            SessionSpec(info.id, false, if (fallbacks.isEmpty()) listOf(prev) else fallbacks, jpeg, 30, false, zoomRatio)
        } else {
            val fps = targetFps(info)
            val sizes = info.chooseVideoInputSizes(
                fps, settings.videoQuality.shortSide, settings.videoAspect.ratio,
                settings.lockMode, settings.levelRange.toDouble(), settings.frameOrientation
            )
            SessionSpec(info.id, true, sizes, null, fps, settings.cameraStabilization, zoomRatio)
        }
    }

    private fun targetFps(info: CameraInfo): Int {
        if (settings.captureMode == CaptureMode.TIMELAPSE) return 30
        return if (settings.fps == 60 && info.supportsFps(60)) 60 else 30
    }

    private fun openCamera(force: Boolean = false) {
        if (!resumed || !hasCameraPermission()) return
        val spec = buildSpec()
        if (spec == null) {
            toast("No usable camera found")
            return
        }
        val same = lastSpec?.let {
            it.cameraId == spec.cameraId && it.video == spec.video && it.inputSizes.firstOrNull() == spec.inputSizes.firstOrNull() &&
                it.fps == spec.fps && it.stabilization == spec.stabilization && it.jpegSize == spec.jpegSize
        } ?: false
        applyRenderConfig()
        if (same && !force) return
        val info = infoFor(currentLens)
        renderer.isFront = info?.isFront ?: false
        cameraReady = false
        lastSpec = spec
        if (settings.fps == 60 && spec.fps != 60 && videoMode() && settings.captureMode != CaptureMode.TIMELAPSE) {
            toast("60 fps is not supported by this camera, using 30 fps")
        }
        camera.open(spec) { size -> renderer.prepareInputSurface(size.width, size.height) }
    }

    /** Re-evaluates the session after a setting change; reopens only when needed. */
    private fun reconfigure() {
        applyRenderConfig()
        refreshUi()
        openCamera(force = false)
    }

    private fun onLockModeChanged() {
        if (settings.lockMode == LockMode.LOCK_360 && settings.preferUltraWide && !(infoFor(currentLens)?.isFront ?: false)) {
            val widest = backLenses.firstOrNull()
            if (widest != null && widest.relative < 1f && currentLens != widest) {
                selectLens(widest, reopen = false)
            }
        }
        reconfigure()
    }

    override fun onCameraOpened(info: CameraInfo, inputSize: Size) {
        CrashLogger.step(this, "camera ${info.id} streaming ${inputSize.width}x${inputSize.height}")
        main.post {
            cameraReady = true
            sessionInput = inputSize
            sessionInfo = info
            sessionFps = lastSpec?.fps ?: 30
            if (!info.hasFlash) torchOn = false
            camera.setTorch(torchOn)
            camera.setEv(evIndex)
            camera.setWhiteBalance(wb)
            updatePreviewArea()
            refreshUi()
            refreshEvPanel()
        }
    }

    override fun onCameraError(message: String) {
        CrashLogger.nonFatal(this, "camera", RuntimeException(message))
        main.post {
            cameraReady = false
            toast(message)
        }
    }

    override fun onFocusState(focused: Boolean?, x: Float, y: Float) {
        main.post { overlay.focusResult(focused) }
    }

    override fun onExposureInfo(exposureNs: Long, iso: Int, rollingShutterSkewNs: Long) {
        this.exposureNs = exposureNs
        this.iso = iso
        val mid = (exposureNs + rollingShutterSkewNs) / 2
        if (mid in 1..200_000_000L) renderer.exposureMidNs = mid
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        if (!cameraReady) return
        val nat = renderer.viewToNatural(x, y) ?: run {
            camera.clearFocus()
            overlay.hideFocus()
            return
        }
        var nx = nat[0]
        if (!renderer.naturalTrueView) nx = 1f - nx
        val ny = 1f - nat[1]
        val info = camera.currentInfo ?: return
        val s = CameraController.naturalToSensor(nx, ny, info.sensorOrientation)
        overlay.showFocus(x, y, lock)
        camera.focusAt(s[0], s[1], lock, x, y)
    }

    private fun switchCamera() {
        if (recorder.isRecording) return
        val front = infoFor(currentLens)?.isFront ?: false
        if (front) {
            currentLens = defaultBackLens()
            settings.useFront = false
        } else {
            val f = frontLens ?: run { toast("No front camera"); return }
            currentLens = f
            settings.useFront = true
        }
        zoomRatio = currentLens?.zoomRatio ?: 1f
        torchOn = false
        btnSwitch.animate().rotationBy(180f).setDuration(250).start()
        rebuildLensButtons()
        openCamera(force = true)
    }

    private fun selectLens(lens: LensOption, reopen: Boolean = true) {
        val sameCamera = currentLens?.cameraId == lens.cameraId
        currentLens = lens
        zoomRatio = lens.zoomRatio
        if (sameCamera) {
            camera.setZoom(zoomRatio)
        } else if (reopen) {
            openCamera(force = true)
        } else {
            lastSpec = null
        }
        refreshLensButtons()
    }

    // ------------------------------------------------------------------ render config & preview area

    private fun applyRenderConfig() {
        val photo = settings.captureMode == CaptureMode.PHOTO
        val smooth = when (settings.response) {
            1 -> false
            2 -> true
            else -> settings.cameraStabilization && !photo
        }
        renderer.config = RenderConfig(
            lockMode = settings.lockMode,
            levelRange = settings.levelRange.toDouble(),
            orientation = settings.frameOrientation,
            aspect = if (photo) settings.photoAspect.ratio else settings.videoAspect.ratio,
            outShort = if (photo) 1080 else settings.videoQuality.shortSide,
            smooth = smooth,
            previewStyle = settings.previewStyle,
            grid = settings.grid,
            mirrorFront = settings.mirrorFront
        )
        overlay.showLevel = settings.levelIndicator
        overlay.lockActive = settings.lockMode != LockMode.OFF
        updatePreviewArea()
    }

    private fun displayRotationDeg(): Int {
        // View.getDisplay() is safe once attached; Context.getDisplay() can throw on some contexts.
        @Suppress("DEPRECATION")
        val d = root.display
            ?: try { if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay } catch (e: Exception) { null }
        return (d?.rotation ?: 0) * 90
    }

    private fun updatePreviewArea() {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0 || h <= 0) return
        val topLimit = (topBar.bottom.takeIf { it > 0 } ?: (insetTop + (60 * dp).toInt())).toFloat()
        val controlsTop = (bottomPanel.top + bottomPanel.height - insetBottom - (12 * dp) - (96 * dp))
        val bottomLimit = if (controlsTop > topLimit) controlsTop else h
        val area: RectF
        if (settings.previewStyle == PreviewStyle.GIMBAL) {
            val info = sessionInfo
            val size = sessionInput
            val nd = if (info != null && size != null) info.naturalDims(size) else intArrayOf(3, 4)
            val disp = displayRotationDeg()
            val aspect = if (HorizonMath.isQuarterTurn(disp)) nd[0].toFloat() / nd[1] else nd[1].toFloat() / nd[0]
            val imgH = w * aspect
            val avail = bottomLimit - topLimit
            val top = when {
                imgH <= avail -> topLimit + (avail - imgH) / 2f
                imgH <= bottomLimit - insetTop -> bottomLimit - imgH
                else -> max(0f, (h - imgH) / 2f)
            }
            area = RectF(0f, top, w, top + imgH)
        } else {
            area = RectF(0f, topLimit, w, bottomLimit)
        }
        renderer.setPreviewArea(area, displayRotationDeg())
        overlay.previewArea = area
        overlay.displayDeg = displayRotationDeg()
    }

    // ------------------------------------------------------------------ capture

    private fun onShutter() {
        if (countdownLeft > 0) {
            cancelCountdown()
            return
        }
        if (!cameraReady && !recorder.isRecording) {
            toast("Camera is starting…")
            return
        }
        when (settings.captureMode) {
            CaptureMode.PHOTO -> withTimer { takePhoto() }
            CaptureMode.VIDEO, CaptureMode.TIMELAPSE ->
                if (recorder.isRecording) stopRecording() else withTimer { startRecording() }
        }
    }

    private fun withTimer(action: () -> Unit) {
        val secs = settings.timerSec
        if (secs <= 0) {
            action()
            return
        }
        countdownLeft = secs
        overlay.countdown = secs
        val total = secs
        val tick = object : Runnable {
            override fun run() {
                countdownLeft--
                overlay.countdown = countdownLeft
                shutter.progress = 1f - countdownLeft.toFloat() / total
                if (countdownLeft <= 0) {
                    shutter.progress = -1f
                    action()
                } else {
                    if (settings.shutterSound) sound?.play(MediaActionSound.FOCUS_COMPLETE)
                    main.postDelayed(this, 1000)
                }
            }
        }
        shutter.progress = 0f
        if (settings.shutterSound) sound?.play(MediaActionSound.FOCUS_COMPLETE)
        main.postDelayed(tick, 1000)
        countdownRunnable = tick
    }

    private var countdownRunnable: Runnable? = null

    private fun cancelCountdown() {
        countdownRunnable?.let { main.removeCallbacks(it) }
        countdownRunnable = null
        countdownLeft = 0
        overlay.countdown = 0
        shutter.progress = -1f
    }

    private fun takePhoto() {
        if (photoBusy) return
        val info = camera.currentInfo ?: return
        val st = renderer.state
        val beta = st?.betaDeg ?: 0
        photoBusy = true
        overlay.flash()
        if (settings.shutterSound) sound?.play(MediaActionSound.SHUTTER_CLICK)
        val params = { up: FloatArray ->
            PhotoProcessor.Params(
                lockMode = settings.lockMode,
                levelRange = settings.levelRange.toDouble(),
                orientation = settings.frameOrientation,
                aspect = settings.photoAspect.ratio,
                upX = up[0].toDouble(),
                upY = up[1].toDouble(),
                betaDev = beta,
                isFront = info.isFront,
                mirrorFront = settings.mirrorFront
            )
        }
        val exposureMid = renderer.exposureMidNs
        camera.capturePhoto { jpeg, ts, err ->
            if (jpeg == null) {
                main.post { photoBusy = false; toast(err ?: "Photo failed") }
                return@capturePhoto
            }
            val up = FloatArray(3)
            val nowRt = SystemClock.elapsedRealtimeNanos()
            val nowMono = System.nanoTime()
            val tRt = if (ts <= 0) nowRt else if (abs(nowRt - ts) <= abs(nowMono - ts)) ts else ts + (nowRt - nowMono)
            if (!sensor.upAt(tRt + exposureMid, up)) { up[0] = 0f; up[1] = 1f }
            val p = params(up)
            worker.execute {
                val r = PhotoProcessor.process(this, jpeg, p)
                main.post {
                    photoBusy = false
                    if (r.error != null) toast(r.error)
                    if (r.uri != null) onSaved(r.uri, false, r.thumbnail)
                }
            }
        }
    }

    private fun startRecording() {
        if (recorder.isRecording) return
        val timelapse = settings.captureMode == CaptureMode.TIMELAPSE
        val beta = renderer.state?.betaDeg ?: 0
        val landscape = when (settings.frameOrientation) {
            FrameOrientation.AUTO -> HorizonMath.isQuarterTurn(beta)
            FrameOrientation.LANDSCAPE -> true
            FrameOrientation.PORTRAIT -> false
        }
        val fpsOut = if (timelapse) 30 else sessionFps
        val wantHevc = settings.hevc && EncoderSupport.hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val mimes = if (wantHevc) listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC) else listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        val qualities = VideoQuality.values().filter { it.shortSide <= settings.videoQuality.shortSide }.sortedByDescending { it.shortSide }
        var choice: EncoderSupport.Choice? = null
        outer@ for (mime in mimes) {
            for (q in qualities) {
                val fs = HorizonMath.frameSize(q.shortSide, settings.videoAspect.ratio, landscape)
                choice = EncoderSupport.pick(mime, fs[0], fs[1], fpsOut)
                if (choice != null) {
                    if (q != settings.videoQuality) toast("${settings.videoQuality.label} not supported by the encoder, recording ${q.label}")
                    break@outer
                }
            }
        }
        val c = choice ?: run {
            toast("No video encoder supports this format")
            return
        }
        var bitrate = VideoRecorder.bitrateFor(c.mime, c.width, c.height, fpsOut, settings.highBitrate)
        EncoderSupport.bitrateRange(c.codecName, c.mime)?.let { bitrate = bitrate.coerceIn(it.lower, it.upper) }
        val cfg = VideoRecorder.Config(
            width = c.width, height = c.height, rotate90 = c.rotate90, fps = fpsOut, bitrate = bitrate,
            mime = c.mime, codecName = c.codecName,
            audio = settings.recordAudio && hasAudioPermission(),
            timelapse = if (timelapse) settings.timelapseSpeed else 1
        )
        val err = recorder.start(cfg)
        if (err != null) {
            toast(err)
            return
        }
        if (settings.shutterSound) sound?.play(MediaActionSound.START_VIDEO_RECORDING)
        shutter.recording = true
        setEvPanelVisible(false)
        refreshUi()
    }

    private fun stopRecording() {
        if (!recorder.isRecording) return
        recorder.stop()
        if (settings.shutterSound) sound?.play(MediaActionSound.STOP_VIDEO_RECORDING)
        shutter.recording = false
        refreshUi()
        toast("Saving video…")
    }

    override fun onRecordingSaved(uri: Uri?, durationMs: Long) {
        main.post {
            if (uri != null) {
                onSaved(uri, true, null)
                toast("Video saved to DCIM/HorizonCam")
            } else {
                toast("Video saved")
            }
        }
    }

    override fun onRecordingFailed(message: String) {
        main.post {
            shutter.recording = false
            toast(message)
            refreshUi()
        }
    }

    override fun onAudioLevel(level: Float) {}

    private fun onSaved(uri: Uri, video: Boolean, bmp: Bitmap?) {
        lastSavedUri = uri
        lastSavedVideo = video
        settings.lastSavedUri = uri.toString()
        settings.lastSavedIsVideo = video
        if (bmp != null) {
            thumb.setImageBitmap(bmp)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            bounceThumb()
        } else {
            loadThumbnailFor(uri, video)
        }
    }

    private fun bounceThumb() {
        thumb.scaleX = 0.6f
        thumb.scaleY = 0.6f
        thumb.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun loadThumbnailFor(uri: Uri?, video: Boolean) {
        if (uri == null) return
        worker.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.loadThumbnail(uri, android.util.Size(240, 240), null)
                } else if (video) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(this, uri)
                        r.getFrameAtTime(0)
                    } finally {
                        r.release()
                    }
                } else {
                    contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                    }
                }
            } catch (e: Exception) { null }
            if (bmp != null) main.post {
                thumb.setImageBitmap(bmp)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                bounceThumb()
            }
        }
    }

    private fun openLastCapture() {
        val uri = lastSavedUri
        if (uri == null) {
            toast("Nothing captured yet")
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, if (lastSavedVideo) "video/mp4" else "image/jpeg")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("No app found to open the file")
        }
    }

    // ------------------------------------------------------------------ modes

    private fun setMode(mode: CaptureMode) {
        if (recorder.isRecording || settings.captureMode == mode) return
        cancelCountdown()
        settings.captureMode = mode
        shutter.mode = mode
        if (mode == CaptureMode.TIMELAPSE) toast("Time-lapse ${settings.timelapseSpeed}× — no sound")
        reconfigure()
    }

    // ------------------------------------------------------------------ UI refresh

    private val uiTicker = object : Runnable {
        override fun run() {
            tickUi()
            main.postDelayed(this, 100)
        }
    }

    private fun tickUi() {
        val st = renderer.state
        if (st != null) {
            val target = -st.betaDeg.toFloat()
            if (abs(HorizonMath.wrapDeg((target - uiRotation).toDouble())) > 1) {
                val delta = HorizonMath.wrapDeg((target - uiRotation).toDouble()).toFloat()
                uiRotation += delta
                for (v in rotatingViews()) v.animate().rotation(uiRotation).setDuration(280).start()
                overlay.uiRotation = uiRotation
            }
        }
        if (recorder.isRecording) {
            val ms = recorder.elapsedMs
            var t = formatTime(ms)
            if (settings.captureMode == CaptureMode.TIMELAPSE) t += "  ▸ " + formatTime(recorder.lastPtsNs / 1_000_000L)
            recTime.text = t
        }
        infoText.text = infoLine(st)
        renderer.error?.let { overlay.warning = it }
    }

    private fun rotatingViews(): List<View> {
        val list = mutableListOf<View>(btnSettings, chipLock, chipFrame, btnTorch, btnTimer, btnEv, thumb, btnSwitch, toastView)
        for (i in 0 until zoomRow.childCount) list.add(zoomRow.getChildAt(i))
        return list
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun infoLine(st: com.darshan.horizoncam.gl.RenderState?): String {
        val sb = StringBuilder()
        when (settings.captureMode) {
            CaptureMode.PHOTO -> sb.append("PHOTO ").append(settings.photoAspect.label)
            CaptureMode.VIDEO -> sb.append(settings.videoQuality.label).append(sessionFps)
            CaptureMode.TIMELAPSE -> sb.append(settings.videoQuality.label).append(" · ").append(settings.timelapseSpeed).append("×")
        }
        if (st != null) {
            if (settings.lockMode != LockMode.OFF) sb.append(" · crop ").append("%.1f×".format(st.cropZoom))
            if (zoomRatio != 1f) sb.append(" · zoom ").append("%.1f×".format(zoomRatio))
        }
        if (iso > 0 && exposureNs > 0) {
            sb.append(" · ISO ").append(iso)
            val inv = 1_000_000_000.0 / exposureNs
            sb.append(if (inv >= 2) " 1/${inv.roundToInt()}" else " %.1fs".format(exposureNs / 1e9))
        }
        return sb.toString()
    }

    private fun lockDescription(): String = when (settings.lockMode) {
        LockMode.LOCK_360 -> "360° Horizon Lock: stays level through any rotation"
        LockMode.LEVEL -> "Auto Level: fixes tilt up to ±${settings.levelRange}°, less crop"
        LockMode.OFF -> "Horizon lock off"
    }

    private fun refreshUi() {
        val mode = settings.captureMode
        shutter.mode = mode
        for ((v, m) in listOf(modePhoto to CaptureMode.PHOTO, modeVideo to CaptureMode.VIDEO, modeTimelapse to CaptureMode.TIMELAPSE)) {
            val sel = m == mode
            v.setTextColor(if (sel) getColor(R.color.accent) else getColor(R.color.text_dim))
            v.setBackgroundResource(if (sel) R.drawable.bg_mode_selected else 0)
        }
        when (settings.lockMode) {
            LockMode.LOCK_360 -> { chipLock.text = "360°"; chipLock.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_360, 0, 0, 0) }
            LockMode.LEVEL -> { chipLock.text = "±${settings.levelRange}°"; chipLock.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_straighten, 0, 0, 0) }
            LockMode.OFF -> { chipLock.text = "OFF"; chipLock.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_lock_open, 0, 0, 0) }
        }
        val aspect = if (mode == CaptureMode.PHOTO) settings.photoAspect else settings.videoAspect
        val square = aspect == AspectRatio.R1_1
        when (settings.frameOrientation) {
            FrameOrientation.AUTO -> { chipFrame.text = if (square) "1:1" else "AUTO"; chipFrame.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_orientation_auto, 0, 0, 0) }
            FrameOrientation.LANDSCAPE -> { chipFrame.text = if (square) "1:1" else aspect.label; chipFrame.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_crop_landscape, 0, 0, 0) }
            FrameOrientation.PORTRAIT -> {
                val parts = aspect.label.split(":")
                chipFrame.text = if (square) "1:1" else "${parts[1]}:${parts[0]}"
                chipFrame.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_crop_portrait, 0, 0, 0)
            }
        }
        btnTorch.setImageResource(if (torchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
        btnTorch.imageTintList = android.content.res.ColorStateList.valueOf(if (torchOn) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
        btnTimer.setImageResource(when (settings.timerSec) { 3 -> R.drawable.ic_timer_3; 10 -> R.drawable.ic_timer_10; else -> R.drawable.ic_timer_off })
        btnEv.imageTintList = android.content.res.ColorStateList.valueOf(
            if (evIndex != 0 || wb != WbPreset.AUTO) getColor(R.color.accent) else 0xFFFFFFFF.toInt()
        )
        val rec = recorder.isRecording
        recTime.visibility = if (rec) View.VISIBLE else View.GONE
        val enabled = !rec
        for (v in listOf<View>(btnSettings, chipLock, chipFrame, btnSwitch, modePhoto, modeVideo, modeTimelapse, thumb)) {
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else 0.35f
        }
        btnSwitch.visibility = if (frontLens != null && backLenses.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        refreshLensButtons()
    }

    // ------------------------------------------------------------------ lens buttons

    private fun rebuildLensButtons() {
        zoomRow.removeAllViews()
        val front = infoFor(currentLens)?.isFront ?: false
        val lenses = if (front) emptyList() else backLenses
        if (lenses.size <= 1) {
            zoomRow.visibility = View.INVISIBLE
            return
        }
        zoomRow.visibility = View.VISIBLE
        for (lens in lenses) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFFFFFFFF.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                tag = lens
                setOnClickListener { if (!recorder.isRecording || lens.cameraId == currentLens?.cameraId) selectLens(lens) }
            }
            val lp = LinearLayout.LayoutParams((38 * dp).toInt(), (38 * dp).toInt())
            lp.setMargins((2 * dp).toInt(), 0, (2 * dp).toInt(), 0)
            zoomRow.addView(tv, lp)
        }
        refreshLensButtons()
    }

    private fun refreshLensButtons() {
        val cur = currentLens
        var nearest: View? = null
        var best = Float.MAX_VALUE
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i) as TextView
            val lens = v.tag as LensOption
            if (lens.cameraId == cur?.cameraId) {
                val d = abs(lens.zoomRatio - zoomRatio)
                if (d < best && zoomRatio >= lens.zoomRatio - 0.05f) { best = d; nearest = v }
            }
        }
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i) as TextView
            val lens = v.tag as LensOption
            val sel = v === nearest
            v.setBackgroundResource(if (sel) R.drawable.bg_lens else 0)
            v.setTextColor(if (sel) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
            v.text = if (sel && abs(zoomRatio - lens.zoomRatio) > 0.05f && lens.cameraId == cur?.cameraId) {
                val z = (zoomRatio * 10).roundToInt() / 10f
                if (z == z.toInt().toFloat()) "${z.toInt()}×" else "$z×"
            } else lens.label
        }
    }

    // ------------------------------------------------------------------ exposure / WB

    private fun buildWbRow() {
        wbRow.removeAllViews()
        for (p in WbPreset.values()) {
            val tv = TextView(this).apply {
                text = p.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                tag = p
                setOnClickListener {
                    wb = p
                    camera.setWhiteBalance(p)
                    refreshEvPanel()
                    refreshUi()
                }
            }
            wbRow.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun refreshEvPanel() {
        val info = camera.currentInfo
        if (info != null && info.evRange.lower != info.evRange.upper) {
            evSeek.isEnabled = true
            evSeek.max = info.evRange.upper - info.evRange.lower
            evSeek.progress = evIndex - info.evRange.lower
            val ev = evIndex * info.evStep
            evLabel.text = "Exposure %+.1f EV   (tap to reset)".format(ev)
        } else {
            evSeek.isEnabled = false
            evLabel.text = "Exposure compensation not supported"
        }
        for (i in 0 until wbRow.childCount) {
            val v = wbRow.getChildAt(i) as TextView
            val sel = v.tag == wb
            v.setTextColor(if (sel) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
            v.setBackgroundResource(if (sel) R.drawable.bg_mode_selected else 0)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun openSettings() {
        if (recorder.isRecording) return
        val panel = settingsPanel ?: SettingsPanel(this) { closeSettings() }.also {
            settingsPanel = it
            settingsHost.addView(it)
        }
        panel.setContent(settingRows())
        settingsHost.visibility = View.VISIBLE
        settingsHost.alpha = 0f
        settingsHost.animate().alpha(1f).setDuration(150).start()
        updateBackCallback()
    }

    private fun closeSettings() {
        settingsHost.visibility = View.GONE
        updateBackCallback()
        rebuildLensButtons()
        reconfigure()
    }

    private fun settingRows(): List<Any> {
        val speeds = listOf(2, 5, 10, 15, 30, 60)
        val ranges = listOf(15, 30, 45)
        val rows = mutableListOf<Any>()
        val H = "Horizon lock"
        rows += SettingRow(H, "Lock mode", "360° keeps the horizon level through any rotation. Auto Level fixes smaller tilts with less crop.",
            listOf("360° Lock", "Auto Level", "Off"), { settings.lockMode.ordinal }, { settings.lockMode = LockMode.values()[it]; onLockModeChanged() })
        rows += SettingRow(H, "Auto Level range", "Largest tilt Auto Level corrects. Larger range means more crop.",
            ranges.map { "±$it°" }, { ranges.indexOf(settings.levelRange).coerceAtLeast(0) }, { settings.levelRange = ranges[it] },
            { settings.lockMode == LockMode.LEVEL })
        rows += SettingRow(H, "Frame orientation", "Auto picks landscape or portrait from how you hold the phone when you start recording.",
            FrameOrientation.values().map { it.label }, { settings.frameOrientation.ordinal }, { settings.frameOrientation = FrameOrientation.values()[it] })
        rows += SettingRow(H, "Stabilization", "Steady uses the camera's own stabilization as well. Lock only gives the most rigid horizon.",
            listOf("Steady", "Lock only"), { if (settings.cameraStabilization) 0 else 1 }, { settings.cameraStabilization = it == 0 })
        rows += SettingRow(H, "Lock response", "Auto is smooth with Steady and rigid with Lock only.",
            listOf("Auto", "Rigid", "Smooth"), { settings.response }, { settings.response = it })
        rows += SettingRow(H, "Ultra-wide for 360°", "Switches to the widest lens in 360° mode to make up for the crop.",
            listOf("On", "Off"), { if (settings.preferUltraWide) 0 else 1 }, { settings.preferUltraWide = it == 0 })
        rows += SettingRow(H, "Preview", "Gimbal view shows the full camera view with the level recording frame. Output view shows exactly what is saved.",
            PreviewStyle.values().map { it.label }, { settings.previewStyle.ordinal }, { settings.previewStyle = PreviewStyle.values()[it] })

        val V = "Video"
        rows += SettingRow(V, "Resolution", "Output resolution. The camera input is picked so the crop still has full detail where possible.",
            VideoQuality.values().map { it.label }, { settings.videoQuality.ordinal }, { settings.videoQuality = VideoQuality.values()[it] })
        rows += SettingRow(V, "Frame rate", "", listOf("30 fps", "60 fps"), { if (settings.fps == 60) 1 else 0 }, { settings.fps = if (it == 1) 60 else 30 })
        rows += SettingRow(V, "Aspect ratio", "", AspectRatio.values().map { it.label }, { settings.videoAspect.ordinal }, { settings.videoAspect = AspectRatio.values()[it] })
        rows += SettingRow(V, "Codec", "HEVC gives smaller files. H.264 plays everywhere.", listOf("H.264", "HEVC"), { if (settings.hevc) 1 else 0 }, { settings.hevc = it == 1 })
        rows += SettingRow(V, "Bitrate", "", listOf("Standard", "High"), { if (settings.highBitrate) 1 else 0 }, { settings.highBitrate = it == 1 })
        rows += SettingRow(V, "Record audio", "", listOf("On", "Off"), { if (settings.recordAudio) 0 else 1 }, { settings.recordAudio = it == 0 })
        rows += SettingRow(V, "Time-lapse speed", "How much faster the time-lapse plays than real time.",
            speeds.map { "$it×" }, { speeds.indexOf(settings.timelapseSpeed).coerceAtLeast(0) }, { settings.timelapseSpeed = speeds[it] })

        val P = "Photo"
        rows += SettingRow(P, "Photo aspect ratio", "Photos are leveled using the exact moment of exposure.",
            AspectRatio.values().map { it.label }, { settings.photoAspect.ordinal }, { settings.photoAspect = AspectRatio.values()[it] })

        val G = "General"
        rows += SettingRow(G, "Grid", "", listOf("Off", "3×3"), { if (settings.grid) 1 else 0 }, { settings.grid = it == 1 })
        rows += SettingRow(G, "Level indicator", "", listOf("On", "Off"), { if (settings.levelIndicator) 0 else 1 }, { settings.levelIndicator = it == 0 })
        rows += SettingRow(G, "Volume keys", "", VolumeAction.values().map { it.label }, { settings.volumeAction.ordinal }, { settings.volumeAction = VolumeAction.values()[it] })
        rows += SettingRow(G, "Shutter sound", "", listOf("On", "Off"), { if (settings.shutterSound) 0 else 1 }, { settings.shutterSound = it == 0 })
        rows += SettingRow(G, "Haptics", "Vibrates when the phone is level.", listOf("On", "Off"), { if (settings.haptics) 0 else 1 }, { settings.haptics = it == 0 })
        rows += SettingRow(G, "Mirror front camera", "Save selfies as shown in the preview.", listOf("Off", "On"), { if (settings.mirrorFront) 1 else 0 }, { settings.mirrorFront = it == 1 })

        val A = "About"
        rows += ActionRow(A, "How Horizon Lock works", "Quick tips") { showWelcome() }
        rows += ActionRow(A, "Camera diagnostics", "Device and camera details to copy into a bug report") { showDiagnostics() }
        rows += ActionRow(A, "Last crash report", if (CrashLogger.lastCrash(this) != null) "A crash was recorded. Tap to view." else "No crashes recorded") {
            val c = CrashLogger.lastCrash(this)
            if (c != null) showCrashDialog(c) else toast("No crashes recorded")
        }
        rows += ActionRow(A, "HorizonCam ${CrashLogger.versionName(this)}", "Horizon-locked video for every Android phone") {}
        return rows
    }

    // ------------------------------------------------------------------ dialogs

    private fun dialogBuilder() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun maybeShowWelcome() {
        if (settings.showHints) {
            settings.showHints = false
            showWelcome()
        }
    }

    private fun showWelcome() {
        dialogBuilder()
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_body)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied")
    }

    private fun showCrashDialog(text: String) {
        dialogBuilder()
            .setTitle("HorizonCam crashed last time")
            .setMessage(text.take(4000))
            .setPositiveButton("Copy") { _, _ -> copyText("crash", text) }
            .setNeutralButton("Clear") { _, _ -> CrashLogger.clear(this) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDiagnostics() {
        val sb = StringBuilder()
        sb.append("HorizonCam ${CrashLogger.versionName(this)}\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Motion sensor: ${sensor.sensorName}\n")
        val st = renderer.state
        sb.append("Session: input=${sessionInput?.let { "${it.width}x${it.height}" }} fps=$sessionFps preview=${st?.fps?.let { "%.1f".format(it) }}\n")
        sb.append("Frame: ${st?.frameW}x${st?.frameH} crop=${st?.cropZoom?.let { "%.2f".format(it) }} roll=${st?.rollDeg?.let { "%.1f".format(it) }}\n")
        sb.append("Encoders: AVC=${EncoderSupport.hasEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)} HEVC=${EncoderSupport.hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)}\n")
        sb.append("Lenses: ${backLenses.joinToString { "${it.label}@${it.cameraId}/${it.zoomRatio}" }} front=${frontLens?.cameraId}\n\n")
        for (c in cameras) sb.append(c.describe()).append('\n')
        CrashLogger.nonFatalLog(this)?.let { sb.append("\nRecent errors:\n").append(it.takeLast(4000)) }
        val text = sb.toString()
        dialogBuilder()
            .setTitle("Camera diagnostics")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ -> copyText("diagnostics", text) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ toast

    private val hideToast = Runnable { toastView.animate().alpha(0f).setDuration(200).withEndAction { toastView.visibility = View.GONE }.start() }

    private fun toast(msg: String) {
        toastView.text = msg
        toastView.rotation = uiRotation
        toastView.visibility = View.VISIBLE
        toastView.animate().cancel()
        toastView.alpha = 1f
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, 2200)
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!initialized) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (settingsHost.visibility == View.VISIBLE || permissionPanel.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
            val action = if (keyCode == KeyEvent.KEYCODE_CAMERA) VolumeAction.SHUTTER else settings.volumeAction
            when (action) {
                VolumeAction.SHUTTER -> {
                    if (event.repeatCount == 0) onShutter()
                    return true
                }
                VolumeAction.ZOOM -> {
                    val info = camera.currentInfo ?: return true
                    val f = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.1f else 1 / 1.1f
                    zoomRatio = (zoomRatio * f).coerceIn(info.minZoom, max(info.minZoom, info.maxZoom))
                    camera.setZoom(zoomRatio)
                    refreshLensButtons()
                    return true
                }
                VolumeAction.OFF -> {}
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!initialized) return super.onKeyUp(keyCode, event)
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            settings.volumeAction != VolumeAction.OFF && settingsHost.visibility != View.VISIBLE
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val REQ_PERMS = 42
        const val EXTRA_SKIP_REPORT = "skip_report"
    }
}
