package com.ioscastaway.edgerewire.gesture

/** Which screen edge a strip covers. */
enum class Edge { LEFT, RIGHT }

/** What a finished touch sequence on an edge strip turned out to be. */
enum class SwipeOutcome {
    /** A tap, a wobble, or a swipe that did not travel far enough inward. */
    NONE,
    /** A swipe that started on the edge and moved inward past the commit distance. */
    INWARD,
    /** The system (or another window) stole the pointer stream before it finished. */
    STOLEN,
}

/**
 * Pure classifier for one pointer on one edge strip. No Android types, so it can be unit-tested.
 *
 * Semantics follow Safari's interactive back gesture loosely: the decision is made on release,
 * from the final position, and a swipe that drifts vertically more than it travels inward is
 * treated as a scroll attempt rather than a navigation.
 *
 * @param edge which edge this detector serves; "inward" means away from that edge.
 * @param commitDistance how far (px) the finger must travel inward for the swipe to count.
 * @param maxDriftRatio |dy| / inward above which the swipe is rejected.
 */
class SwipeDetector(
    val edge: Edge,
    private val commitDistance: Float,
    private val maxDriftRatio: Float = 1.0f,
) {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false

    fun onDown(x: Float, y: Float) {
        startX = x
        startY = y
        tracking = true
    }

    /** Returns the inward distance so far, or 0 when not tracking. Useful for a visual hint. */
    fun onMove(x: Float, y: Float): Float = if (tracking) inward(x) else 0f

    fun onUp(x: Float, y: Float): SwipeOutcome {
        if (!tracking) return SwipeOutcome.NONE
        tracking = false
        val inward = inward(x)
        if (inward < commitDistance) return SwipeOutcome.NONE
        val drift = kotlin.math.abs(y - startY)
        return if (drift > inward * maxDriftRatio) SwipeOutcome.NONE else SwipeOutcome.INWARD
    }

    fun onCancel(): SwipeOutcome {
        if (!tracking) return SwipeOutcome.NONE
        tracking = false
        return SwipeOutcome.STOLEN
    }

    private fun inward(x: Float): Float = when (edge) {
        Edge.LEFT -> x - startX
        Edge.RIGHT -> startX - x
    }
}
