package com.example.streaktest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusLine: TextView
    private lateinit var logView: TextView
    private lateinit var nameInput: EditText
    private lateinit var shortcutInput: EditText
    private var lastLog = ""

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("streak", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(56), dp(16), dp(24))
        }

        val title = TextView(this).apply {
            text = "Streak Test"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }

        val info = TextView(this).apply {
            text = "Flow: Snapchat Camera \u2192 Memories \u2192 black image \u2192 Send To \u2192 shortcut \u2192 friend \u2192 Send.\n" +
                "The black image must be in the first row of your Memories."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        }

        nameInput = EditText(this).apply {
            hint = "Friend name (as shown in Snapchat)"
            setText(prefs.getString("name", "Krisha"))
            setSingleLine()
        }

        shortcutInput = EditText(this).apply {
            hint = "Shortcut emoji"
            setText(prefs.getString("shortcut", "\uD83D\uDCAF"))
            setSingleLine()
        }

        statusLine = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(8))
        }

        val settingsButton = Button(this).apply {
            text = "OPEN ACCESSIBILITY SETTINGS"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val sendButton = Button(this).apply {
            text = "SEND AUTOMATED TEST"
            textSize = 18f
            setOnClickListener {
                val name = nameInput.text.toString().trim().ifEmpty { "Krisha" }
                val shortcut = shortcutInput.text.toString().trim().ifEmpty { "\uD83D\uDCAF" }
                prefs.edit().putString("name", name).putString("shortcut", shortcut).apply()
                startTest(name, shortcut)
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP"
            setOnClickListener { SnapAutomationService.instance?.stopRun("stopped by user") }
        }

        val copyButton = Button(this).apply {
            text = "COPY LOG"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("streak log", SnapAutomationService.logText()))
                Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }

        layout.addView(title)
        layout.addView(info)
        layout.addView(nameInput)
        layout.addView(shortcutInput)
        layout.addView(statusLine)
        layout.addView(settingsButton)
        layout.addView(sendButton)
        layout.addView(stopButton)
        layout.addView(copyButton)
        layout.addView(logView)

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun startTest(name: String, shortcut: String) {
        val svc = SnapAutomationService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on \"Streak Snapchat Automation\" in Accessibility settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        SnapAutomationService.clearLog()
        svc.startRun(name, shortcut)
        if (!launchSnapchat()) {
            svc.stopRun("could not launch Snapchat")
        }
    }

    private fun launchSnapchat(): Boolean {
        val pkg = SnapAutomationService.SNAP_PKG
        // getLaunchIntentForPackage needs a <queries> entry on Android 11+; the explicit-package
        // MAIN/LAUNCHER intent below works either way.
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (SnapAutomationService.FRESH_START) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.addFlags(flags)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            SnapAutomationService.log("launch failed: $e")
            false
        }
    }

    private fun refresh() {
        val svc = SnapAutomationService.instance
        statusLine.text = when {
            svc == null -> "\u26A0 Accessibility service is OFF - enable \"Streak Snapchat Automation\""
            svc.isRunning -> "\u25B6 Running..."
            else -> "\u2713 Service ready"
        }
        val t = SnapAutomationService.logText()
        if (t != lastLog) {
            lastLog = t
            logView.text = t.takeLast(7000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}