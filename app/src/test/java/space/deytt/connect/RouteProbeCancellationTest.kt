package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RouteProbeCancellationTest {
    @Test
    fun cancelClosesTrackedAndLateSocketsIdempotently() {
        val cancellation = RouteProbeCancellation()
        val current = TrackingSocket()
        val late = TrackingSocket()

        cancellation.track(current)
        cancellation.cancel()
        cancellation.cancel()

        assertTrue(current.closed)
        assertEquals(1, current.closeCalls)
        try {
            cancellation.track(late)
            throw AssertionError("late socket accepted")
        } catch (_: IOException) { }
        assertTrue(late.closed)
        assertEquals(1, late.closeCalls)
        try {
            cancellation.check()
            throw AssertionError("cancelled probe continued")
        } catch (_: IOException) { }
    }

    @Test
    fun releasePreventsCancellationFromClosingCompletedSocket() {
        val cancellation = RouteProbeCancellation()
        val socket = TrackingSocket()
        cancellation.track(socket)
        cancellation.release(socket)
        cancellation.cancel()
        assertFalse(socket.closed)
    }

    @Test
    fun blockedSocketCloseDoesNotHoldRegistryLock() {
        val cancellation = RouteProbeCancellation()
        val slow = BlockingSocket()
        val late = TrackingSocket()
        cancellation.track(slow)
        val cancelling = Thread { cancellation.cancel() }.apply { start() }

        assertTrue(slow.closeStarted.await(1, TimeUnit.SECONDS))
        val lateTracked = CountDownLatch(1)
        Thread {
            try { cancellation.track(late) } catch (_: IOException) { }
            lateTracked.countDown()
        }.start()
        assertTrue(lateTracked.await(200, TimeUnit.MILLISECONDS))
        assertTrue(late.closed)
        slow.allowClose.countDown()
        cancelling.join(1_000)
        assertFalse(cancelling.isAlive)
    }

    private class TrackingSocket : Socket() {
        var closeCalls = 0
        val closed get() = closeCalls > 0
        override fun close() { closeCalls++ }
    }

    private class BlockingSocket : Socket() {
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        override fun close() {
            closeStarted.countDown()
            allowClose.await(1, TimeUnit.SECONDS)
        }
    }
}
