package com.ioscastaway.edgerewire.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.ioscastaway.edgerewire.gesture.Edge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-app edge navigation. The service does three things and nothing else:
 *
 * 1. Watches window events to learn which package is in the foreground and on which display.
 * 2. While a target package is in front, keeps two [EdgeOverlay] strips attached on that display.
 * 3. On an inward swipe, reads the browser's Back/Forward toolbar state and clicks the button,
 *    or deliberately does nothing when the button is disabled.
 *
 * Outside a target app there are no strips and no reads. The system's own gestures are untouched.
 */
class EdgeRewireService : AccessibilityService() {

    data class State(
        val foreground: String? = null,
        val displayId: Int = Display.DEFAULT_DISPLAY,
        val attached: Boolean = false,
        val lastAction: String = "",
    )

    companion object {
        private const val TAG = "EdgeRewire"
        private const val LOG_LINES = 80
        private const val ACTION_DUMP = "com.ioscastaway.edgerewire.DUMP"
        private const val BOUNDS_PROBE_INTERVAL_MS = 1000L
        private const val NUDGE_DP = 64
        private const val NUDGE_MS = 150L
        private const val SETTLE_AFTER_NUDGE_MS = 250L
        private const val TAP_MS = 60L

        private val _instance = MutableStateFlow<EdgeRewireService?>(null)
        /** Non-null while the service is bound. */
        val instance: StateFlow<EdgeRewireService?> = _instance.asStateFlow()

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private val _log = MutableStateFlow<List<String>>(emptyList())
        val log: StateFlow<List<String>> = _log.asStateFlow()

        fun clearLog() { _log.value = emptyList() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prefsJob: Job? = null
    private lateinit var prefs: Prefs
    private val navigator = BrowserNavigator(::log)
    private var settings = Settings()

    private var left: EdgeOverlay? = null
    private var right: EdgeOverlay? = null

    /** Debug only: `adb shell am broadcast -a com.ioscastaway.edgerewire.DUMP` logs the target tree. */
    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mode = intent.getStringExtra("cache") ?: "keep"
            when (mode) {
                "clear" -> { log("dump: clearCache() -> ${clearCache()}") }
                "off" -> { setCacheEnabled(false); log("dump: cache disabled") }
                "on" -> { setCacheEnabled(true); log("dump: cache enabled") }
            }
            val root = targetRoot()
            if (root == null) { log("dump: no target root"); return }
            val refreshed = root.refresh()
            log("dump: root.refresh()=$refreshed rootInActiveWindow.kids=${rootInActiveWindow?.childCount}")
            log("dump: windows=" + (try { windows.joinToString(" | ") { w -> "${w.type}/${w.root?.packageName ?: "-"} id=${w.id} f=${w.isFocused} ${Rect().also { w.getBoundsInScreen(it) }}" } } catch (t: Throwable) { "$t" }))
            dumpTree(root, 0, intent.getIntExtra("depth", 6))
        }
    }

