package com.ioscastaway.edgerewire.platform

import android.accessibilityservice.AccessibilityService
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
    private var attachedDisplayId = Display.INVALID_DISPLAY

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
        log("service connected (targets=${settings.targets.joinToString()})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return // our own strips appearing and disappearing
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> evaluate(force = false)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
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
        if (ok) main.postDelayed({ logCandidates() }, 600)
    }

    private fun detachStrips() {
        if (left == null && right == null) return
        left?.detach(); right?.detach()
        left = null; right = null
        attachedDisplayId = Display.INVALID_DISPLAY
        _state.update { it.copy(attached = false) }
        log("strips detached")
    }

    // ------------------------------------------------------------ navigation

    private fun onSwipe(edge: Edge) {
        val direction = when (edge) {
            Edge.LEFT -> BrowserNavigator.Direction.BACK
            Edge.RIGHT -> BrowserNavigator.Direction.FORWARD
        }
        val root = rootInActiveWindow
        val result = navigator.navigate(root, direction)
        val msg = when (result) {
            BrowserNavigator.Result.Clicked -> "$direction: clicked toolbar button"
            BrowserNavigator.Result.Disabled -> "$direction: button disabled, doing nothing (root of tab)"
            BrowserNavigator.Result.ClickFailed -> "$direction: click rejected by the app"
            BrowserNavigator.Result.NotFound ->
                if (direction == BrowserNavigator.Direction.BACK && settings.fallbackToSystemBack) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    "$direction: button not found, fell back to system back"
                } else {
                    "$direction: button not found, doing nothing"
                }
        }
        _state.update { it.copy(lastAction = msg) }
        log(msg)
    }

    /** Dump what the navigator sees, so the ids can be checked before anything is hardcoded. */
    fun logCandidates() {
        val root = rootInActiveWindow ?: run { log("no active root"); return }
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
