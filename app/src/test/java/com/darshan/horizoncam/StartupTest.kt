package com.darshan.horizoncam

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Looper
import android.util.Range
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

/**
 * Smoke test: the camera screen starts (with and without permissions) on the JVM.
 * Camera2 and EGL are faked by Robolectric, so this checks app logic, layouts and resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class StartupTest {
    private fun fakeEgl() {
        // Real Android initialises these natively; Robolectric leaves them null.
        fun mk(cls: Class<*>): Any {
            val c = cls.getDeclaredConstructor(java.lang.Long.TYPE)
            c.isAccessible = true
            return c.newInstance(0L)
        }
        val e = android.opengl.EGL14::class.java
        for ((name, cls) in listOf(
            "EGL_NO_SURFACE" to android.opengl.EGLSurface::class.java,
            "EGL_NO_CONTEXT" to android.opengl.EGLContext::class.java,
            "EGL_NO_DISPLAY" to android.opengl.EGLDisplay::class.java
        )) {
            val f = e.getDeclaredField(name)
            f.isAccessible = true
            if (f.get(null) == null) f.set(null, mk(cls))
        }
    }

    private fun addCameras() {
        fakeEgl()
        val app = RuntimeEnvironment.getApplication()
        val cm = app.getSystemService(CameraManager::class.java)
        val back = ShadowCameraCharacteristics.newCameraCharacteristics()
        val sb = shadowOf(back)
        sb.set(CameraCharacteristics.LENS_FACING, CameraMetadata.LENS_FACING_BACK)
        sb.set(CameraCharacteristics.SENSOR_ORIENTATION, 90)
        sb.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE))
        if (android.os.Build.VERSION.SDK_INT >= 30) sb.set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, Range(0.6f, 10f))
        sb.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowOf(cm).addCamera("0", back)
        val front = ShadowCameraCharacteristics.newCameraCharacteristics()
        val sf = shadowOf(front)
        sf.set(CameraCharacteristics.LENS_FACING, CameraMetadata.LENS_FACING_FRONT)
        sf.set(CameraCharacteristics.SENSOR_ORIENTATION, 270)
        sf.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE))
        shadowOf(cm).addCamera("1", front)
    }

    @Test
    fun startsWithPermissions() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        addCameras()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(20) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle() }
        c.pause().stop().destroy()
        println("STARTUP-WITH-PERMISSIONS OK")
    }

    @Test
    fun startsWithoutPermissions() {
        addCameras()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(10) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle() }
        c.pause().stop().destroy()
        println("STARTUP-NO-PERMISSIONS OK")
    }

    /** Runs pending work and advances the clock so springs and timers finish. */
    private fun idle() = repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(80)) }

    /** Sends a drag from (x0, y0) by (dx, dy) in [steps] moves over [ms] milliseconds. */
    private fun drag(a: android.app.Activity, x0: Float, y0: Float, dx: Float, dy: Float, steps: Int = 8, ms: Long = 160) {
        val root = a.window.decorView
        val t0 = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, t: Long, x: Float, y: Float) {
            val e = android.view.MotionEvent.obtain(t0, t, action, x, y, 0)
            root.dispatchTouchEvent(e)
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, t0, x0, y0)
        for (i in 1..steps) send(android.view.MotionEvent.ACTION_MOVE, t0 + ms * i / steps, x0 + dx * i / steps, y0 + dy * i / steps)
        send(android.view.MotionEvent.ACTION_UP, t0 + ms, x0 + dx, y0 + dy)
        idle()
    }

    private fun centreOf(v: android.view.View): FloatArray {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return floatArrayOf(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    private fun findByDesc(v: android.view.View, desc: String): android.view.View? {
        if (v.contentDescription?.toString() == desc) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findByDesc(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    @Test
    fun cameraControlsWork() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        addCameras()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        idle()
        fun click(id: Int) { a.findViewById<android.view.View>(id).performClick(); idle() }
        fun clickChild(id: Int, index: Int) {
            val g = a.findViewById<android.view.ViewGroup>(id)
            val items = (0 until g.childCount).map { g.getChildAt(it) }.filter { it.isClickable }
            if (items.size > index) items[index].performClick()
            idle()
        }
        fun tapQuick(label: String) {
            val v = findByDesc(a.findViewById(R.id.quickPanel), label)
            check(v != null) { "quick control $label missing" }
            v.performClick()
            idle()
        }
        fun tapAllChips(id: Int) {
            // Every chip / pill (TextView leaves that are clickable).
            fun walk(v: android.view.View) {
                if (v is android.widget.TextView && v.isClickable) v.performClick()
                else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(a.findViewById(id))
            idle()
        }

        // Stabilization / horizon lock menu at the top.
        for (i in 0 until 3) { click(R.id.btnSteady); clickChild(R.id.steadyMenu, i) }
        // Resolution menu, frame orientation.
        click(R.id.chipRes); tapAllChips(R.id.resMenu)
        repeat(3) { click(R.id.btnFrame) }

        for (mode in listOf(R.id.modeVideo, R.id.modeTimelapse, R.id.modePhoto)) {
            click(mode)
            click(R.id.chipRes); tapAllChips(R.id.resMenu)
            click(R.id.btnQuick)
            for (label in listOf("Torch", "Sound", "Timer", "Ratio", "Brightness", "Guides", "Video size", "Speed", "Processing")) {
                val v = findByDesc(a.findViewById(R.id.quickPanel), label) ?: continue
                v.performClick(); idle()
                tapAllChips(R.id.quickPanel)
            }
            tapQuick("Torch"); tapQuick("Sound"); tapQuick("Sound")
            @Suppress("DEPRECATION") a.onBackPressed()
            idle()
        }
        click(R.id.modeVideo)

        // Settings opens its own screen.
        click(R.id.btnQuick)
        tapQuick("Settings")
        val next = shadowOf(a).nextStartedActivity
        check(next?.component?.className == SettingsActivity::class.java.name) { "settings not opened: $next" }

        click(R.id.btnSwitch); click(R.id.btnSwitch)
        c.pause().stop().destroy()
        println("CAMERA-CONTROLS OK")
    }

    @Test
    fun gesturesFollowTheFinger() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        addCameras()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        idle()
        val prefs = app.getSharedPreferences("horizoncam", 0)
        fun mode() = prefs.getString("captureMode", "VIDEO")

        // Drag the mode carousel to the left: the next mode (hyperlapse) is picked.
        val video = centreOf(a.findViewById(R.id.modeVideo))
        drag(a, video[0], video[1], -260f, 0f)
        check(mode() == "TIMELAPSE") { "carousel drag left should pick hyperlapse, got ${mode()}" }
        // And back to the right, past the start: rubber-bands and settles on the first mode.
        val lapse = centreOf(a.findViewById(R.id.modeTimelapse))
        drag(a, lapse[0], lapse[1], 900f, 0f)
        check(mode() == "PHOTO") { "carousel drag right should end on photo, got ${mode()}" }

        // Quick controls: open, then flick the sheet down to close it.
        a.findViewById<android.view.View>(R.id.btnQuick).performClick()
        idle()
        val sheet = a.findViewById<com.darshan.horizoncam.ui.QuickPanel>(R.id.quickPanel)
        check(sheet.isOpen && sheet.visibility == android.view.View.VISIBLE) { "sheet should be open" }
        val top = centreOf(sheet)
        drag(a, top[0], top[1] - sheet.height / 2f + 10f, 0f, 500f, steps = 6, ms = 90)
        check(!sheet.isOpen && sheet.visibility == android.view.View.GONE) { "flicking down should close the sheet" }

        // A small drag on an open sheet springs back instead of closing.
        a.findViewById<android.view.View>(R.id.btnQuick).performClick()
        idle()
        val top2 = centreOf(sheet)
        drag(a, top2[0], top2[1] - sheet.height / 2f + 10f, 0f, 40f, steps = 6, ms = 600)
        check(sheet.isOpen && kotlin.math.abs(sheet.translationY) < 2f) { "small drag should settle back, ty=${sheet.translationY}" }

        c.pause().stop().destroy()
        println("GESTURES OK")
    }

    @Test
    fun microphoneIsAskedWhenRecordingStarts() {
        val app = RuntimeEnvironment.getApplication()
        // Camera only: the microphone must not be asked for at launch.
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        app.getSharedPreferences("horizoncam", 0).edit().putString("captureMode", "VIDEO").commit()
        addCameras()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        idle()
        val launch = shadowOf(a).lastRequestedPermission
        check(launch == null || Manifest.permission.RECORD_AUDIO !in launch.requestedPermissions) { "microphone asked at launch" }
        // Pretend the camera is running, then press the shutter: now the microphone is asked for.
        MainActivity::class.java.getDeclaredField("cameraReady").apply { isAccessible = true }.setBoolean(a, true)
        a.findViewById<android.view.View>(R.id.shutter).performClick()
        idle()
        val req = shadowOf(a).lastRequestedPermission
        check(req != null && Manifest.permission.RECORD_AUDIO in req.requestedPermissions) { "microphone not asked when recording" }
        c.pause().stop().destroy()
        println("MIC-IN-CONTEXT OK")
    }

    @Test
    fun galleryButtonOpensGalleryApp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        addCameras()
        // A phone with the Samsung gallery installed.
        val gallery = "com.sec.android.gallery3d"
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(gallery)
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = gallery
                name = "$gallery.Gallery"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = gallery }
            }
        })
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        idle()
        val f = MainActivity::class.java.getDeclaredField("lastSavedUri")
        f.isAccessible = true
        f.set(a, android.net.Uri.parse("content://media/external/images/media/42"))
        a.findViewById<android.view.View>(R.id.thumb).performClick()
        val started = shadowOf(a).nextStartedActivity
        check(started != null && started.`package` == gallery && started.action == "com.android.camera.action.REVIEW") {
            "expected the gallery review intent, got $started"
        }
        check(started.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        c.pause().stop().destroy()
        println("GALLERY-BUTTON OK")
    }

    @Test
    fun settingsScreenWorks() {
        addCameras()
        val c = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val a = c.get()
        idle()
        // Tap every row; close any dialog that opens (after picking its first choice).
        fun rows(v: android.view.View, out: MutableList<android.view.View>) {
            if (v is android.widget.LinearLayout && v.isClickable) { out.add(v); return }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) rows(v.getChildAt(i), out)
        }
        val all = ArrayList<android.view.View>()
        rows(a.window.decorView, all)
        check(all.size > 20) { "only ${all.size} setting rows" }
        for (r in all) {
            r.performClick()
            idle()
            val d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            if (d != null && d.isShowing) {
                d.listView?.let { lv -> if (lv.adapter != null && lv.adapter.count > 0) lv.performItemClick(lv.getChildAt(0), 0, 0) }
                if (d.isShowing) d.dismiss()
                idle()
            }
        }
        c.pause().stop().destroy()
        println("SETTINGS-SCREEN OK (${all.size} rows)")
    }
}