    private fun dumpTree(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int, maxDepth: Int) {
        val b = Rect().also { n.getBoundsInScreen(it) }
        val id = n.viewIdResourceName?.substringAfter('/')
        val cls = n.className?.toString()?.substringAfterLast('.')
        val desc = n.contentDescription?.toString()?.take(24)
        log("  ".repeat(depth) + "$cls id=$id desc=$desc vis=${n.isVisibleToUser} en=${n.isEnabled} kids=${n.childCount} $b")
        if (depth >= maxDepth) return
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (c.className?.toString()?.contains("WebView") == true) {
                log("  ".repeat(depth + 1) + "WebView (subtree skipped) kids=${c.childCount}")
                continue
            }
            dumpTree(c, depth + 1, maxDepth)
        }
    }
    private var attachedDisplayId = Display.INVALID_DISPLAY

    /**
     * Screen bounds of the Back/Forward buttons, remembered whenever the toolbar is in the tree.
     * Samsung Internet draws the toolbar itself after the first hide-on-scroll (the Android views
     * stay hidden until the page is back at the top), so a swipe on a scrolled page can only reach
     * the buttons by tapping where they were. A disabled button swallows the tap, which keeps the
     * "root of the tab is a wall" rule intact.
     */
    private val buttonBounds = HashMap<BrowserNavigator.Direction, Rect>()
    private var lastBoundsProbeAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        settings = prefs.settings.value
        prefsJob = scope.launch {
            prefs.settings.collect { s ->
                val relayout = s != settings
                settings = s
                if (relayout) {
                    // Any knob change: drop the strips and let the next evaluation rebuild them.
                    detachStrips()
                    evaluate(force = true)
                }
            }
        }
        _instance.value = this
        registerReceiver(dumpReceiver, IntentFilter(ACTION_DUMP), RECEIVER_EXPORTED)
        log("service connected (targets=${settings.targets.joinToString()})")
        // The target may already be in front (service enabled while browsing); do not wait for
        // the next window event to find out.
        main.postDelayed({ evaluate(force = true) }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return // our own strips appearing and disappearing
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> evaluate(force = false)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Also keeps the framework's node cache valid. Work here is limited to learning the
                // button bounds once, while the toolbar happens to be in the tree.
                if (_state.value.attached && buttonBounds.size < 2 &&
                    event.packageName?.toString() in settings.targets
                ) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastBoundsProbeAt >= BOUNDS_PROBE_INTERVAL_MS) {
                        lastBoundsProbeAt = now
                        targetRoot()?.let { rememberBounds(it) }
                    }
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        try { unregisterReceiver(dumpReceiver) } catch (_: Throwable) {}
        prefsJob?.cancel()
        detachStrips()
        _instance.value = null
        _state.update { it.copy(attached = false) }
        log("service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------ foreground tracking

    /** Decide whether the strips should exist right now, and on which display. */
    private fun evaluate(force: Boolean) {
        val active = activeWindow()
        val pkg = active?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: return // transient: nothing focused yet, keep whatever we have
        val displayId = active?.displayId ?: Display.DEFAULT_DISPLAY
        val wanted = settings.enabled && pkg in settings.targets

        val prev = _state.value
        if (!force && prev.foreground == pkg && prev.displayId == displayId && prev.attached == wanted) return
        _state.update { it.copy(foreground = pkg, displayId = displayId) }

        if (wanted) {
            if (attachedDisplayId != displayId) detachStrips()
            attachStrips(displayId)
        } else {
            detachStrips()
        }
    }

    private fun activeWindow(): AccessibilityWindowInfo? = try {
        windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: windows.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------ strips

    private fun attachStrips(displayId: Int) {
        if (left?.isAttached == true && right?.isAttached == true) return
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId)
        if (display == null) {
            log("display $displayId not found; strips not attached")
            return
        }
        // An accessibility service's WindowManager is bound to a display through a display context.
        val ctx = createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val density = ctx.resources.displayMetrics.density
        val widthPx = (settings.edgeWidthDp * density).toInt().coerceAtLeast(1)
        val commitPx = settings.commitDistanceDp * density

        fun make(edge: Edge) = EdgeOverlay(
            context = ctx,
            windowManager = wm,
            edge = edge,
            widthPx = widthPx,
            commitDistancePx = commitPx,
            unrestrictedExclusion = settings.unrestrictedExclusion,
            debugTint = settings.debugTint,
            onSwipe = ::onSwipe,
            log = ::log,
        )
        left = make(Edge.LEFT).also { it.attach() }
        right = make(Edge.RIGHT).also { it.attach() }
        attachedDisplayId = displayId
        val ok = left?.isAttached == true && right?.isAttached == true
        _state.update { it.copy(attached = ok) }
        log("strips ${if (ok) "attached" else "FAILED"} on display $displayId (${settings.edgeWidthDp}dp, unrestricted=${settings.unrestrictedExclusion})")
        if (ok) main.postDelayed({ logCandidates(); targetRoot()?.let { rememberBounds(it) } }, 600)
    }

    private fun detachStrips() {
        if (left == null && right == null) return
        left?.detach(); right?.detach()
        left = null; right = null
        attachedDisplayId = Display.INVALID_DISPLAY
        buttonBounds.clear()
        _state.update { it.copy(attached = false) }
        log("strips detached")
    }

    // ------------------------------------------------------------ navigation

    /**
     * The browser's root node, looked up through the windows list rather than [rootInActiveWindow].
     *
     * While a finger is down on one of our strips, the framework's "active window" is the strip
     * itself (the window being touched), so at ACTION_UP `rootInActiveWindow` would hand back our
     * own empty overlay. The focused application window is the browser regardless of the touch.
     */
    private fun targetRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        val apps = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (t: Throwable) {
            emptyList()
        }
        val ordered = apps.sortedByDescending { it.isFocused }
        for (w in ordered) {
            val root = w.root ?: continue
            if (root.packageName?.toString() in settings.targets) return root
        }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() in settings.targets }
    }

    private fun rememberBounds(root: android.view.accessibility.AccessibilityNodeInfo) {
        for (d in BrowserNavigator.Direction.entries) {
            val n = navigator.find(root, d) ?: continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (!r.isEmpty && buttonBounds[d] != r) {
                buttonBounds[d] = r
                log("bounds $d = $r")
            }
        }
    }

    private fun onSwipe(edge: Edge) {
        val direction = when (edge) {
            Edge.LEFT -> BrowserNavigator.Direction.BACK
            Edge.RIGHT -> BrowserNavigator.Direction.FORWARD
        }
        val root = targetRoot()
        if (root == null) {
            report("$direction: no target window found")
            return
        }
        when (navigator.navigate(root, direction)) {
            BrowserNavigator.Result.Clicked -> { rememberBounds(root); report("$direction: clicked toolbar button") }
            BrowserNavigator.Result.Disabled -> { rememberBounds(root); report("$direction: button disabled, doing nothing (root of tab)") }
            BrowserNavigator.Result.ClickFailed -> report("$direction: click rejected by the app")
            BrowserNavigator.Result.NotFound -> tapWhereItWas(direction)
        }
    }

    /**
     * The toolbar is not in the accessibility tree. Two cases look identical from here: it is
     * hidden (scrolled), or it is on screen but drawn by the browser's compositor with the Android
     * views hidden, which is what Samsung Internet does after the first hide until the page is
     * back at the top. Cache clearing does not help; it is the app's own view state.
     *
     * So: nudge the page a little so a hidden toolbar comes back, wait for it to settle, then tap
     * where the button was last seen. If the button is disabled the tap does nothing.
     */
    private fun tapWhereItWas(direction: BrowserNavigator.Direction) {
        val target = buttonBounds[direction]
        if (target == null) {
            report("$direction: toolbar not in tree and its bounds are not known yet, doing nothing")
            return
        }
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y0 = dm.heightPixels * 0.55f
        val nudge = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y0); lineTo(x, y0 + dm.density * NUDGE_DP) }, 0, NUDGE_MS))
            .build()
        val sent = dispatchGesture(nudge, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                main.postDelayed({ tap(direction, target) }, SETTLE_AFTER_NUDGE_MS)
            }
            override fun onCancelled(g: GestureDescription?) = report("$direction: nudge cancelled")
        }, null)
        if (!sent) report("$direction: nudge not dispatched")
    }

    private fun tap(direction: BrowserNavigator.Direction, r: Rect) {
        val tap = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 0, TAP_MS))
            .build()
        dispatchGesture(tap, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                // After a real navigation the toolbar views come back; say what we can see.
                main.postDelayed({
                    val st = targetRoot()?.let { navigator.state(it) }
                    report("$direction: tapped drawn button at (${r.centerX()},${r.centerY()})" +
                        (st?.takeIf { it.visible }?.let { "; toolbar now back=${it.back} forward=${it.forward}" } ?: "; toolbar still not in tree"))
                }, 400)
            }
            override fun onCancelled(g: GestureDescription?) = report("$direction: tap cancelled")
        }, null)
    }

    private fun report(msg: String) {
        _state.update { it.copy(lastAction = msg) }
        log(msg)
    }

    /** Dump what the navigator sees, so the ids can be checked before anything is hardcoded. */
    fun logCandidates() {
        val root = targetRoot() ?: run { log("no target root"); return }
        val list = navigator.candidates(root)
        if (list.isEmpty()) log("no back/forward candidates in ${root.packageName}")
        list.forEach { (dir, c) -> log("candidate $dir: $c") }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        val stamped = "${TS.format(Date())} $line"
        _log.update { (it + stamped).takeLast(LOG_LINES) }
    }

    private val TS = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}
