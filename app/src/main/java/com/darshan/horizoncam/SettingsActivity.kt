package com.darshan.horizoncam

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.darshan.horizoncam.camera.CameraController
import com.darshan.horizoncam.camera.CameraInfo
import com.darshan.horizoncam.core.AspectRatio
import com.darshan.horizoncam.core.FrameOrientation
import com.darshan.horizoncam.core.LockMode
import com.darshan.horizoncam.core.PreviewStyle
import com.darshan.horizoncam.core.Settings
import com.darshan.horizoncam.core.VideoQuality
import com.darshan.horizoncam.core.VolumeAction
import com.darshan.horizoncam.media.EncoderSupport
import com.darshan.horizoncam.media.MediaSaver
import com.darshan.horizoncam.media.SoundPolicy
import com.darshan.horizoncam.sensor.HorizonSensor
import com.darshan.horizoncam.ui.Ui
import com.darshan.horizoncam.util.CrashLogger

/** Last camera session description, set by the camera screen for diagnostics. */
object SessionInfo {
    @Volatile var text: String = "Camera not opened yet"
}

/**
 * Camera settings: large title, rounded groups, switches on the right and native choice dialogs.
 * Follows the system light or dark theme (colour tokens in values/ and values-night/), uses the
 * same amber accent as the camera for "on", and gives TalkBack proper switch roles and states.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var list: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun c(id: Int) = getColor(id)
    private val textColor by lazy { c(R.color.settings_text) }
    private val secondaryColor by lazy { c(R.color.settings_secondary) }

    private fun rowRipple() = android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(c(R.color.settings_ripple)), null, Ui.rounded(Color.WHITE, 0f))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false) else layoutBehindBarsLegacy()

        val root = FrameLayout(this).apply { setBackgroundColor(c(R.color.settings_bg)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Small pinned bar that appears once the big title scrolls away.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(c(R.color.settings_bg))
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(textColor)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(c(R.color.settings_ripple)), null,
                Ui.rounded(Color.WHITE, dp(24f).toFloat()))
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        val smallTitle = TextView(this).apply {
            text = "Camera settings"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bar.addView(smallTitle)
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // Large expanded title (One UI style), about a third of the screen.
        val headerH = (resources.displayMetrics.heightPixels * 0.30f).toInt()
        val header = FrameLayout(this)
        header.addView(TextView(this).apply {
            text = "Camera settings"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            // Regular weight with slightly tighter tracking for a large title (light weights read poorly).
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = -0.01f
            gravity = Gravity.CENTER
            setPadding(dp(24f), 0, dp(24f), 0)
            isFocusable = true
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        list.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerH))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            // The small title takes over as the large one scrolls away (continuous, tied to the scroll).
            smallTitle.alpha = ((y - headerH * 0.55f) / (headerH * 0.3f)).coerceIn(0f, 1f)
            header.alpha = 1f - ((y - headerH * 0.2f) / (headerH * 0.5f)).coerceIn(0f, 1f)
        }

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top else insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
            bar.setPadding(0, top, 0, 0)
            list.setPadding(0, top, 0, bottom + dp(24f))
            insets
        }
        setContentView(root)
        buildContent()
    }

    @Suppress("DEPRECATION")
    private fun layoutBehindBarsLegacy() {
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // ------------------------------------------------------------------ content

    private fun buildContent() {
        val processing = processingOptions()

        section("Pictures")
        card {
            choice("Picture ratio", listOf("3:4", "9:16", "1:1"),
                { when (settings.photoAspect) { AspectRatio.R4_3 -> 0; AspectRatio.R16_9 -> 1; AspectRatio.R1_1 -> 2 } },
                { settings.photoAspect = listOf(AspectRatio.R4_3, AspectRatio.R16_9, AspectRatio.R1_1)[it] })
            choice("Picture processing", processing.map { it.second },
                { processing.indexOfFirst { p -> p.first == settings.photoProcessing }.coerceAtLeast(0) },
                { settings.photoProcessing = processing[it].first },
                summaryHint = if (processing.size <= 1) "Your phone doesn't offer HDR or Night processing to other apps" else "Uses your phone's own HDR / Night processing")
        }

        section("Videos")
        card {
            choice("Video size", VideoQuality.values().map { qualityLabel(it) }, { settings.videoQuality.ordinal }, { settings.videoQuality = VideoQuality.values()[it] })
            choice("Frame rate", listOf("30 fps", "60 fps"), { if (settings.fps == 60) 1 else 0 }, { settings.fps = if (it == 1) 60 else 30 })
            choice("Video ratio", listOf("9:16", "3:4", "1:1"),
                { when (settings.videoAspect) { AspectRatio.R16_9 -> 0; AspectRatio.R4_3 -> 1; AspectRatio.R1_1 -> 2 } },
                { settings.videoAspect = listOf(AspectRatio.R16_9, AspectRatio.R4_3, AspectRatio.R1_1)[it] })
            choice("Video codec", listOf("H.264 (most compatible)", "HEVC (smaller files)"), { if (settings.hevc) 1 else 0 }, { settings.hevc = it == 1 })
            switch("High bitrate", "More detail in busy scenes, bigger files", { settings.highBitrate }, { settings.highBitrate = it })
            switch("Video stabilization", "Uses the camera's stabilization as well. Turn off for the sharpest image.", { settings.cameraStabilization }, { settings.cameraStabilization = it })
            switch("Record audio", null, { settings.recordAudio }, { settings.recordAudio = it })
            choice("Hyperlapse speed", HYPER_SPEEDS.map { "${it}x" }, { HYPER_SPEEDS.indexOf(settings.timelapseSpeed).coerceAtLeast(0) }, { settings.timelapseSpeed = HYPER_SPEEDS[it] })
        }

        section("Horizon lock")
        card {
            choice("Stabilization mode", listOf("Off", "Steady", "Steady + horizon lock"),
                { when (settings.lockMode) { LockMode.OFF -> 0; LockMode.LEVEL -> 1; LockMode.LOCK_360 -> 2 } },
                { settings.lockMode = listOf(LockMode.OFF, LockMode.LEVEL, LockMode.LOCK_360)[it] })
            choice("Steady leveling", listOf("Up to ±15°", "Up to ±30°", "Up to ±45°"),
                { listOf(15, 30, 45).indexOf(settings.levelRange).coerceAtLeast(0) }, { settings.levelRange = listOf(15, 30, 45)[it] },
                enabled = { settings.lockMode == LockMode.LEVEL })
            choice("Recording frame", listOf("Auto (follow phone)", "Always landscape", "Always portrait"),
                { settings.frameOrientation.ordinal }, { settings.frameOrientation = FrameOrientation.values()[it] })
            choice("Lock response", listOf("Auto", "Rigid", "Smooth"), { settings.response }, { settings.response = it })
            switch("Use ultra-wide for horizon lock", "Wider view, less detail", { settings.preferUltraWide }, { settings.preferUltraWide = it })
            choice("Viewfinder", listOf("Show recorded frame", "Gimbal view (full sensor + frame)"),
                { if (settings.previewStyle == PreviewStyle.OUTPUT) 0 else 1 }, { settings.previewStyle = if (it == 0) PreviewStyle.OUTPUT else PreviewStyle.GIMBAL })
        }

        section("Image")
        card {
            choice("Sharpness", listOf("Off", "Natural", "Crisp"), { settings.sharpness }, { settings.sharpness = it })
        }

        section("General")
        card {
            if (SoundPolicy.forced()) {
                switch("Shutter sound", "Required by law in your region", { true }, { }, enabled = { false })
            } else {
                switch("Shutter sound", "Also the timer and recording sounds. Always muted when the phone is on silent or vibrate.",
                    { settings.shutterSound }, { settings.shutterSound = it })
            }
            switch("Vibration feedback", null, { settings.haptics }, { settings.haptics = it })
            choice("Volume keys", listOf("Take picture or record video", "Zoom", "System volume"),
                { when (settings.volumeAction) { VolumeAction.SHUTTER -> 0; VolumeAction.ZOOM -> 1; VolumeAction.OFF -> 2 } },
                { settings.volumeAction = listOf(VolumeAction.SHUTTER, VolumeAction.ZOOM, VolumeAction.OFF)[it] })
            switch("Grid lines", null, { settings.grid }, { settings.grid = it })
            switch("Level", "Shows a spirit level in the viewfinder", { settings.levelIndicator }, { settings.levelIndicator = it })
            switch("Save selfies as previewed", null, { settings.mirrorFront }, { settings.mirrorFront = it })
        }

        section("Storage")
        card {
            switch("Save to a separate folder", null, { settings.separateFolder }, { settings.separateFolder = it },
                summary = {
                    if (settings.separateFolder) "New pictures and videos go to DCIM/${MediaSaver.OWN_FOLDER}"
                    else "Off: saved to DCIM/${MediaSaver.CAMERA_FOLDER} with your other camera pictures"
                })
        }

        section("About")
        card {
            action("How to use HorizonCam", "Gestures and tips") { showText(getString(R.string.welcome_title), getString(R.string.welcome_body), null) }
            action("Camera diagnostics", "Details to copy into a bug report") { showText("Camera diagnostics", diagnostics(), "diagnostics") }
            action("Last crash report", if (CrashLogger.lastCrash(this@SettingsActivity) != null) "A crash was recorded" else "No crashes recorded") {
                val c = CrashLogger.lastCrash(this@SettingsActivity)
                if (c != null) showText("Last crash report", c, "crash") else toast("No crashes recorded")
            }
            action("About HorizonCam", "Version ${CrashLogger.versionName(this@SettingsActivity)}") {}
        }
    }

    private fun qualityLabel(q: VideoQuality) = when (q) {
        VideoQuality.HD -> "HD 720p"
        VideoQuality.FHD -> "FHD 1080p"
        VideoQuality.QHD -> "QHD 1440p"
        VideoQuality.UHD -> "UHD 4K"
    }

    /** (extension id or -1, label) for the main back camera. */
    private fun processingOptions(): List<Pair<Int, String>> {
        val out = mutableListOf(-1 to "Standard")
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val back = CameraInfo.loadAll(manager).firstOrNull { !it.isFront } ?: return out
            val exts = CameraController.supportedExtensions(manager, back.id)
            for (e in listOf(0, 3, 4)) if (exts.contains(e)) out.add(e to CameraController.extensionName(e))
        } catch (e: Exception) { }
        return out
    }

    // ------------------------------------------------------------------ building blocks

    private var currentCard: LinearLayout? = null

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title
            setTextColor(secondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(30f), dp(24f), dp(30f), dp(8f))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
    }

    private fun card(build: () -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(c(R.color.settings_card), dp(26f).toFloat())
            clipToOutline = true
        }
        currentCard = c
        build()
        list.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12f); rightMargin = dp(12f)
        })
        currentCard = null
    }

    private fun addRow(row: View) {
        val c = currentCard ?: return
        if (c.childCount > 0) {
            c.addView(View(this).apply { setBackgroundColor(c(R.color.settings_divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(22f); rightMargin = dp(22f) })
        }
        c.addView(row)
    }

    private fun rowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64f)
        setPadding(dp(22f), dp(14f), dp(18f), dp(14f))
        isClickable = true
        isFocusable = true
        background = rowRipple()
    }

    private fun texts(title: String, summary: TextView?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            text = title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        if (summary != null) addView(summary)
    }

    /** Values and explanations share the secondary colour; the accent only means "on". */
    @Suppress("UNUSED_PARAMETER")
    private fun summaryView(value: Boolean) = TextView(this).apply {
        setTextColor(secondaryColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(2f), 0, 0)
        setLineSpacing(0f, 1.1f)
    }

    private fun choice(
        title: String,
        options: List<String>,
        get: () -> Int,
        set: (Int) -> Unit,
        enabled: () -> Boolean = { true },
        summaryHint: String? = null
    ) {
        val row = rowBase()
        val summary = summaryView(true)
        val col = texts(title, summary)
        if (summaryHint != null) col.addView(summaryView(false).apply { text = summaryHint; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val refresh = {
            summary.text = options.getOrElse(get()) { options.first() }
            row.alpha = if (enabled()) 1f else 0.4f
        }
        refreshers.add(refresh)
        refresh()
        row.setOnClickListener {
            if (!enabled() || options.size <= 1) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), get()) { d, which ->
                    set(which)
                    refreshAll()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        addRow(row)
    }

    private fun switch(
        title: String,
        summaryText: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        enabled: () -> Boolean = { true },
        summary: (() -> String)? = null
    ) {
        val row = rowBase()
        val summaryView = (summaryText ?: summary?.invoke())?.let { summaryView(false).apply { text = it } }
        row.addView(texts(title, summaryView), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = get()
            // Solid track and thumb: the stock track is translucent and the state is hard to see on
            // a light card. Off track vs card is 3.3:1 (light) / 3.8:1 (dark).
            trackDrawable = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setSize(dp(44f), dp(24f))
                setColor(Color.WHITE)
            }
            // The thumb carries a ring in the track colour, so it reads as sitting inside the track.
            fun knob(fill: Int, ring: Int) = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(dp(24f), dp(24f))
                setColor(fill)
                setStroke(dp(3f), ring)
            }
            thumbDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), knob(c(R.color.settings_switch_on_thumb), c(R.color.settings_accent)))
                addState(intArrayOf(), knob(c(R.color.settings_switch_off_thumb), c(R.color.settings_switch_off_track)))
            }
            showText = false
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(states, intArrayOf(c(R.color.settings_accent), c(R.color.settings_switch_off_track)))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(sw)
        // TalkBack: announce the row as a switch with its on/off state.
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = get()
                info.isEnabled = enabled()
            }
        }
        val refresh = {
            sw.isChecked = get()
            row.alpha = if (enabled()) 1f else 0.4f
            if (summary != null) summaryView?.text = summary()
        }
        refreshers.add(refresh)
        refresh()
        row.setOnClickListener {
            if (!enabled()) return@setOnClickListener
            set(!get())
            refreshAll()
            row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
        addRow(row)
    }

    private fun action(title: String, summaryText: String?, onClick: () -> Unit) {
        val row = rowBase()
        val summary = summaryText?.let { summaryView(false).apply { text = it } }
        row.addView(texts(title, summary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { onClick() }
        addRow(row)
    }

    private fun refreshAll() = refreshers.forEach { it() }

    // ------------------------------------------------------------------ dialogs

    private fun showText(title: String, text: String, copyLabel: String?) {
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text.take(6000))
            .setNegativeButton("Close", null)
        if (copyLabel != null) {
            b.setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                toast("Copied")
            }
        }
        if (copyLabel == "crash") b.setNeutralButton("Clear") { _, _ -> CrashLogger.clear(this) }
        b.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun diagnostics(): String {
        val sb = StringBuilder()
        sb.append("HorizonCam ${CrashLogger.versionName(this)}\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Motion sensor: ${HorizonSensor(this).sensorName}\n")
        sb.append("Last session: ${SessionInfo.text}\n")
        sb.append("Encoders: AVC=${EncoderSupport.hasEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)} HEVC=${EncoderSupport.hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)}\n\n")
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (c in CameraInfo.loadAll(manager)) {
                sb.append(c.describe())
                val exts = CameraController.supportedExtensions(manager, c.id)
                sb.append("  extensions=${if (exts.isEmpty()) "none" else exts.joinToString { CameraController.extensionName(it) }}\n\n")
            }
        } catch (e: Exception) {
            sb.append("Camera info unavailable: ${e.message}\n")
        }
        CrashLogger.nonFatalLog(this)?.let { sb.append("Recent errors:\n").append(it.takeLast(4000)) }
        return sb.toString()
    }

    companion object {
        val HYPER_SPEEDS = listOf(2, 5, 10, 15, 30, 60)
    }
}
