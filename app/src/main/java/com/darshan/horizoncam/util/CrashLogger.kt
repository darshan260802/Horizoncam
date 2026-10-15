package com.darshan.horizoncam.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash and start-up diagnostics for testers:
 *  - uncaught Java exceptions are written to a file and shown on the next launch,
 *  - start-up "breadcrumbs" reveal where the app died even for native/driver crashes,
 *  - non-fatal errors are kept in a short log for the diagnostics screen.
 */
object CrashLogger {
    private const val TAG = "HorizonCam"
    private const val FILE = "last_crash.txt"
    private const val STARTUP = "startup_step.txt"
    private const val NONFATAL = "nonfatal.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val step = try { File(app.filesDir, STARTUP).readText() } catch (x: Throwable) { "" }
                val text = header(app) + "Thread: ${t.name}\n" +
                    (if (step.isNotEmpty()) "Start-up step: $step\n" else "") + "\n" + stackTrace(e)
                File(app.filesDir, FILE).writeText(text)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun header(context: Context): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "HorizonCam ${versionName(context)} at $ts\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n"
    }

    fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    fun lastCrash(context: Context): String? {
        val f = File(context.filesDir, FILE)
        return if (f.exists()) f.readText() else null
    }

    /** Returns the crash text only the first time it is asked for (used for the launch screen). */
    fun unseenCrash(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        if (prefs.getLong("seen", 0L) == f.lastModified()) return null
        prefs.edit().putLong("seen", f.lastModified()).commit()
        return f.readText()
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, NONFATAL).delete()
    }

    // ---------------------------------------------------------------- start-up breadcrumbs

    /** Records the current start-up step (synchronously, so it survives a hard crash). */
    fun step(context: Context, name: String) {
        Log.i(TAG, "startup: $name")
        try { File(context.applicationContext.filesDir, STARTUP).writeText(name) } catch (e: Throwable) { }
    }

    /** Start-up finished (or the app was left normally). */
    fun startupDone(context: Context) {
        try { File(context.applicationContext.filesDir, STARTUP).delete() } catch (e: Throwable) { }
    }

    /** If the previous run died during start-up, returns its last step (and forgets it). */
    fun consumeUnfinishedStartup(context: Context): String? {
        val f = File(context.filesDir, STARTUP)
        if (!f.exists()) return null
        val s = try { f.readText() } catch (e: Throwable) { "?" }
        f.delete()
        return s
    }

    fun unfinishedStartupReport(context: Context, step: String): String =
        header(context) + "\nThe app closed during start-up without a Java exception.\n" +
            "Last start-up step: $step\n\nThis usually means a crash inside the camera or graphics driver."

    // ---------------------------------------------------------------- non-fatal log

    fun nonFatal(context: Context, where: String, e: Throwable) {
        Log.w(TAG, "non-fatal in $where", e)
        try {
            val f = File(context.applicationContext.filesDir, NONFATAL)
            val old = if (f.exists()) f.readText().takeLast(12_000) else ""
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.writeText(old + "[$ts] $where: ${stackTrace(e).take(1500)}\n")
        } catch (ignored: Throwable) {
        }
    }

    fun nonFatalLog(context: Context): String? {
        val f = File(context.filesDir, NONFATAL)
        return if (f.exists()) f.readText() else null
    }

    fun versionName(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
