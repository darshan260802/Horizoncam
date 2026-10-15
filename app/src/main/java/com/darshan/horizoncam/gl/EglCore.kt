package com.darshan.horizoncam.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log

/** Minimal EGL14 helper (GLES 2, recordable config so the same context can feed MediaCodec). */
class EglCore {
    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY; private set
    var context: EGLContext = EGL14.EGL_NO_CONTEXT; private set
    private var config: EGLConfig? = null

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw RuntimeException("eglInitialize failed")
        config = chooseConfig(true, 8) ?: chooseConfig(true, 0) ?: chooseConfig(false, 8)
            ?: throw RuntimeException("No suitable EGLConfig")
        val attrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attrs, 0)
        checkEgl("eglCreateContext")
        if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
    }

    private fun chooseConfig(recordable: Boolean, alpha: Int): EGLConfig? {
        val list = mutableListOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT
        )
        if (recordable) {
            list.add(EGL_RECORDABLE_ANDROID); list.add(1)
        }
        list.add(EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, list.toIntArray(), 0, configs, 0, 1, num, 0)) return null
        return if (num[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        val attrs = intArrayOf(EGL14.EGL_NONE)
        val s = EGL14.eglCreateWindowSurface(display, config, surface, attrs, 0)
        checkEgl("eglCreateWindowSurface")
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        return s
    }

    fun createPbufferSurface(w: Int, h: Int): EGLSurface {
        val attrs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(display, config, attrs, 0)
        checkEgl("eglCreatePbufferSurface")
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")
        return s
    }

    fun makeCurrent(s: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) {
            throw RuntimeException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
    }

    fun swapBuffers(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)

    fun setPresentationTime(s: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, s, nsecs)
    }

    fun querySurface(s: EGLSurface, what: Int): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(display, s, what, v, 0)
        return v[0]
    }

    fun releaseSurface(s: EGLSurface) {
        EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    private fun checkEgl(msg: String) {
        val err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS) Log.w("EglCore", "$msg: EGL error 0x${Integer.toHexString(err)}")
    }

    companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
