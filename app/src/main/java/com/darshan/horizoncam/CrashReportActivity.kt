package com.darshan.horizoncam

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.darshan.horizoncam.util.CrashLogger

/**
 * Shown instead of the camera after a crash so testers can copy the report.
 * Deliberately uses only framework views and theme: it must work even when
 * the camera screen itself is what keeps crashing.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT) ?: CrashLogger.lastCrash(this) ?: "No crash report found."
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "HorizonCam stopped last time"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Please tap Copy and send this report to the developer."
            setTextColor(0xFFB0B8C4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = report
            setTextColor(0xFFE6E6E6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        button("Copy") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("HorizonCam crash", report))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        button("Share") {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "HorizonCam crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }, "Share crash report"))
            } catch (e: Exception) {
                Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show()
            }
        }
        button("Open camera") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SKIP_REPORT, true))
            finish()
        }
        root.addView(buttons)
        setContentView(root)
    }

    companion object {
        const val EXTRA_REPORT = "report"
    }
}
