package com.ioscastaway.edgerewire.platform

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.ioscastaway.edgerewire.gesture.Edge
import com.ioscastaway.edgerewire.gesture.SwipeDetector
import com.ioscastaway.edgerewire.gesture.SwipeOutcome

/**
 * One thin, full-height window hugging a screen edge.
 *
 * Two things make it more than a touch target:
 *
 * 1. It registers its whole bounds as a system-gesture exclusion rect, so the system's edge back
 *    gesture is not recognised there and the touch stream reaches us instead.
 * 2. Optionally it requests hidden navigation bars with "show transient bars by swipe". The window
 *    manager lifts the 200dp-per-edge exclusion cap for windows in that state (the fullscreen-game
 *    path). The window is not focusable, so it never becomes the insets control target and the
 *    real navigation bar is unaffected. Whether the cap is actually lifted for an accessibility
 *    overlay is one of the questions this experiment exists to answer; see the README.
 */
class EdgeOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    val edge: Edge,
    private val widthPx: Int,
    commitDistancePx: Float,
    private val unrestrictedExclusion: Boolean,
    private val debugTint: Boolean,
    private val onSwipe: (Edge) -> Unit,
    private val log: (String) -> Unit,
) {
    private val detector = SwipeDetector(edge, commitDistancePx)
    private var view: StripView? = null

    val isAttached: Boolean get() = view != null

    fun attach() {
        if (view != null) return
        val v = StripView(context)
        val lp = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or if (edge == Edge.LEFT) Gravity.START else Gravity.END
            // Span the whole edge: ignore status/navigation bar insets and the cutout.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "EdgeRewire:${edge.name}"
        }
        try {
            windowManager.addView(v, lp)
            view = v
        } catch (t: Throwable) {
            log("attach ${edge.name} failed: $t")
            Log.w(TAG, "addView failed", t)
        }
    }

    fun detach() {
        val v = view ?: return
        view = null
        try {
            windowManager.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }

    private inner class StripView(context: Context) : View(context) {

        init {
            setBackgroundColor(
                if (debugTint) (if (edge == Edge.LEFT) 0x3300C853 else 0x33FFAB00) else Color.TRANSPARENT,
            )
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!unrestrictedExclusion) return
            // Request the "immersive sticky" state on our own window only.
            windowInsetsController?.let { c ->
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.navigationBars())
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            // Exclude our full bounds from system gestures. Reported in view coordinates.
            systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    detector.onDown(event.rawX, event.rawY)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    detector.onMove(event.rawX, event.rawY)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    when (detector.onUp(event.rawX, event.rawY)) {
                        SwipeOutcome.INWARD -> onSwipe(edge)
                        SwipeOutcome.NONE -> Unit
                        SwipeOutcome.STOLEN -> Unit
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (detector.onCancel() == SwipeOutcome.STOLEN) {
                        // The system back gesture pilfered the pointers: exclusion did not hold here.
                        log("${edge.name}: pointer stolen (system gesture won)")
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private companion object {
        const val TAG = "EdgeRewire"
    }
}
