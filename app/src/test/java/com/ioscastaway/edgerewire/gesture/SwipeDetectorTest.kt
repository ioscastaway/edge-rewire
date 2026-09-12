package com.ioscastaway.edgerewire.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeDetectorTest {

    private fun detector(edge: Edge) = SwipeDetector(edge, commitDistance = 100f, maxDriftRatio = 1f)

    @Test
    fun leftEdgeSwipeRightCommits() {
        val d = detector(Edge.LEFT)
        d.onDown(5f, 500f)
        d.onMove(80f, 502f)
        assertEquals(SwipeOutcome.INWARD, d.onUp(160f, 505f))
    }

    @Test
    fun rightEdgeSwipeLeftCommits() {
        val d = detector(Edge.RIGHT)
        d.onDown(1075f, 500f)
        assertEquals(SwipeOutcome.INWARD, d.onUp(900f, 490f))
    }

    @Test
    fun shortSwipeIsIgnored() {
        val d = detector(Edge.LEFT)
        d.onDown(5f, 500f)
        assertEquals(SwipeOutcome.NONE, d.onUp(60f, 500f))
    }

    @Test
    fun wrongDirectionIsIgnored() {
        val d = detector(Edge.RIGHT)
        d.onDown(1075f, 500f)
        assertEquals(SwipeOutcome.NONE, d.onUp(1200f, 500f))
    }

    @Test
    fun verticalDriftIsTreatedAsScroll() {
        val d = detector(Edge.LEFT)
        d.onDown(5f, 500f)
        assertEquals(SwipeOutcome.NONE, d.onUp(160f, 900f))
    }

    @Test
    fun cancelReportsStolenPointer() {
        val d = detector(Edge.LEFT)
        d.onDown(5f, 500f)
        d.onMove(90f, 500f)
        assertEquals(SwipeOutcome.STOLEN, d.onCancel())
        assertEquals(SwipeOutcome.NONE, d.onUp(300f, 500f))
    }

    @Test
    fun upWithoutDownIsNone() {
        assertEquals(SwipeOutcome.NONE, detector(Edge.LEFT).onUp(300f, 500f))
    }
}
