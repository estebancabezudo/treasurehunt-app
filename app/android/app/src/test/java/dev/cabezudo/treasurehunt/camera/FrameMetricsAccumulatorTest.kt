package dev.cabezudo.treasurehunt.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMetricsAccumulatorTest {
    @Test
    fun firstFrameStartsRunningWithRealMetadata() {
        val metrics = FrameMetricsAccumulator()

        val state = metrics.recordFrame(frame(timestampNanos = 4_000), nowNanos = 1_000)

        assertEquals(FrameAnalysisStatus.RUNNING, state.status)
        assertEquals(1, state.totalFrames)
        assertEquals(1, state.framesLastSecond)
        assertEquals(FrameResolution(640, 480), state.resolution)
        assertEquals(90, state.rotationDegrees)
        assertEquals(35, state.format)
        assertEquals(3, state.planeCount)
        assertEquals(0.0, state.approximateFps, 0.0)
    }

    @Test
    fun severalFramesAccumulateWithoutLosingCount() {
        val metrics = FrameMetricsAccumulator()

        repeat(4) { index ->
            metrics.recordFrame(frame(index.toLong()), index * 100_000_000L)
        }

        val state = metrics.snapshot(300_000_000L)
        assertEquals(4, state.totalFrames)
        assertEquals(4, state.framesLastSecond)
        assertEquals(10.0, state.approximateFps, 0.001)
    }

    @Test
    fun fpsUsesMonotonicArrivalIntervals() {
        val metrics = FrameMetricsAccumulator()
        metrics.recordFrame(frame(99_000), 0)
        metrics.recordFrame(frame(1), 500_000_000L)
        metrics.recordFrame(frame(50), 1_000_000_000L)

        assertEquals(2.0, metrics.snapshot(1_000_000_000L).approximateFps, 0.001)
    }

    @Test
    fun intervalWithoutFramesExpiresWindowAndReportsAge() {
        val metrics = FrameMetricsAccumulator()
        metrics.recordFrame(frame(1), 100_000_000L)

        val state = metrics.snapshot(1_600_000_000L)

        assertEquals(0, state.framesLastSecond)
        assertEquals(0.0, state.approximateFps, 0.0)
        assertEquals(1_500L, state.lastFrameAgeMillis)
    }

    @Test
    fun diagnosticPublicationIsLimitedToFourTimesPerSecond() {
        val metrics = FrameMetricsAccumulator(publicationIntervalNanos = 250_000_000L)
        metrics.recordFrame(frame(1), 0)

        assertTrue(metrics.snapshotIfPublicationDue(0) != null)
        assertNull(metrics.snapshotIfPublicationDue(100_000_000L))
        assertNull(metrics.snapshotIfPublicationDue(249_999_999L))
        assertTrue(metrics.snapshotIfPublicationDue(250_000_000L) != null)
    }

    @Test
    fun pauseAndRecoveryReturnToRunningOnNextFrame() {
        val metrics = FrameMetricsAccumulator()
        metrics.recordFrame(frame(1), 0)

        assertEquals(FrameAnalysisStatus.PAUSED, metrics.markPaused(10).status)
        assertEquals(FrameAnalysisStatus.STARTING, metrics.markStarting(20).status)
        assertEquals(FrameAnalysisStatus.RUNNING, metrics.recordFrame(frame(2), 30).status)
    }

    @Test
    fun closeProducesTerminalClosedState() {
        val metrics = FrameMetricsAccumulator()
        metrics.recordFrame(frame(1), 0)

        assertEquals(FrameAnalysisStatus.CLOSED, metrics.markClosed(100).status)
    }

    @Test
    fun analysisErrorKeepsConcreteCause() {
        val metrics = FrameMetricsAccumulator()

        val state = metrics.markError("Formato inválido", 10)

        assertEquals(FrameAnalysisStatus.ERROR, state.status)
        assertEquals("Formato inválido", state.lastError)
    }

    private fun frame(timestampNanos: Long) = FrameMetadata(
        width = 640,
        height = 480,
        format = 35,
        rotationDegrees = 90,
        timestampNanos = timestampNanos,
        planeCount = 3,
        interFrameNanos = null,
    )
}
