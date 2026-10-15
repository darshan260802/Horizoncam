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
}
