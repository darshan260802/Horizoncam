package com.darshan.horizoncam

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.darshan.horizoncam.gl.RenderState
import com.darshan.horizoncam.media.EncoderSupport
import com.darshan.horizoncam.media.MediaSaver
import com.darshan.horizoncam.media.PhotoProcessor
import com.darshan.horizoncam.media.SoundPolicy
import com.darshan.horizoncam.media.VideoRecorder
import com.darshan.horizoncam.sensor.HorizonSensor
import com.darshan.horizoncam.ui.OverlayView
import com.darshan.horizoncam.ui.QuickItem
import com.darshan.horizoncam.ui.QuickPanel
import com.darshan.horizoncam.ui.ShutterButton
import com.darshan.horizoncam.ui.Ui
import com.darshan.horizoncam.ui.ZoomBar
import com.darshan.horizoncam.util.CrashLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
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
    private lateinit var curtain: View
    private lateinit var overlay: OverlayView
    private lateinit var topBar: View
    private lateinit var btnSteady: ImageButton
    private lateinit var recTime: TextView
    private lateinit var infoText: TextView
    private lateinit var chipRes: TextView
    private lateinit var steadyMenu: LinearLayout
    private lateinit var resMenu: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var quickPanel: QuickPanel
    private lateinit var btnFrame: ImageButton
    private lateinit var zoomRow: ZoomBar
    private lateinit var btnQuick: ImageButton
    private lateinit var modeRow: View
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var modeTimelapse: TextView
    private lateinit var thumb: ImageView
    private lateinit var btnPause: ImageButton
    private lateinit var shutter: ShutterButton
    private lateinit var btnSwitch: ImageButton
    private lateinit var btnSnap: ImageButton
    private lateinit var toastView: TextView
    private lateinit var permissionPanel: View

    // state
    private var cameras: List<CameraInfo> = emptyList()
    private var backLenses: List<LensOption> = emptyList()
    private var frontLens: LensOption? = null
    private var currentLens: LensOption? = null
    private var zoomRatio = 1f
    private var zoomAnimator: ValueAnimator? = null
    private var zoomDragStart = 1f
    private var lastZoomTick = 0
    private var torchOn = false
    private var evIndex = 0
    private var evDragStart = 0
    private var evDragging = false
    private var lastFocusTapAt = 0L
    private var wb = WbPreset.AUTO
    private var cameraReady = false
    private var resumed = false
    private var lastSpec: SessionSpec? = null
    private var sessionInput: Size? = null
    private var sessionInfo: CameraInfo? = null
    private var sessionFps = 30
    private var activeExtension = -1
    private var extensionWarnedFor = -1
    private val extensionCache = HashMap<String, List<Int>>()
    private var lastLockMode: LockMode? = null
    private var areaAspect = 0f
    private var ticks = 0
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
    private fun px(v: Float) = (v * dp + 0.5f).toInt()

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
        lastLockMode = settings.lockMode
        CrashLogger.step(this, "motion sensor")
        sensor = HorizonSensor(this)
        CrashLogger.step(this, "OpenGL renderer")
        renderer = HorizonRenderer(sensor)
        renderer.frameListener = {
            renderer.frameListener = null
            CrashLogger.step(this, "first camera frame")
            main.postDelayed({ CrashLogger.startupDone(this) }, 1500)
        }
        renderer.sessionFrameListener = { main.post { curtainOut() } }
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
        overlay.onLevelReached = { if (settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        CrashLogger.step(this, "controls")
        setupListeners()
        setupGestures()
        setupInsets()
        rebuildLensButtons()
        applyRenderConfig()
        refreshUi()
        main.post(uiTicker)

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
        syncWithSettings()
        refreshThumbnail()
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
        }
        hidePopups()
        refreshUi()
        super.onPause()
    }

    override fun onDestroy() {
        if (!initialized) {
            super.onDestroy()
            return
        }
        main.removeCallbacksAndMessages(null)
        zoomAnimator?.cancel()
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

    /** Picks up changes made on the settings screen. */
    private fun syncWithSettings() {
        if (lastLockMode != settings.lockMode) {
            lastLockMode = settings.lockMode
            pickLensForLockMode()
        }
        rebuildLensButtons()
        applyRenderConfig()
        refreshUi()
    }

    /** Pop-ups that are fading out (they no longer count as open). */
    private val hiding = HashSet<View>()

    private fun isOpen(v: View) = v.visibility == View.VISIBLE && v !in hiding

    private fun popupOpen() = isOpen(quickPanel) || isOpen(steadyMenu) || isOpen(resMenu)

    /** Closes the open pop-up. Returns false when nothing was open. */
    private fun handleBack(): Boolean = hidePopups()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!initialized || !handleBack()) super.onBackPressed()
    }

    private var backCallback: Any? = null

    /** Android 13+: predictive back only reaches the app through a registered callback. */
    private fun updateBackCallback(needed: Boolean = popupOpen()) {
        if (Build.VERSION.SDK_INT < 33) return
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

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        preview = findViewById(R.id.preview)
        curtain = findViewById(R.id.curtain)
        overlay = findViewById(R.id.overlay)
        topBar = findViewById(R.id.topBar)
        btnSteady = findViewById(R.id.btnSteady)
        recTime = findViewById(R.id.recTime)
        infoText = findViewById(R.id.infoText)
        chipRes = findViewById(R.id.chipRes)
        steadyMenu = findViewById(R.id.steadyMenu)
        resMenu = findViewById(R.id.resMenu)
        bottomPanel = findViewById(R.id.bottomPanel)
        quickPanel = findViewById(R.id.quickPanel)
        btnFrame = findViewById(R.id.btnFrame)
        zoomRow = findViewById(R.id.zoomRow)
        btnQuick = findViewById(R.id.btnQuick)
        modeRow = findViewById(R.id.modeRow)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        modeTimelapse = findViewById(R.id.modeTimelapse)
        thumb = findViewById(R.id.thumb)
        btnPause = findViewById(R.id.btnPause)
        shutter = findViewById(R.id.shutter)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnSnap = findViewById(R.id.btnSnap)
        toastView = findViewById(R.id.toast)
        permissionPanel = findViewById(R.id.permissionPanel)
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
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop
            topBar.requestLayout()
            for (m in listOf<View>(steadyMenu, resMenu)) {
                (m.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop + px(60f)
                m.requestLayout()
            }
            bottomPanel.setPadding(0, 0, 0, insetBottom + px(8f))
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
        btnSteady.setOnClickListener { toggleMenu(steadyMenu) { buildSteadyMenu() } }
        chipRes.setOnClickListener { toggleMenu(resMenu) { buildResMenu() } }
        btnQuick.setOnClickListener { toggleQuickPanel() }
        btnFrame.setOnClickListener { cycleFrame() }
        modePhoto.setOnClickListener { setMode(CaptureMode.PHOTO) }
        modeVideo.setOnClickListener { setMode(CaptureMode.VIDEO) }
        modeTimelapse.setOnClickListener { setMode(CaptureMode.TIMELAPSE) }
        shutter.setOnClickListener {
            if (settings.haptics) shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onShutter()
        }
        btnSwitch.setOnClickListener { tap(); switchCamera() }
        thumb.setOnClickListener { tap(); openLastCapture() }
        btnPause.setOnClickListener { togglePause() }
        btnSnap.setOnClickListener { takeSnapshot() }
        zoomRow.dragListener = object : ZoomBar.DragListener {
            override fun onZoomDragStart() {
                zoomAnimator?.cancel()
                zoomDragStart = zoomRatio
                lastZoomTick = (zoomRatio * 10).roundToInt()
            }
            override fun onZoomDrag(totalDx: Float) {
                val info = camera.currentInfo ?: return
                val z = (zoomDragStart * exp(totalDx / (150f * dp))).coerceIn(info.minZoom, max(info.minZoom, info.maxZoom))
                setZoomNow(z)
                val tick = (z * 10).roundToInt()
                if (tick != lastZoomTick) {
                    lastZoomTick = tick
                    if (settings.haptics && tick % 5 == 0) zoomRow.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            override fun onZoomDragEnd() {}
        }
    }

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val info = camera.currentInfo ?: return true
                zoomAnimator?.cancel()
                setZoomNow((zoomRatio * detector.scaleFactor).coerceIn(info.minZoom, max(info.minZoom, info.maxZoom)))
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                evDragging = false
                evDragStart = evIndex
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (hidePopups()) return true
                focusAt(e.x, e.y, false)
                lastFocusTapAt = SystemClock.uptimeMillis()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (evDragging) return
                focusAt(e.x, e.y, true)
                lastFocusTapAt = SystemClock.uptimeMillis()
                if (settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // Right after tap-to-focus, dragging up or down sets the brightness.
                val afterFocus = SystemClock.uptimeMillis() - lastFocusTapAt < 4000
                if (!evDragging && afterFocus && abs(dy) > 16 * dp && abs(dy) > abs(dx) * 1.5f) evDragging = true
                if (evDragging) {
                    lastFocusTapAt = SystemClock.uptimeMillis()
                    val info = camera.currentInfo ?: return true
                    val r = info.evRange
                    if (r.lower == r.upper) return true
                    val steps = r.upper - r.lower
                    val newIndex = (evDragStart - dy / (260f * dp) * steps).roundToInt().coerceIn(r.lower, r.upper)
                    if (newIndex != evIndex) {
                        evIndex = newIndex
                        camera.setEv(evIndex)
                        if (settings.haptics && evIndex == 0) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    overlay.showEv((evIndex - r.lower).toFloat() / steps, "%+.1f".format(evIndex * info.evStep))
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || recorder.isRecording || evDragging) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > 100 * dp && abs(vx) > abs(vy) * 1.5f) {
                    // Swipe sideways: next / previous mode.
                    val modes = CaptureMode.values()
                    val i = settings.captureMode.ordinal + if (dx < 0) 1 else -1
                    if (i in modes.indices) setMode(modes[i])
                    return true
                }
                if (abs(dy) > 100 * dp && abs(vy) > abs(vx) * 1.5f) {
                    // Swipe up or down: switch between the back and front camera.
                    if (frontLens != null && backLenses.isNotEmpty()) {
                        tap()
                        switchCamera()
                    }
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

    private fun tap() {
        if (settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * Camera sounds follow the Shutter sound setting and stay quiet when the phone is on silent or
     * vibrate (MediaActionSound itself would play anyway). Where the law requires the sound
     * (Android 13+ reports this), it always plays.
     */
    private fun playSound(kind: Int) {
        if (!SoundPolicy.forced()) {
            if (!settings.shutterSound) return
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        }
        sound?.play(kind)
    }

    // ------------------------------------------------------------------ pop-ups

    private fun toggleMenu(menu: LinearLayout, build: () -> Unit) {
        tap()
        if (isOpen(menu)) {
            hidePopups()
            return
        }
        if (recorder.isRecording) return
        hidePopups()
        build()
        menu.animate().cancel()
        hiding.remove(menu)
        menu.visibility = View.VISIBLE
        menu.alpha = 0f
        menu.translationY = -12 * dp
        menu.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
        updateBackCallback(true)
    }

    /** Hides every pop-up. Returns true if something was open. */
    private fun hidePopups(): Boolean {
        var any = false
        for (v in listOf<View>(steadyMenu, resMenu, quickPanel)) {
            if (!isOpen(v)) continue
            any = true
            hiding.add(v)
            val dy = if (v === quickPanel) 16 * dp else -10 * dp
            v.animate().cancel()
            v.animate().alpha(0f).translationY(dy).setDuration(130).withEndAction {
                hiding.remove(v)
                v.visibility = View.GONE
                if (v === quickPanel) quickPanel.clearDetail()
                updateBackCallback()
            }.start()
        }
        btnQuick.setImageResource(R.drawable.ic_quick_controls)
        if (any) updateBackCallback(false)
        return any
    }

    private fun menuTitle(text: String, inset: Float = 18f) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF9A9A9E.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(px(inset), px(6f), px(inset), px(6f))
    }

    private fun menuRow(icon: Int, title: String, desc: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(60f)
            setPadding(px(18f), px(10f), px(16f), px(10f))
            background = Ui.ripple(null, Ui.rounded(Color.WHITE, 0f))
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { tap(); onClick() }
        }
        val color = if (selected) Ui.ACCENT else Color.WHITE
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color)
        }, LinearLayout.LayoutParams(px(24f), px(24f)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(TextView(this).apply {
            text = desc
            setTextColor(0xFFA0A0A5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, px(2f), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(16f) })
        if (selected) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(Ui.ACCENT)
            }, LinearLayout.LayoutParams(px(22f), px(22f)).apply { leftMargin = px(10f) })
        }
        return row
    }

    private fun menuChip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (selected) Ui.ACCENT else Color.WHITE)
        background = Ui.ripple(GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(if (selected) 0x26FFC83D else 0x14FFFFFF)
            setStroke(px(1.5f), if (selected) Ui.ACCENT else 0x40FFFFFF)
        }, Ui.rounded(Color.WHITE, 12 * dp))
        isClickable = true
        isFocusable = true
        setOnClickListener { tap(); onClick() }
    }

    /** Grid of chips, [perRow] per row. */
    private fun chipGrid(labels: List<String>, selected: Int, perRow: Int, onPick: (Int) -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(6f))
        }
        labels.chunked(perRow).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { j, label ->
                val idx = r * perRow + j
                row.addView(menuChip(label, idx == selected) { onPick(idx) }, LinearLayout.LayoutParams(px(72f), px(40f)).apply {
                    setMargins(px(3f), px(3f), px(3f), px(3f))
                })
            }
            col.addView(row)
        }
        return col
    }

    private fun buildSteadyMenu() {
        steadyMenu.removeAllViews()
        steadyMenu.addView(menuTitle(if (settings.captureMode == CaptureMode.PHOTO) "Leveling" else "Stabilization"))
        val photo = settings.captureMode == CaptureMode.PHOTO
        val options = listOf(
            Triple(LockMode.OFF, R.drawable.ic_steady_off, "Off" to
                (if (photo) "Photos are saved as shot" else "Camera stabilization only, no leveling")),
            Triple(LockMode.LEVEL, R.drawable.ic_steady, "Steady" to
                "Straightens tilts up to ±${settings.levelRange}° with a small crop"),
            Triple(LockMode.LOCK_360, R.drawable.ic_horizon_lock, "Steady + horizon lock" to
                "The horizon stays level even if you turn the phone all the way round")
        )
        for ((mode, icon, text) in options) {
            steadyMenu.addView(menuRow(icon, text.first, text.second, settings.lockMode == mode) {
                settings.lockMode = mode
                lastLockMode = mode
                hidePopups()
                toast(lockDescription())
                onLockModeChanged()
            })
        }
    }

    private fun buildResMenu() {
        resMenu.removeAllViews()
        when (settings.captureMode) {
            CaptureMode.PHOTO -> {
                val ratios = ratioOptions()
                resMenu.addView(menuTitle("Picture ratio", 4f))
                resMenu.addView(chipGrid(ratios.map { it.second }, ratios.indexOfFirst { it.first == settings.photoAspect }, 3) { i ->
                    setAspect(ratios[i].first)
                    hidePopups()
                })
            }
            CaptureMode.VIDEO -> {
                val combos = videoCombos()
                resMenu.addView(menuTitle("Video size", 4f))
                resMenu.addView(chipGrid(combos.map { comboLabel(it) },
                    combos.indexOfFirst { it.first == settings.videoQuality && it.second == effectiveFps() }, 4) { i ->
                    settings.videoQuality = combos[i].first
                    settings.fps = combos[i].second
                    hidePopups()
                    reconfigure()
                })
                val ratios = ratioOptions()
                resMenu.addView(menuTitle("Video ratio", 4f))
                resMenu.addView(chipGrid(ratios.map { it.second }, ratios.indexOfFirst { it.first == settings.videoAspect }, 3) { i ->
                    setAspect(ratios[i].first)
                    hidePopups()
                })
            }
            CaptureMode.TIMELAPSE -> {
                val qualities = VideoQuality.values().reversed()
                resMenu.addView(menuTitle("Video size", 4f))
                resMenu.addView(chipGrid(qualities.map { shortQuality(it) }, qualities.indexOf(settings.videoQuality), 4) { i ->
                    settings.videoQuality = qualities[i]
                    hidePopups()
                    reconfigure()
                })
                val speeds = SettingsActivity.HYPER_SPEEDS
                resMenu.addView(menuTitle("Speed", 4f))
                resMenu.addView(chipGrid(speeds.map { "${it}x" }, speeds.indexOf(settings.timelapseSpeed), 3) { i ->
                    settings.timelapseSpeed = speeds[i]
                    hidePopups()
                    refreshUi()
                })
            }
        }
    }

    private fun toggleQuickPanel() {
        tap()
        if (isOpen(quickPanel)) {
            hidePopups()
            return
        }
        if (recorder.isRecording) return
        hidePopups()
        quickPanel.clearDetail()
        refreshQuickPanel()
        quickPanel.animate().cancel()
        hiding.remove(quickPanel)
        quickPanel.visibility = View.VISIBLE
        quickPanel.alpha = 0f
        quickPanel.translationY = 24 * dp
        quickPanel.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        btnQuick.setImageResource(R.drawable.ic_close)
        updateBackCallback(true)
    }

    private fun refreshQuickPanel() {
        val items = ArrayList<QuickItem>()
        items += QuickItem("settings", R.drawable.ic_settings, "Settings", "", false) { openSettings() }
        items += QuickItem("torch", if (torchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off, "Torch",
            if (torchOn) "On" else "Off", torchOn) { toggleTorch() }
        val soundOn = SoundPolicy.forced() || settings.shutterSound
        items += QuickItem("sound", if (soundOn) R.drawable.ic_volume_up else R.drawable.ic_volume_off, "Sound",
            if (SoundPolicy.forced()) "Required" else onOff(soundOn), false) {
            if (SoundPolicy.forced()) {
                toast("The shutter sound can't be turned off in your region")
            } else {
                settings.shutterSound = !settings.shutterSound
                toast(if (settings.shutterSound) "Shutter sound on (muted when the phone is on silent or vibrate)" else "Shutter sound off")
                refreshQuickPanel()
            }
        }

        val timerValues = listOf(0, 3, 10)
        items += QuickItem("timer", timerIcon(), "Timer", if (settings.timerSec == 0) "Off" else "${settings.timerSec} sec", settings.timerSec != 0) {
            quickPanel.showChoices("timer", "Timer", listOf("Off", "3 sec", "10 sec"), timerValues.indexOf(settings.timerSec).coerceAtLeast(0)) { i ->
                settings.timerSec = timerValues[i]
                refreshQuickPanel()
            }
            refreshQuickPanel()
        }

        val ratios = ratioOptions()
        items += QuickItem("ratio", R.drawable.ic_aspect_ratio, "Ratio", ratioLabel(currentAspect()), false) {
            quickPanel.showChoices("ratio", "Ratio", ratios.map { it.second }, ratios.indexOfFirst { it.first == currentAspect() }) { i ->
                setAspect(ratios[i].first)
                refreshQuickPanel()
            }
            refreshQuickPanel()
        }

        when (settings.captureMode) {
            CaptureMode.PHOTO -> {
                val opts = processingOptions()
                val cur = opts.indexOfFirst { it.first == settings.photoProcessing }.coerceAtLeast(0)
                val ext = opts[cur].first
                items += QuickItem("proc", processingIcon(ext), "Processing", opts[cur].second, ext >= 0) {
                    if (opts.size <= 1) {
                        toast("This phone doesn't offer HDR or Night processing to other apps")
                    } else {
                        quickPanel.showChoices("proc", "Picture processing", opts.map { it.second }, cur) { i ->
                            settings.photoProcessing = opts[i].first
                            reconfigure()
                            refreshQuickPanel()
                        }
                        refreshQuickPanel()
                    }
                }
            }
            CaptureMode.VIDEO -> {
                val combos = videoCombos()
                items += QuickItem("res", R.drawable.ic_videocam, "Video size", videoLabel(), false) {
                    quickPanel.showChoices("res", "Video size", combos.map { comboLabel(it) },
                        combos.indexOfFirst { it.first == settings.videoQuality && it.second == effectiveFps() }) { i ->
                        settings.videoQuality = combos[i].first
                        settings.fps = combos[i].second
                        reconfigure()
                        refreshQuickPanel()
                    }
                    refreshQuickPanel()
                }
            }
            CaptureMode.TIMELAPSE -> {
                val speeds = SettingsActivity.HYPER_SPEEDS
                items += QuickItem("speed", R.drawable.ic_timelapse, "Speed", "${settings.timelapseSpeed}x", false) {
                    quickPanel.showChoices("speed", "Hyperlapse speed", speeds.map { "${it}x" }, speeds.indexOf(settings.timelapseSpeed)) { i ->
                        settings.timelapseSpeed = speeds[i]
                        refreshUi()
                    }
                    refreshQuickPanel()
                }
            }
        }

        items += QuickItem("ev", R.drawable.ic_exposure, "Brightness", evValueText(), evIndex != 0 || wb != WbPreset.AUTO) { toggleExposureDetail() }
        items += QuickItem("grid", R.drawable.ic_grid_on, "Grid", onOff(settings.grid), settings.grid) {
            settings.grid = !settings.grid
            applyRenderConfig()
            refreshQuickPanel()
        }
        items += QuickItem("level", R.drawable.ic_straighten, "Level", onOff(settings.levelIndicator), settings.levelIndicator) {
            settings.levelIndicator = !settings.levelIndicator
            applyRenderConfig()
            refreshQuickPanel()
        }
        quickPanel.setItems(items)
    }

    private fun toggleExposureDetail() {
        if (quickPanel.detailKey == "ev") {
            quickPanel.clearDetail()
            refreshQuickPanel()
            return
        }
        val range = camera.currentInfo?.evRange
        val hasEv = range != null && range.lower != range.upper
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = WbPreset.values().toList()
        for (p in presets) {
            val pill = Ui.pill(this, if (p == WbPreset.AUTO) "Auto WB" else p.label, p == wb) {
                tap()
                wb = p
                camera.setWhiteBalance(p)
                for (c in 0 until pills.childCount) {
                    val v = pills.getChildAt(c) as TextView
                    if (v.tag is WbPreset) Ui.styleSelected(v, v.tag == wb)
                }
                refreshQuickPanel()
            }
            pill.tag = p
            pills.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(8f) })
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        }
        val max = if (range != null && hasEv) range.upper - range.lower else 1
        val progress = if (range != null && hasEv) evIndex - range.lower else 0
        val seek = quickPanel.showSlider("ev", evTitle(), max, progress, scroller) { p ->
            if (range != null && hasEv) {
                evIndex = range.lower + p
                camera.setEv(evIndex)
                if (settings.haptics && evIndex == 0) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                quickPanel.setDetailTitle(evTitle())
                refreshQuickPanel()
            }
        }
        seek.isEnabled = hasEv
        refreshQuickPanel()
    }

    private fun evTitle(): String {
        val info = camera.currentInfo ?: return "Brightness"
        if (info.evRange.lower == info.evRange.upper) return "Brightness can't be changed on this camera"
        return "Brightness %+.1f".format(evIndex * info.evStep)
    }

    private fun evValueText(): String {
        val step = camera.currentInfo?.evStep ?: 0f
        return when {
            evIndex != 0 -> "%+.1f".format(evIndex * step)
            wb != WbPreset.AUTO -> wb.label
            else -> "Auto"
        }
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun timerIcon() = when (settings.timerSec) {
        3 -> R.drawable.ic_timer_3
        10 -> R.drawable.ic_timer_10
        else -> R.drawable.ic_timer_off
    }

    private fun processingIcon(ext: Int) = when (ext) {
        3 -> R.drawable.ic_hdr_on
        4 -> R.drawable.ic_night
        0 -> R.drawable.ic_auto_awesome
        else -> R.drawable.ic_photo_camera
    }

    private fun toggleTorch() {
        val info = camera.currentInfo
        if (info == null || !info.hasFlash) {
            toast("No flash on this camera")
            return
        }
        torchOn = !torchOn
        camera.setTorch(torchOn)
        refreshQuickPanel()
    }

    private fun cycleFrame() {
        tap()
        if (recorder.isRecording) return
        hidePopups()
        settings.frameOrientation = FrameOrientation.values()[(settings.frameOrientation.ordinal + 1) % FrameOrientation.values().size]
        toast(frameDescription())
        reconfigure()
    }

    private fun openSettings() {
        if (recorder.isRecording) return
        hidePopups()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ------------------------------------------------------------------ labels

    private fun shortQuality(q: VideoQuality) = when (q) {
        VideoQuality.HD -> "HD"
        VideoQuality.FHD -> "FHD"
        VideoQuality.QHD -> "QHD"
        VideoQuality.UHD -> "UHD"
    }

    /** Ratios as shown on a portrait screen (like the system camera): 3:4, 9:16, 1:1. */
    private fun ratioLabel(a: AspectRatio) = when (a) {
        AspectRatio.R4_3 -> "3:4"
        AspectRatio.R16_9 -> "9:16"
        AspectRatio.R1_1 -> "1:1"
    }

    private fun ratioOptions(): List<Pair<AspectRatio, String>> {
        val order = if (settings.captureMode == CaptureMode.PHOTO) listOf(AspectRatio.R4_3, AspectRatio.R16_9, AspectRatio.R1_1)
        else listOf(AspectRatio.R16_9, AspectRatio.R4_3, AspectRatio.R1_1)
        return order.map { it to ratioLabel(it) }
    }

    private fun setAspect(a: AspectRatio) {
        if (settings.captureMode == CaptureMode.PHOTO) settings.photoAspect = a else settings.videoAspect = a
        reconfigure()
    }

    private fun supports60(): Boolean = infoFor(currentLens)?.supportsFps(60) == true

    private fun effectiveFps(): Int = if (settings.fps == 60 && supports60()) 60 else 30

    private fun videoCombos(): List<Pair<VideoQuality, Int>> {
        val sixty = supports60()
        val out = ArrayList<Pair<VideoQuality, Int>>()
        for (q in VideoQuality.values().reversed()) {
            if (sixty) out += q to 60
            out += q to 30
        }
        return out
    }

    private fun comboLabel(c: Pair<VideoQuality, Int>) = "${shortQuality(c.first)} ${c.second}"

    private fun videoLabel() = "${shortQuality(settings.videoQuality)} ${effectiveFps()}"

    private fun chipLabel(): String = when (settings.captureMode) {
        CaptureMode.PHOTO -> ratioLabel(settings.photoAspect)
        CaptureMode.VIDEO -> videoLabel()
        CaptureMode.TIMELAPSE -> "${shortQuality(settings.videoQuality)} ${settings.timelapseSpeed}x"
    }

    private fun lockDescription(): String = when (settings.lockMode) {
        LockMode.LOCK_360 -> "Horizon lock on: the horizon stays level through any rotation"
        LockMode.LEVEL -> "Steady: straightens tilts up to ±${settings.levelRange}°"
        LockMode.OFF -> "Leveling off"
    }

    private fun frameDescription(): String {
        if (currentAspect() == AspectRatio.R1_1) return "Square frame"
        return when (settings.frameOrientation) {
            FrameOrientation.AUTO -> "Frame follows how you hold the phone"
            FrameOrientation.LANDSCAPE -> "Always landscape, even when you hold the phone upright"
            FrameOrientation.PORTRAIT -> "Always portrait, even when you hold the phone sideways"
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

    private fun currentAspect(): AspectRatio = if (settings.captureMode == CaptureMode.PHOTO) settings.photoAspect else settings.videoAspect

    private fun extensionsFor(cameraId: String): List<Int> =
        extensionCache.getOrPut(cameraId) { CameraController.supportedExtensions(cameraManager, cameraId) }

    /** (extension id or -1, label) offered for the current camera. */
    private fun processingOptions(): List<Pair<Int, String>> {
        val out = mutableListOf(-1 to "Standard")
        val id = currentLens?.cameraId ?: return out
        val exts = extensionsFor(id)
        for (e in listOf(0, 3, 4)) if (exts.contains(e)) out.add(e to CameraController.extensionName(e))
        return out
    }

    private fun buildSpec(): SessionSpec? {
        val lens = currentLens ?: return null
        val info = infoFor(lens) ?: return null
        return if (!videoMode()) {
            val (prev, jpeg) = info.choosePhotoSizes()
            val fallbacks = listOf(prev, Size(1440, 1080), Size(1280, 960), Size(1280, 720))
                .filter { s -> info.textureSizes().contains(s) }.distinct()
            val wanted = settings.photoProcessing
            val ext = if (wanted >= 0 && extensionsFor(info.id).contains(wanted)) wanted else -1
            SessionSpec(info.id, false, if (fallbacks.isEmpty()) listOf(prev) else fallbacks, jpeg, 30, false, zoomRatio, ext)
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
                it.fps == spec.fps && it.stabilization == spec.stabilization && it.jpegSize == spec.jpegSize &&
                it.extension == spec.extension
        } ?: false
        applyRenderConfig()
        if (same && !force) return
        val info = infoFor(currentLens)
        renderer.isFront = info?.isFront ?: false
        cameraReady = false
        activeExtension = -1
        lastSpec = spec
        curtainIn()
        camera.open(spec) { size -> renderer.prepareInputSurface(size.width, size.height) }
    }

    /** Re-evaluates the session after a setting change; reopens only when needed. */
    private fun reconfigure() {
        applyRenderConfig()
        refreshUi()
        openCamera(force = false)
    }

    private fun pickLensForLockMode() {
        if (settings.lockMode == LockMode.LOCK_360 && settings.preferUltraWide && !(infoFor(currentLens)?.isFront ?: false)) {
            val widest = backLenses.firstOrNull()
            if (widest != null && widest.relative < 1f && currentLens != widest) {
                selectLens(widest, reopen = false)
            }
        }
    }

    private fun onLockModeChanged() {
        pickLensForLockMode()
        reconfigure()
    }

    override fun onCameraOpened(info: CameraInfo, inputSize: Size) {
        CrashLogger.step(this, "camera ${info.id} streaming ${inputSize.width}x${inputSize.height}")
        main.post {
            cameraReady = true
            sessionInput = inputSize
            sessionInfo = info
            sessionFps = lastSpec?.fps ?: 30
            activeExtension = -1
            if (!info.hasFlash) torchOn = false
            camera.setTorch(torchOn)
            camera.setEv(evIndex)
            camera.setWhiteBalance(wb)
            SessionInfo.text = sessionDescription(renderer.state)
            updatePreviewArea()
            refreshUi()
        }
    }

    override fun onCameraError(message: String) {
        CrashLogger.nonFatal(this, "camera", RuntimeException(message))
        main.post {
            cameraReady = false
            curtainOut()
            toast(message)
        }
    }

    override fun onExtensionUnavailable() {
        main.post {
            activeExtension = -1
            val wanted = settings.photoProcessing
            if (wanted >= 0 && extensionWarnedFor != wanted) {
                extensionWarnedFor = wanted
                toast("${CameraController.extensionName(wanted)} isn't available right now, using standard processing")
            }
            refreshUi()
        }
    }

    override fun onExtensionActive(extension: Int) {
        main.post {
            activeExtension = extension
            SessionInfo.text = sessionDescription(renderer.state)
            refreshUi()
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
        hidePopups()
        val front = infoFor(currentLens)?.isFront ?: false
        if (front) {
            currentLens = defaultBackLens()
            settings.useFront = false
        } else {
            val f = frontLens ?: run { toast("No front camera"); return }
            currentLens = f
            settings.useFront = true
        }
        zoomAnimator?.cancel()
        zoomRatio = currentLens?.zoomRatio ?: 1f
        torchOn = false
        btnSwitch.animate().rotationBy(180f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        rebuildLensButtons()
        refreshUi()
        openCamera(force = true)
    }

    private fun selectLens(lens: LensOption, reopen: Boolean = true) {
        val sameCamera = currentLens?.cameraId == lens.cameraId
        currentLens = lens
        if (sameCamera) {
            animateZoomTo(lens.zoomRatio)
        } else {
            zoomAnimator?.cancel()
            zoomRatio = lens.zoomRatio
            if (reopen) openCamera(force = true) else lastSpec = null
        }
        refreshLensButtons()
    }

    /** Applies a zoom immediately (pinch / drag). */
    private fun setZoomNow(z: Float) {
        zoomRatio = z
        camera.setZoom(z)
        refreshLensButtons()
        overlay.showLabel(formatZoom(z))
    }

    /** Smooth zoom transition between lens presets. */
    private fun animateZoomTo(target: Float) {
        zoomAnimator?.cancel()
        val from = zoomRatio
        zoomAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                zoomRatio = it.animatedValue as Float
                camera.setZoom(zoomRatio)
                refreshLensButtons()
            }
            start()
        }
        overlay.showLabel(formatZoom(target))
    }

    private fun formatZoom(z: Float): String {
        val r = (z * 10).roundToInt() / 10f
        return if (r == floor(r)) "${r.toInt()}×" else "$r×"
    }

    // ------------------------------------------------------------------ smooth transitions

    private val curtainTimeout = Runnable { curtainOut() }

    private fun curtainIn() {
        curtain.animate().cancel()
        curtain.visibility = View.VISIBLE
        curtain.animate().alpha(1f).setDuration(120).start()
        main.removeCallbacks(curtainTimeout)
        main.postDelayed(curtainTimeout, 3000)
    }

    private fun curtainOut() {
        main.removeCallbacks(curtainTimeout)
        if (curtain.visibility != View.VISIBLE) return
        curtain.animate().cancel()
        curtain.animate().alpha(0f).setDuration(260).withEndAction { curtain.visibility = View.GONE }.start()
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
            aspect = currentAspect().ratio,
            outShort = if (photo) 1080 else settings.videoQuality.shortSide,
            smooth = smooth,
            previewStyle = settings.previewStyle,
            grid = settings.grid,
            mirrorFront = settings.mirrorFront,
            sharpness = when (settings.sharpness) { 0 -> 0.0; 2 -> 0.55; else -> 0.3 },
            dynamicCrop = photo
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

    /** Height / width of the recorded frame as it appears on screen. */
    private fun screenFrameAspect(): Float {
        val st = renderer.state
        if (st != null && st.frameW > 0 && st.frameH > 0) {
            val q = HorizonMath.isQuarterTurn(st.betaDeg + displayRotationDeg())
            return if (q) st.frameW.toFloat() / st.frameH else st.frameH.toFloat() / st.frameW
        }
        val r = currentAspect().ratio.toFloat()
        return if (settings.frameOrientation == FrameOrientation.LANDSCAPE) 1f / r else r
    }

    private fun updatePreviewArea() {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0 || h <= 0) return
        // The viewfinder starts right under the top bar, like the system camera; the bottom
        // controls float over it.
        val barH = if (topBar.height > 0) topBar.height else px(56f)
        val topLimit = (insetTop + barH).toFloat()
        val bottomLimit = h - insetBottom
        val frameAspect = screenFrameAspect()
        areaAspect = frameAspect
        val area: RectF
        if (settings.previewStyle == PreviewStyle.GIMBAL) {
            val info = sessionInfo
            val size = sessionInput
            val nd = if (info != null && size != null) info.naturalDims(size) else intArrayOf(3, 4)
            val disp = displayRotationDeg()
            val aspect = if (HorizonMath.isQuarterTurn(disp)) nd[0].toFloat() / nd[1] else nd[1].toFloat() / nd[0]
            val imgH = w * aspect
            val top = if (imgH <= bottomLimit - topLimit) topLimit else max(0f, bottomLimit - imgH)
            area = RectF(0f, top, w, top + imgH)
        } else {
            // 3:4 and wider frames sit in a 3:4 window under the top bar; taller ones grow downwards.
            val bottom = min(bottomLimit, topLimit + w * max(4f / 3f, frameAspect))
            area = RectF(0f, topLimit, w, bottom)
        }
        renderer.setPreviewArea(area, displayRotationDeg())
        overlay.previewArea = area
        overlay.displayDeg = displayRotationDeg()
    }

    // ------------------------------------------------------------------ capture

    private fun onShutter() {
        hidePopups()
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

    private var countdownRunnable: Runnable? = null

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
                    playSound(MediaActionSound.FOCUS_COMPLETE)
                    main.postDelayed(this, 1000)
                }
            }
        }
        shutter.progress = 0f
        playSound(MediaActionSound.FOCUS_COMPLETE)
        main.postDelayed(tick, 1000)
        countdownRunnable = tick
    }

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
        if (activeExtension == 4) toast("Hold still…")
        playSound(MediaActionSound.SHUTTER_CLICK)
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
                mirrorFront = settings.mirrorFront,
                sensorOrientation = info.sensorOrientation
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

    /** Still photo from the recording itself (horizon-locked, full video resolution). */
    private fun takeSnapshot() {
        if (!recorder.isRecording) return
        if (settings.haptics) btnSnap.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        overlay.flash()
        renderer.requestSnapshot { bmp ->
            if (bmp == null) {
                main.post { toast("Couldn't capture the photo") }
                return@requestSnapshot
            }
            worker.execute {
                val bytes = try {
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                } catch (e: Exception) { null }
                val scale = 240f / max(bmp.width, bmp.height)
                val small = try {
                    Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * scale).toInt()), max(1, (bmp.height * scale).toInt()), true)
                } catch (e: Exception) { null }
                bmp.recycle()
                if (bytes == null) {
                    main.post { toast("Couldn't save the photo") }
                    return@execute
                }
                MediaSaver.saveJpeg(this, bytes, null, MediaSaver.timestampName("HC", "jpg")) { uri ->
                    main.post {
                        if (uri != null) {
                            onSaved(uri, false, small)
                            toast("Photo saved")
                        } else {
                            toast("Couldn't save the photo")
                        }
                    }
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
                    if (q != settings.videoQuality) toast("${shortQuality(settings.videoQuality)} not supported by the encoder, recording ${shortQuality(q)}")
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
        playSound(MediaActionSound.START_VIDEO_RECORDING)
        shutter.recording = true
        hidePopups()
        refreshUi()
        tickUi()
    }

    private fun stopRecording() {
        if (!recorder.isRecording) return
        recorder.stop()
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        shutter.recording = false
        refreshUi()
    }

    private fun togglePause() {
        if (!recorder.isRecording) return
        tap()
        if (recorder.isPaused) recorder.resume() else recorder.pause()
        refreshUi()
        tickUi()
    }

    override fun onRecordingSaved(uri: Uri?, durationMs: Long) {
        main.post {
            if (uri != null) onSaved(uri, true, null)
            toast("Video saved")
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
        thumb.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
    }

    /**
     * Points the gallery button at the newest capture that still exists (re-checked on every
     * resume, so deleting a photo in the gallery updates the button).
     */
    private fun refreshThumbnail() {
        worker.execute {
            val latest = MediaSaver.latestCapture(this)
            main.post {
                if (latest == null) {
                    lastSavedUri = null
                    thumb.setImageResource(R.drawable.ic_photo_library)
                    thumb.scaleType = ImageView.ScaleType.CENTER
                } else if (latest.uri != lastSavedUri || thumb.scaleType != ImageView.ScaleType.CENTER_CROP) {
                    lastSavedUri = latest.uri
                    lastSavedVideo = latest.isVideo
                    loadThumbnailFor(latest.uri, latest.isVideo, bounce = false)
                }
            }
        }
    }

    private fun loadThumbnailFor(uri: Uri?, video: Boolean, bounce: Boolean = true) {
        if (uri == null) return
        worker.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.loadThumbnail(uri, Size(240, 240), null)
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
                if (uri != lastSavedUri) return@post
                thumb.setImageBitmap(bmp)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                if (bounce) bounceThumb()
            }
        }
    }

    /**
     * Opens the last capture straight in the phone's gallery app (no "open with" chooser). The
     * camera "review" action comes first: galleries that support it let you swipe through
     * recent captures.
     */
    private fun openLastCapture() {
        val uri = lastSavedUri
        if (uri == null) {
            toast("Nothing captured yet")
            return
        }
        val mime = if (lastSavedVideo) "video/mp4" else "image/jpeg"
        fun intent(action: String, pkg: String?) = Intent(action).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        val gallery = galleryPackage()
        val attempts = ArrayList<Intent>()
        if (gallery != null) {
            attempts += intent(ACTION_REVIEW, gallery)
            attempts += intent(Intent.ACTION_VIEW, gallery)
        }
        attempts += intent(Intent.ACTION_VIEW, null)
        for (i in attempts) {
            try {
                startActivity(i)
                return
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
        toast("No app found to open the file")
    }

    /** The phone's gallery app: the default "gallery" app, else a well-known one that is installed. */
    private fun galleryPackage(): String? {
        try {
            val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            val ri = packageManager.resolveActivity(selector, PackageManager.MATCH_DEFAULT_ONLY)
            val pkg = ri?.activityInfo?.packageName
            // "android" is the system chooser, shown when there is no default.
            if (pkg != null && pkg != "android") return pkg
        } catch (e: Exception) {
        }
        for (pkg in KNOWN_GALLERIES) {
            try {
                if (packageManager.getLaunchIntentForPackage(pkg) != null) return pkg
            } catch (e: Exception) {
            }
        }
        return null
    }

    // ------------------------------------------------------------------ modes

    private fun setMode(mode: CaptureMode) {
        if (recorder.isRecording || settings.captureMode == mode) return
        tap()
        cancelCountdown()
        hidePopups()
        settings.captureMode = mode
        shutter.mode = mode
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
        ticks++
        val st = renderer.state
        if (st != null) {
            val target = -st.betaDeg.toFloat()
            if (abs(HorizonMath.wrapDeg((target - uiRotation).toDouble())) > 1) {
                val delta = HorizonMath.wrapDeg((target - uiRotation).toDouble()).toFloat()
                uiRotation += delta
                for (v in rotatingViews()) {
                    v.animate().rotation(uiRotation).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
                }
                overlay.uiRotation = uiRotation
            }
        }
        if (recorder.isRecording) {
            var t = formatTime(recorder.elapsedMs)
            if (settings.captureMode == CaptureMode.TIMELAPSE) t += "  ▸ " + formatTime(recorder.lastPtsNs / 1_000_000L)
            val paused = recorder.isPaused
            val text = SpannableString((if (paused) "❚❚  " else "●  ") + t)
            text.setSpan(ForegroundColorSpan(if (paused) Color.WHITE else RED), 0, if (paused) 2 else 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            recTime.text = text
            recTime.alpha = if (paused && (SystemClock.uptimeMillis() / 600) % 2 == 0L) 0.4f else 1f
        }
        infoText.text = statusLine(st)
        if (abs(screenFrameAspect() - areaAspect) > 0.01f) updatePreviewArea()
        if (ticks % 10 == 0 && cameraReady) SessionInfo.text = sessionDescription(st)
        renderer.error?.let { overlay.warning = it }
    }

    private fun rotatingViews(): List<View> {
        val list = mutableListOf<View>(btnSteady, chipRes, btnFrame, btnQuick, thumb, btnSwitch, btnPause, btnSnap, toastView)
        for (i in 0 until zoomRow.childCount) list.add(zoomRow.getChildAt(i))
        return list
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** Short status in the middle of the top bar (empty most of the time). */
    private fun statusLine(st: RenderState?): String = when {
        st != null && st.unstable && settings.lockMode != LockMode.OFF -> "Horizon lock paused"
        settings.captureMode == CaptureMode.PHOTO && activeExtension >= 0 -> CameraController.extensionName(activeExtension)
        else -> ""
    }

    private fun sessionDescription(st: RenderState?): String {
        val sb = StringBuilder()
        sb.append("camera=${sessionInfo?.id} input=${sessionInput?.let { "${it.width}x${it.height}" }} fps=$sessionFps")
        sb.append(" mode=${settings.captureMode} lock=${settings.lockMode}")
        if (activeExtension >= 0) sb.append(" processing=${CameraController.extensionName(activeExtension)}")
        if (st != null) {
            sb.append("\n  preview=%.1ffps frame=%dx%d crop=%.2f roll=%.1f zoom=%.2f".format(st.fps, st.frameW, st.frameH, st.cropZoom, st.rollDeg, zoomRatio))
        }
        if (iso > 0 && exposureNs > 0) sb.append(" iso=$iso exposure=${exposureNs / 1000}us")
        sb.append("\n  lenses=${backLenses.joinToString { "${it.label}@${it.cameraId}/${it.zoomRatio}" }} front=${frontLens?.cameraId}")
        return sb.toString()
    }

    /** Fades a control in or out (keeps its space in the layout). */
    private fun fade(v: View, show: Boolean) {
        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
            }
            v.isEnabled = true
            v.animate().alpha(1f).setDuration(150).start()
        } else {
            v.isEnabled = false
            if (v.visibility == View.VISIBLE) {
                v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.INVISIBLE }.start()
            }
        }
    }

    private fun refreshUi() {
        val mode = settings.captureMode
        shutter.mode = mode
        for ((v, m) in listOf(modePhoto to CaptureMode.PHOTO, modeVideo to CaptureMode.VIDEO, modeTimelapse to CaptureMode.TIMELAPSE)) {
            val sel = m == mode
            v.setTextColor(if (sel) Color.WHITE else 0xB3FFFFFF.toInt())
            v.typeface = Typeface.create("sans-serif", if (sel) Typeface.BOLD else Typeface.NORMAL)
            v.setBackgroundResource(if (sel) R.drawable.bg_mode_selected else 0)
        }

        val steadyIcon = when (settings.lockMode) {
            LockMode.OFF -> R.drawable.ic_steady_off
            LockMode.LEVEL -> R.drawable.ic_steady
            LockMode.LOCK_360 -> R.drawable.ic_horizon_lock
        }
        btnSteady.setImageResource(steadyIcon)
        btnSteady.imageTintList = ColorStateList.valueOf(if (settings.lockMode != LockMode.OFF) Ui.ACCENT else Color.WHITE)
        btnSteady.contentDescription = "Stabilization: " + when (settings.lockMode) {
            LockMode.OFF -> "off"
            LockMode.LEVEL -> "steady"
            LockMode.LOCK_360 -> "steady and horizon lock"
        }
        chipRes.text = chipLabel()

        val frameIcon = when (settings.frameOrientation) {
            FrameOrientation.AUTO -> R.drawable.ic_orientation_auto
            FrameOrientation.LANDSCAPE -> R.drawable.ic_crop_landscape
            FrameOrientation.PORTRAIT -> R.drawable.ic_crop_portrait
        }
        btnFrame.setImageResource(frameIcon)
        btnFrame.imageTintList = ColorStateList.valueOf(if (settings.frameOrientation != FrameOrientation.AUTO) Ui.ACCENT else Color.WHITE)
        btnFrame.contentDescription = "Recording frame: " + settings.frameOrientation.label

        val rec = recorder.isRecording
        val hasBoth = frontLens != null && backLenses.isNotEmpty()
        fade(thumb, !rec)
        fade(btnSwitch, !rec && hasBoth)
        fade(btnPause, rec)
        fade(btnSnap, rec)
        for (v in listOf(btnSteady, chipRes, btnFrame, btnQuick, modeRow)) fade(v, !rec)
        val paused = rec && recorder.isPaused
        btnPause.setImageResource(if (paused) R.drawable.ic_record_dot else R.drawable.ic_pause)
        btnPause.imageTintList = ColorStateList.valueOf(if (paused) RED else Color.WHITE)
        btnPause.contentDescription = if (paused) "Resume recording" else "Pause recording"
        recTime.visibility = if (rec) View.VISIBLE else View.GONE
        if (!rec) recTime.alpha = 1f
        infoText.visibility = if (rec) View.GONE else View.VISIBLE
        if (isOpen(quickPanel)) refreshQuickPanel()
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
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                tag = lens
                contentDescription = "Zoom ${lens.label}"
                setOnClickListener {
                    if (recorder.isRecording && lens.cameraId != currentLens?.cameraId) return@setOnClickListener
                    tap()
                    selectLens(lens)
                }
            }
            val lp = LinearLayout.LayoutParams(px(42f), px(42f))
            lp.setMargins(px(1f), 0, px(1f), 0)
            zoomRow.addView(tv, lp)
        }
        refreshLensButtons()
    }

    /** Unselected lenses show a plain number ("0.6", "3"); the active one shows the zoom ("1×", "1.4×"). */
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
            v.setTextColor(if (sel) Ui.ACCENT else Color.WHITE)
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sel) 13f else 11.5f)
            v.text = when {
                sel && abs(zoomRatio - lens.zoomRatio) > 0.05f && lens.cameraId == cur?.cameraId -> formatZoom(zoomRatio * lens.relative / lens.zoomRatio)
                sel -> lens.label
                else -> lens.label.removeSuffix("×").let { if (it.startsWith(".")) "0$it" else it }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun maybeShowWelcome() {
        if (settings.showHints) {
            settings.showHints = false
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_body)
                .setPositiveButton(R.string.got_it, null)
                .show()
        }
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
            if (permissionPanel.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
            val action = if (keyCode == KeyEvent.KEYCODE_CAMERA) VolumeAction.SHUTTER else settings.volumeAction
            when (action) {
                VolumeAction.SHUTTER -> {
                    if (event.repeatCount == 0) onShutter()
                    return true
                }
                VolumeAction.ZOOM -> {
                    val info = camera.currentInfo ?: return true
                    val f = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.1f else 1 / 1.1f
                    zoomAnimator?.cancel()
                    setZoomNow((zoomRatio * f).coerceIn(info.minZoom, max(info.minZoom, info.maxZoom)))
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
            settings.volumeAction != VolumeAction.OFF && permissionPanel.visibility != View.VISIBLE
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val REQ_PERMS = 42
        /** Camera "review" intent (shows the capture with the gallery's swipe-through view). */
        private const val ACTION_REVIEW = "com.android.camera.action.REVIEW"
        /** Also listed under <queries> in the manifest. */
        private val KNOWN_GALLERIES = listOf("com.sec.android.gallery3d", "com.google.android.apps.photos")
        private const val RED = 0xFFF5372B.toInt()
        const val EXTRA_SKIP_REPORT = "skip_report"
    }
}
