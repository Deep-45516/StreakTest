package com.example.streaktest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * State-based Snapchat automation.
 *
 * Flow (each step waits for the NEXT screen to be positively recognised, with timeouts):
 *
 *   LAUNCH    Snapchat comes to the foreground (fresh start -> Camera)
 *   CAMERA    tap the Memories button
 *   MEMORIES  find the black tile in the first row of the Snaps grid, tap it
 *   IMAGE     confirm the opened image is black, press "Send To"
 *   SHORTCUT  wait until the recipient screen is fully rendered, select the shortcut emoji
 *   PICK      find the target name in the filtered list (never anyone else), select it
 *   SEND      confirm only that person is selected, press the blue Send arrow
 *
 * The service is IDLE unless MainActivity calls startRun(). It never acts on its own.
 * Every decision is written to an on-screen log (see MainActivity) so a failed run can be diagnosed
 * without adb.
 */
class SnapAutomationService : AccessibilityService() {

    companion object {
        const val SNAP_PKG = "com.snapchat.android"
        private const val TAG = "StreakAuto"

        /** Restart Snapchat from its launcher activity so every run starts on the Camera screen. */
        const val FRESH_START = true

        // Fallback positions (fractions of the screen), measured from the screenshots.
        private const val MEMORIES_BTN_X = 0.278f
        private const val MEMORIES_BTN_Y = 0.781f
        private const val CHIP_X = 0.315f      // the 100 emoji chip
        private const val CHIP_Y = 0.130f      // chip row centre (old value 0.115 was on the pill's top edge)
        private const val EDITOR_SEND_X = 0.813f
        private const val EDITOR_SEND_Y = 0.956f
        private const val FINAL_SEND_X = 0.928f
        private const val FINAL_SEND_Y = 0.965f

        private const val TICK_MS = 250L
        private const val TOTAL_TIMEOUT_MS = 120_000L
        private const val LAUNCH_TIMEOUT_MS = 25_000L

        private val SNAPS_HEADER = Regex("^[\\d.,]+ snaps?$")

        @Volatile
        var instance: SnapAutomationService? = null
            private set

        const val BUILD = "v3"

        private val logLock = Any()
        private val logLines = ArrayList<String>()
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        private var logFile: File? = null

        /**
         * The log is also written to a file, so it survives Android killing the app's process
         * (aggressive on Vivo/Funtouch). A "[process started]" line in the middle of a run = it was killed.
         */
        fun setup(ctx: Context) {
            synchronized(logLock) {
                if (logFile != null) return
                val f = File(ctx.filesDir, "streak.log")
                logFile = f
                try {
                    if (f.exists()) logLines.addAll(f.readLines().takeLast(250))
                } catch (_: Throwable) {
                }
            }
            log("[process started, pid=${android.os.Process.myPid()}, build $BUILD]")
        }

        fun log(msg: String) {
            Log.d(TAG, msg)
            val line = timeFmt.format(Date()) + " " + msg
            synchronized(logLock) {
                logLines.add(line)
                while (logLines.size > 400) logLines.removeAt(0)
                try {
                    logFile?.let { f ->
                        if (f.length() > 200_000) f.writeText(logLines.joinToString("\n") + "\n") else f.appendText(line + "\n")
                    }
                } catch (_: Throwable) {
                }
            }
        }

        fun logText(): String = synchronized(logLock) { logLines.joinToString("\n") }

        fun clearLog() {
            synchronized(logLock) {
                logLines.clear()
                try {
                    logFile?.writeText("")
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------------------------------

    private enum class St { IDLE, LAUNCH, CAMERA, MEMORIES, IMAGE, SHORTCUT, PICK, SEND }

    private enum class Kind { UNKNOWN, MEMORIES, EDITOR, LIST_ALL, LIST_FILTERED, LIST_SELECTED }

    /** One visible accessibility node with normalised (lower-case, emoji-selector-free) labels. */
    private class N(
        val node: AccessibilityNodeInfo,
        val text: String,
        val desc: String,
        val hint: String,
        val id: String,
        val b: Rect,
        val clickable: Boolean,
        val editable: Boolean
    ) {
        val label: String get() = if (text.isNotEmpty()) text else desc
        fun has(s: String) = text.contains(s) || desc.contains(s) || hint.contains(s)
        fun eq(s: String) = text == s || desc == s
    }

    private class Scr(val nodes: List<N>, val w: Int, val h: Int) {
        fun cy(n: N) = n.b.centerY().toFloat() / h
        fun cx(n: N) = n.b.centerX().toFloat() / w
        fun hasExact(s: String) = nodes.any { it.eq(s) }
        fun hasContains(s: String) = nodes.any { it.has(s) }
        fun find(p: (N) -> Boolean): N? = nodes.firstOrNull(p)
    }

    private class Ctx(val now: Long, val stable: Long, val scr: Scr, val kind: Kind)

    // ------------------------------------------------------------------------------------------
    // Run state
    // ------------------------------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private var st = St.IDLE
    private var runId = 0
    private var runStart = 0L
    private var stSince = 0L
    private var lastActAt = 0L
    private var lastSnapSeen = 0L
    private var attempts = 0
    private var backJumps = 0
    private var sigHash = 0
    private var sigSince = 0L
    private var asyncBusy = false
    private var imageChecked = false
    private var imageIsBlack = false
    private val triedTiles = HashSet<Int>()
    private var targetName = "krisha"
    private var shortcutEmoji = "\uD83D\uDCAF" // 💯
    private var lastScr: Scr? = null
    private var lastKindLogged: Kind? = null
    private var lastBeat = 0L
    private var dumpedInState = false
    private var toast: Toast? = null
    private var bannerView: TextView? = null
    private var lastBannerAt = 0L
    private val hideBannerRunnable = Runnable { removeBanner() }

    val isRunning: Boolean get() = st != St.IDLE

    // ------------------------------------------------------------------------------------------
    // Public control (called from MainActivity on the main thread)
    // ------------------------------------------------------------------------------------------

    fun startRun(recipient: String, shortcut: String) {
        runId++
        targetName = norm(recipient)
        shortcutEmoji = shortcut.trim()
        val now = SystemClock.uptimeMillis()
        runStart = now
        lastSnapSeen = now
        sigHash = 0
        sigSince = now
        asyncBusy = false
        backJumps = 0
        triedTiles.clear()
        imageChecked = false
        imageIsBlack = false
        lastScr = null
        lastKindLogged = null
        lastBeat = 0L
        log("=== RUN START ($BUILD)  recipient='$recipient'  shortcut='$shortcut' ===")
        banner("STREAK $BUILD: run started", 0xD9000000.toInt(), 0L)
        goTo(St.LAUNCH, "run started")
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stopRun(reason: String) {
        if (st != St.IDLE) {
            log("STOPPED: $reason")
            banner("STREAK stopped: $reason", 0xEE455A64.toInt(), 5000L)
        }
        st = St.IDLE
        runId++
        asyncBusy = false
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------------------------------
    // Main loop
    // ------------------------------------------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            if (st == St.IDLE) return
            try {
                tick()
            } catch (t: Throwable) {
                fail("internal error: $t")
                return
            }
            if (st != St.IDLE) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - runStart > TOTAL_TIMEOUT_MS) {
            fail("overall timeout (${TOTAL_TIMEOUT_MS / 1000}s)")
            return
        }
        if (asyncBusy) return

        val root = snapRoot()
        if (root == null) {
            val pkg = rootInActiveWindow?.packageName?.toString() ?: "none"
            if (now - lastBeat >= 3000) {
                lastBeat = now
                log("waiting for Snapchat window (foreground app: $pkg, state $st)")
            }
            if (now - lastBannerAt >= 700) {
                lastBannerAt = now
                banner("STREAK waiting for Snapchat window (foreground app: $pkg)", 0xD9000000.toInt(), 0L)
            }
            if (st == St.LAUNCH) {
                if (now - stSince > LAUNCH_TIMEOUT_MS) {
                    fail("the accessibility service cannot see Snapchat's window (foreground app: $pkg)")
                }
            } else if (now - lastSnapSeen > 8000) {
                fail("Snapchat left the foreground (now: $pkg)")
            }
            return
        }
        lastSnapSeen = now
        if (st == St.LAUNCH) goTo(St.CAMERA, "Snapchat is in the foreground")

        val scr = readScreen(root)
        lastScr = scr

        // Screen-stability: the accessibility tree must stop changing before we act on it.
        val sig = signature(scr)
        if (sig != sigHash) {
            sigHash = sig
            sigSince = now
        }
        val kind = classify(scr)
        if (kind != lastKindLogged) {
            lastKindLogged = kind
            log("screen: $kind (${scr.nodes.size} nodes)")
        }
        fastForward(kind, now)

        val c = Ctx(now, now - sigSince, scr, kind)
        if (now - lastBeat >= 3000) {
            lastBeat = now
            log("state=$st screen=$kind stable=${c.stable}ms nodes=${scr.nodes.size}")
        }
        if (now - lastBannerAt >= 700) {
            lastBannerAt = now
            banner("STREAK [${stepLabel(st)}]  screen=$kind  nodes=${scr.nodes.size}  steady=${c.stable}ms  try=$attempts", 0xD9000000.toInt(), 0L)
        }
        if (!dumpedInState && now - stSince > 6000) {
            dumpedInState = true
            dump(scr)
        }
        when (st) {
            St.CAMERA -> stepCamera(c)
            St.MEMORIES -> stepMemories(c)
            St.IMAGE -> stepImage(c)
            St.SHORTCUT -> stepShortcut(c)
            St.PICK -> stepPick(c)
            St.SEND -> stepSend(c)
            else -> Unit
        }
    }

    /** Acts only when the tree has been unchanged for [need] ms (or the state has waited long enough anyway). */
    private fun ready(c: Ctx, need: Long) = c.stable >= need || c.now - stSince >= need + 3500L

    private fun goTo(next: St, why: String) {
        log("-> $next ($why)")
        showToast("Streak: ${stepLabel(next)}", false)
        banner("STREAK: ${stepLabel(next)}", 0xD9000000.toInt(), 0L)
        st = next
        dumpedInState = false
        stSince = SystemClock.uptimeMillis()
        lastActAt = 0L
        attempts = 0
    }

    /** If the screen on display is further along the flow than our state, catch up. Limited moves backward. */
    private fun fastForward(kind: Kind, now: Long) {
        val target = when (kind) {
            Kind.MEMORIES -> St.MEMORIES
            Kind.EDITOR -> St.IMAGE
            Kind.LIST_ALL -> St.SHORTCUT
            Kind.LIST_FILTERED -> St.PICK
            Kind.LIST_SELECTED -> St.SEND
            Kind.UNKNOWN -> null
        }
        if (target != null && target.ordinal > st.ordinal) {
            goTo(target, "screen is $kind")
            return
        }
        if (backJumps < 4 && now - stSince > 2500) {
            val back = when {
                st == St.PICK && kind == Kind.LIST_ALL -> St.SHORTCUT
                st == St.SEND && kind == Kind.LIST_FILTERED -> St.PICK
                st == St.SEND && kind == Kind.LIST_ALL -> St.SHORTCUT
                st == St.IMAGE && kind == Kind.MEMORIES -> St.MEMORIES
                else -> null
            }
            if (back != null) {
                backJumps++
                goTo(back, "screen went back to $kind")
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------------------------------

    private fun stepCamera(c: Ctx) {
        if (c.kind != Kind.UNKNOWN) return
        // Give Snapchat ~2s to finish landing on the Camera screen before the first action.
        if (c.now - stSince < 2000) return
        if (c.now - lastActAt < 2200) return
        if (attempts >= 8) {
            fail("could not open Memories after 8 tries (swipe-up and icon tap both failed)")
            return
        }
        attempts++
        lastActAt = c.now
        val btn = findMemoriesButton(c.scr)
        // Alternate strategies every attempt: swipe up (Snapchat's built-in gesture) and a direct tap on
        // the small Memories/Camera-Roll thumbnail near the bottom-left of the shutter button.
        val useSwipe = attempts % 2 == 1
        if (useSwipe) {
            log("Camera: swiping up to Memories (attempt $attempts)")
            showToast("Streak: swipe up to Memories ($attempts)", false)
            swipeUp(c.scr)
        } else if (btn != null) {
            log("Camera: tapping Memories button found by accessibility (attempt $attempts)")
            showToast("Streak: tap Memories icon ($attempts)", false)
            activate(btn, c.scr, attempts < 4)
        } else {
            log("Camera: tapping Memories icon at fallback position (attempt $attempts)")
            showToast("Streak: tap Memories icon ($attempts)", false)
            tapFrac(c.scr, MEMORIES_BTN_X, MEMORIES_BTN_Y)
        }
    }

    private fun stepMemories(c: Ctx) {
        if (c.kind != Kind.MEMORIES) {
            // We tapped a tile and Snapchat left the grid without showing a screen we recognise
            // (e.g. a Memories viewer). Let the IMAGE step look for a Send button there.
            if (attempts > 0 && c.kind == Kind.UNKNOWN && c.now - lastActAt > 1500) {
                goTo(St.IMAGE, "left Memories after tapping a tile")
            }
            return
        }
        if (!ready(c, 900) || c.now - stSince < 900 || c.now - lastActAt < 3000) return
        if (attempts >= 4) {
            fail("could not open the black image from Memories")
            return
        }
        attempts++
        lastActAt = c.now
        chooseAndTapTile(c.scr)
    }

    private fun stepImage(c: Ctx) {
        if (c.kind == Kind.MEMORIES) return
        if (!ready(c, 1000) || c.now - stSince < 1200) return

        // Safety: make sure the image we opened really is the black one BEFORE going to Send To.
        if (!imageChecked) {
            imageChecked = true
            verifyBlack(c.scr) { res ->
                if (res == false) {
                    fail("the opened image is NOT black - aborted, nothing was sent")
                } else {
                    imageIsBlack = res == true
                    log(if (res == true) "image check: black OK" else "image check: could not verify (screenshot unavailable)")
                }
            }
            return
        }

        if (c.now - lastActAt < 2500) return
        if (attempts >= 4) {
            fail("could not press Send on the image screen")
            return
        }
        attempts++
        lastActAt = c.now
        val btn = findEditorSend(c.scr)
        log("Image: pressing Send To (attempt $attempts, button found=${btn != null})")
        showToast("Streak: pressing Send To ($attempts)", false)
        when {
            btn != null -> activate(btn, c.scr, attempts >= 2)
            attempts >= 2 && imageIsBlack -> tapFrac(c.scr, EDITOR_SEND_X, EDITOR_SEND_Y)
            else -> log("Image: no Send button visible yet")
        }
    }

    private fun stepShortcut(c: Ctx) {
        if (c.kind != Kind.LIST_ALL) return
        // "Wait until fully rendered": screen recognised AND tree stable AND a minimum delay.
        if (!ready(c, 1200) || c.now - stSince < 1200 || c.now - lastActAt < 2500) return
        if (attempts >= 5) {
            fail("could not select the $shortcutEmoji shortcut")
            return
        }
        attempts++
        lastActAt = c.now
        val chip = findChip(c.scr)
        log("Send To: selecting $shortcutEmoji (attempt $attempts, chip found by accessibility=${chip != null})")
        showToast("Streak: selecting $shortcutEmoji ($attempts)", false)
        when (attempts) {
            1 -> if (chip != null) activate(chip, c.scr, true) else tapChipCoord(c.scr)
            2 -> if (chip != null) activate(chip, c.scr, false) else tapChipCoord(c.scr)
            3 -> tapChipCoord(c.scr)
            else -> setSearchText(c.scr, shortcutEmoji)
        }
    }

    private fun stepPick(c: Ctx) {
        if (c.kind != Kind.LIST_FILTERED) return
        if (!ready(c, 600) || c.now - stSince < 700 || c.now - lastActAt < 2000) return
        val row = findRecipientRow(c.scr)
        if (row == null) {
            // Never guess. If the name is not visible, stop.
            if (c.now - stSince > 9000) fail("'$targetName' not found in the shortcut list - not selecting anyone else")
            return
        }
        if (attempts >= 3) {
            fail("tapping '$targetName' did not select it")
            return
        }
        attempts++
        lastActAt = c.now
        log("Pick: selecting \"${row.label}\" (attempt $attempts)")
        showToast("Streak: selecting ${row.label} ($attempts)", false)
        activate(row, c.scr, attempts >= 2)
    }

    private fun stepSend(c: Ctx) {
        if (c.kind != Kind.LIST_SELECTED) {
            // After we pressed Send, Snapchat leaves the recipient screen -> success.
            if (attempts > 0 && c.kind != Kind.LIST_ALL && c.kind != Kind.LIST_FILTERED && c.now - lastActAt > 1500) {
                succeed()
            }
            return
        }
        if (!ready(c, 500) || c.now - stSince < 700 || c.now - lastActAt < 2500) return

        // Safety: the blue bar lists who will receive the Snap. Only the intended person, nobody else.
        val s = c.scr
        val barText = s.nodes
            .filter { s.cy(it) > 0.90f && s.cx(it) < 0.85f && it.label.isNotEmpty() && !it.has("send") }
            .joinToString(" ") { it.label }
        if (barText.isNotEmpty() && (!barText.contains(targetName) || barText.contains(","))) {
            fail("unexpected recipients selected ('$barText') - not sending")
            return
        }
        if (attempts >= 3) {
            fail("Send did not go through")
            return
        }
        attempts++
        lastActAt = c.now
        val btn = findFinalSend(s)
        log("Send: pressing the blue arrow (attempt $attempts, found by accessibility=${btn != null}, bar='$barText')")
        showToast("Streak: pressing Send ($attempts)", false)
        if (btn != null && attempts <= 2) activate(btn, s, attempts == 2) else tapFrac(s, FINAL_SEND_X, FINAL_SEND_Y)
    }

    private fun succeed() {
        log("DONE - Snap sent to '$targetName'")
        showToast("Streak: DONE - snap sent", true)
        banner("STREAK DONE - snap sent", 0xEE2E7D32.toInt(), 8000L)
        st = St.IDLE
        handler.removeCallbacks(ticker)
    }

    private fun fail(reason: String) {
        log("STOPPED at $st: $reason")
        showToast("Streak STOPPED at ${stepLabel(st)}: $reason", true)
        banner("STREAK STOPPED at ${stepLabel(st)}: $reason", 0xEED32F2F.toInt(), 60_000L)
        lastScr?.let { dump(it) }
        st = St.IDLE
        runId++
        asyncBusy = false
        handler.removeCallbacks(ticker)
    }

    /**
     * Status bar drawn over Snapchat (accessibility overlay: no extra permission, not touchable, so taps
     * pass straight through). This is the "can't miss it" error display.
     */
    private fun banner(msg: String, bg: Int, hideAfterMs: Long) {
        try {
            handler.removeCallbacks(hideBannerRunnable)
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            var v = bannerView
            if (v == null) {
                val dm = resources.displayMetrics
                val tv = TextView(this)
                tv.setTextColor(Color.WHITE)
                tv.textSize = 12f
                tv.setPadding((10 * dm.density).toInt(), (6 * dm.density).toInt(), (10 * dm.density).toInt(), (6 * dm.density).toInt())
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP
                lp.y = (36 * dm.density).toInt()
                wm.addView(tv, lp)
                bannerView = tv
                v = tv
            }
            v.setBackgroundColor(bg)
            v.text = msg
            if (hideAfterMs > 0) handler.postDelayed(hideBannerRunnable, hideAfterMs)
        } catch (t: Throwable) {
            Log.d(TAG, "banner failed: $t")
        }
    }

    private fun removeBanner() {
        try {
            val v = bannerView ?: return
            bannerView = null
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {
        }
    }

    private fun stepLabel(s: St) = when (s) {
        St.IDLE -> "idle"
        St.LAUNCH -> "waiting for Snapchat"
        St.CAMERA -> "opening Memories"
        St.MEMORIES -> "picking the black image"
        St.IMAGE -> "pressing Send To"
        St.SHORTCUT -> "selecting the shortcut"
        St.PICK -> "selecting the friend"
        St.SEND -> "pressing Send"
    }

    private fun showToast(msg: String, long: Boolean) {
        try {
            toast?.cancel()
            toast = Toast.makeText(applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
            toast?.show()
        } catch (_: Throwable) {
        }
    }

    /**
     * Snapchat's root node. Uses the active window first; if the system reports something else as active
     * (overlays, OEM floating widgets) falls back to the top-most application window - but only if that
     * window really is Snapchat, so taps can never land in another app.
     */
    private fun snapRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() == SNAP_PKG) return active
        try {
            val top = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val r = top?.root
            if (r != null && r.packageName?.toString() == SNAP_PKG) return r
        } catch (_: Throwable) {
        }
        return null
    }

    // ------------------------------------------------------------------------------------------
    // Screen reading & recognition
    // ------------------------------------------------------------------------------------------

    private fun norm(s: CharSequence?): String =
        (s?.toString() ?: "").replace("\uFE0F", "").replace("\u200B", "").trim().lowercase(Locale.ROOT)

    private fun readScreen(root: AccessibilityNodeInfo): Scr {
        val rb = Rect()
        root.getBoundsInScreen(rb)
        val out = ArrayList<N>()
        val queue = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        var i = 0
        while (i < queue.size && i < 1500) {
            val n = queue[i++]
            if (n.isVisibleToUser) {
                val text = norm(n.text)
                val desc = norm(n.contentDescription)
                val hint = norm(n.hintText)
                if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || n.isClickable) {
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    out.add(N(n, text, desc, hint, n.viewIdResourceName ?: "", b, n.isClickable, n.isEditable))
                }
            }
            for (k in 0 until n.childCount) n.getChild(k)?.let { queue.add(it) }
        }
        return Scr(out, rb.right.coerceAtLeast(1), rb.bottom.coerceAtLeast(1))
    }

    private fun signature(s: Scr): Int {
        var h = 17
        for (n in s.nodes) {
            h = 31 * h + n.text.hashCode()
            h = 31 * h + n.desc.hashCode()
            h = 31 * h + (n.b.top / 8) * 3 + n.b.left / 8
        }
        return h
    }

    private fun sendToHeader(s: Scr): N? =
        s.find { it.text == "send to" && s.cy(it) in 0.12f..0.80f && s.cx(it) < 0.4f }

    private fun classify(s: Scr): Kind {
        val emoji = norm(shortcutEmoji)
        val chips = s.hasExact("new friends") && s.hasExact("groups")
        val isList = chips ||
            s.hasExact("friends selected") ||
            s.hasExact("post to") || s.hasExact("post to...") ||
            s.hasExact("best friends") ||
            s.nodes.any { it.hint.startsWith("send to") }
        if (isList) {
            if (s.hasExact("friends selected") || findFinalSend(s) != null) return Kind.LIST_SELECTED
            val filtered = sendToHeader(s) != null ||
                s.hasExact("post to") ||
                s.nodes.any { it.editable && emoji.isNotEmpty() && it.text.contains(emoji) }
            return if (filtered) Kind.LIST_FILTERED else Kind.LIST_ALL
        }
        // Editor: the blue "Send To" button at the bottom right. (In the filtered list "Send To" is a
        // section header near the middle of the screen, so the position tells them apart.)
        if (s.nodes.any { it.eq("send to") && s.cy(it) > 0.85f && s.cx(it) > 0.45f }) return Kind.EDITOR
        if (s.hasContains("places, dates") ||
            (s.hasExact("camera roll") && s.hasExact("ai snaps")) ||
            s.hasExact("create video")
        ) return Kind.MEMORIES
        return Kind.UNKNOWN
    }

    private fun findMemoriesButton(s: Scr): N? =
        s.find { (it.eq("memories") || it.eq("gallery")) && s.cy(it) > 0.6f }

    private fun findEditorSend(s: Scr): N? =
        s.find { (it.eq("send to") || it.eq("send")) && s.cy(it) > 0.82f && s.cx(it) > 0.45f }
            ?: s.find { it.clickable && it.has("send") && !it.has("chat") && s.cy(it) > 0.82f && s.cx(it) > 0.55f }

    private fun findFinalSend(s: Scr): N? =
        s.find { (it.desc.contains("send") || it.text == "send") && !it.has("send to") && s.cy(it) > 0.88f && s.cx(it) > 0.6f }

    /** The shortcut chip: only looked for in the chip row near the top, never in the list. */
    private fun findChip(s: Scr): N? {
        val key = norm(shortcutEmoji)
        if (key.isEmpty()) return null
        val synonyms = if (key == "\uD83D\uDCAF") listOf("hundred points") else emptyList()
        return s.find { n ->
            s.cy(n) in 0.07f..0.21f && !n.editable && (
                n.text.contains(key) || n.desc.contains(key) ||
                    (key == "\uD83D\uDCAF" && (n.text == "100" || n.desc == "100")) ||
                    synonyms.any { n.desc.contains(it) || n.text.contains(it) }
                )
        }
    }

    /**
     * The friend's row in the FILTERED list. The "Post To > Private Story" card shows the same name as a
     * subtitle, so only nodes below the "Send To" header (and not next to a "story" label) are accepted.
     */
    private fun findRecipientRow(s: Scr): N? {
        val hdr = sendToHeader(s)
        val minTop = hdr?.b?.bottom ?: (s.h * 0.2f).toInt()
        return s.nodes
            .filter { it.b.top >= minTop - 4 && s.cy(it) < 0.90f && (it.text.contains(targetName) || it.desc.contains(targetName)) }
            .filter { !nearStoryLabel(s, it) }
            .minByOrNull { it.b.top }
    }

    private fun nearStoryLabel(s: Scr, n: N): Boolean {
        val pad = (s.h * 0.04f).toInt()
        val zone = Rect(n.b.left, n.b.top - pad, n.b.right, n.b.bottom + pad)
        return s.nodes.any { it !== n && (it.text.contains("story") || it.desc.contains("story")) && Rect.intersects(zone, it.b) }
    }

    // ------------------------------------------------------------------------------------------
    // Memories: locate the black tile using a screenshot, then tap it
    // ------------------------------------------------------------------------------------------

    private fun chooseAndTapTile(scr: Scr) {
        asyncBusy = true
        val id = runId
        captureBitmap { bmp ->
            asyncBusy = false
            if (id != runId || st != St.MEMORIES) return@captureBitmap

            val header = scr.find { SNAPS_HEADER.matches(it.text) }
            val tileTop = header?.b?.bottom?.plus(8) ?: (scr.h * 0.747f).toInt()
            val cols = 4
            val colW = scr.w / cols
            var chosen = -1

            if (bmp != null) {
                for (col in 0 until cols) {
                    if (col in triedTiles) continue
                    val l = col * colW + 8
                    val r = (col + 1) * colW - 8
                    val t = tileTop + 12
                    val b = minOf(t + (scr.h * 0.15f).toInt(), (scr.h * 0.92f).toInt())
                    val f = darkFraction(bmp, scr, l, t, r, b)
                    log("tile #$col black=${String.format(Locale.US, "%.2f", f)}")
                    if (f >= 0.99f) {
                        chosen = col
                        break
                    }
                }
                if (chosen < 0) log("no pure-black tile in the first row - trying the first untried tile (the opened image is checked before sending)")
            } else {
                log("screenshot unavailable - trying the first untried tile")
            }
            if (chosen < 0) chosen = (0 until cols).firstOrNull { it !in triedTiles } ?: 0
            triedTiles.add(chosen)

            val x = chosen * colW + colW / 2f
            val y = tileTop + scr.h * 0.06f
            log("Memories: tapping tile #$chosen at (${x.toInt()}, ${y.toInt()}), 'N Snaps' header ${if (header != null) "found" else "NOT found"}")
            tap(x, y)
        }
    }

    private fun verifyBlack(scr: Scr, cb: (Boolean?) -> Unit) {
        asyncBusy = true
        val id = runId
        captureBitmap { bmp ->
            asyncBusy = false
            if (id != runId) return@captureBitmap
            if (bmp == null) {
                cb(null)
                return@captureBitmap
            }
            // Centre of the canvas: avoids the tool column on the right, the top pill and the bottom bar.
            val f = darkFraction(bmp, scr, (scr.w * 0.12f).toInt(), (scr.h * 0.18f).toInt(), (scr.w * 0.80f).toInt(), (scr.h * 0.72f).toInt())
            log("image darkness = ${String.format(Locale.US, "%.3f", f)}")
            cb(f >= 0.97f)
        }
    }

    private fun darkFraction(bmp: Bitmap, scr: Scr, l: Int, t: Int, r: Int, b: Int): Float {
        val sx = bmp.width.toFloat() / scr.w
        val sy = bmp.height.toFloat() / scr.h
        val x0 = (l * sx).toInt().coerceIn(0, bmp.width - 1)
        val x1 = (r * sx).toInt().coerceIn(x0 + 1, bmp.width)
        val y0 = (t * sy).toInt().coerceIn(0, bmp.height - 1)
        val y1 = (b * sy).toInt().coerceIn(y0 + 1, bmp.height)
        var total = 0
        var dark = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bmp.getPixel(x, y)
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (lum < 20) dark++
                total++
                x += 8
            }
            y += 8
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    /** Screenshot through the accessibility service (API 30+). Always calls [cb] exactly once (null on failure). */
    private fun captureBitmap(cb: (Bitmap?) -> Unit) {
        var delivered = false
        val deliver = { bmp: Bitmap? ->
            if (!delivered) {
                delivered = true
                try {
                    cb(bmp)
                } catch (t: Throwable) {
                    fail("internal error: $t")
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            deliver(null)
            return
        }
        handler.postDelayed({
            if (!delivered) {
                log("screenshot timed out")
                deliver(null)
            }
        }, 4000)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var out: Bitmap? = null
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                        } catch (t: Throwable) {
                            log("screenshot decode failed: $t")
                        }
                        try {
                            result.hardwareBuffer.close()
                        } catch (_: Throwable) {
                        }
                        deliver(out)
                    }

                    override fun onFailure(errorCode: Int) {
                        log("screenshot failed (code $errorCode)")
                        deliver(null)
                    }
                }
            )
        } catch (t: Throwable) {
            log("screenshot error: $t")
            deliver(null)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Input helpers
    // ------------------------------------------------------------------------------------------

    /**
     * gesture=true  -> real touch at the node's centre (bounds come from the tree, so the position is known-good)
     * gesture=false -> semantic click on the nearest reasonably-sized clickable ancestor, else the same touch
     */
    private fun activate(n: N, scr: Scr, gesture: Boolean): Boolean {
        if (!gesture) {
            var cur: AccessibilityNodeInfo? = n.node
            var depth = 0
            while (cur != null && depth < 6) {
                if (cur.isClickable && cur.isEnabled) {
                    val b = Rect()
                    cur.getBoundsInScreen(b)
                    if (b.height() <= scr.h * 0.25f && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                cur = cur.parent
                depth++
            }
        }
        return tap(n.b.exactCenterX(), n.b.exactCenterY())
    }

    private fun tapFrac(scr: Scr, fx: Float, fy: Float): Boolean = tap(scr.w * fx, scr.h * fy)

    /** Chip fallback: X from the screenshots, Y taken from the real row ("New Friends" label) when visible. */
    private fun tapChipCoord(scr: Scr): Boolean {
        val anchor = scr.find { it.eq("new friends") && scr.cy(it) < 0.25f }
        val y = anchor?.b?.exactCenterY() ?: (scr.h * CHIP_Y)
        return tap(scr.w * CHIP_X, y)
    }

    private fun setSearchText(scr: Scr, text: String): Boolean {
        val field = scr.find { it.editable && scr.cy(it) < 0.22f } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tap(x: Float, y: Float, durationMs: Long = 90): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun swipeUp(scr: Scr): Boolean {
        val path = Path()
        path.moveTo(scr.w * 0.5f, scr.h * 0.86f)
        path.lineTo(scr.w * 0.5f, scr.h * 0.25f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ------------------------------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------------------------------

    private fun dump(s: Scr) {
        log("--- visible nodes (${s.nodes.size}, first 70) ---")
        for (n in s.nodes.take(70)) {
            val sb = StringBuilder()
            sb.append(String.format(Locale.US, "y=%.2f x=%.2f ", s.cy(n), s.cx(n)))
            if (n.text.isNotEmpty()) sb.append("t='").append(n.text.take(32)).append("' ")
            if (n.desc.isNotEmpty()) sb.append("d='").append(n.desc.take(32)).append("' ")
            if (n.hint.isNotEmpty()) sb.append("h='").append(n.hint.take(20)).append("' ")
            if (n.clickable) sb.append("[click] ")
            if (n.id.isNotEmpty()) sb.append(n.id.substringAfterLast('/'))
            log(sb.toString())
        }
    }

    // ------------------------------------------------------------------------------------------
    // AccessibilityService plumbing
    // ------------------------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: the run is driven by the polling ticker, not by events.
    }

    override fun onInterrupt() {
        log("service interrupted by the system")
    }

    override fun onCreate() {
        super.onCreate()
        setup(applicationContext)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("accessibility service connected ($BUILD)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopRun("service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        log("service destroyed by the system")
        instance = null
        handler.removeCallbacksAndMessages(null)
        removeBanner()
        super.onDestroy()
    }
}