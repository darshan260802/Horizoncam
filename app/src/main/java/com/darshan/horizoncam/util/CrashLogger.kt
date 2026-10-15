package com.darshan.horizoncam.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores the last uncaught exception so testers can copy it from the app after a crash. */
object CrashLogger {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "HorizonCam ${versionName(app)} crash at $ts\n" +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n" +
                    "Thread: ${t.name}\n\n$sw"
                File(app.filesDir, FILE).writeText(text)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun lastCrash(context: Context): String? {
        val f = File(context.filesDir, FILE)
        return if (f.exists()) f.readText() else null
    }

    /** Returns the crash text only the first time it is asked for (used for the launch dialog). */
    fun unseenCrash(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        if (prefs.getLong("seen", 0L) == f.lastModified()) return null
        prefs.edit().putLong("seen", f.lastModified()).apply()
        return f.readText()
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    fun versionName(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
