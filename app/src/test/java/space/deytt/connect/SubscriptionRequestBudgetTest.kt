package space.deytt.connect

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRequestBudgetTest {
    @Test
    fun monotonicBudgetCannotBeExtendedByRetry() {
        var clock = 0L
        val budget = SubscriptionRequestBudget(1_000) { clock }
        assertEquals(1_000, budget.remainingMillis())
        clock = 999_000_000
        assertEquals(1, budget.remainingMillis())
        clock = 1_000_000_000
        assertTrue(runCatching { budget.check() }.exceptionOrNull() is SubscriptionDeadlineException)
    }

    @Test
    fun watchdogDisconnectsBlockedResponseAtDeadline() {
        val connection = FakeConnection()
        val error = runCatching {
            SubscriptionRequestBudget(80).withConnection(connection) {
                assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
                throw java.io.IOException("socket closed with private token")
            }
        }.exceptionOrNull()
        assertTrue(error is SubscriptionDeadlineException)
    }

    @Test
    fun watchdogInterruptsReadAndPreservesCancellation() {
        val connection = FakeConnection()
        var failure: Throwable? = null
        val started = CountDownLatch(1)
        val worker = Thread {
            failure = runCatching {
                SubscriptionRequestBudget(10_000).withConnection(connection) {
                    started.countDown()
                    // Simulate a socket read that does not react to interrupt.
                    while (connection.disconnected.count > 0) Thread.yield()
                    throw java.io.IOException("socket closed")
                }
            }.exceptionOrNull()
        }
        worker.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertTrue(failure is SubscriptionCancelledException)
    }

    private class FakeConnection : HttpURLConnection(URL("https://example.invalid")) {
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
    }
}
