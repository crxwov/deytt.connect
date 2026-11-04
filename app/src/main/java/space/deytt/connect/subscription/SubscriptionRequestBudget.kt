package space.deytt.connect

import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Monotonic deadline shared by retries and response reads, including trickling servers. */
internal class SubscriptionRequestBudget(
    timeoutMillis: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val deadline = clockNanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    fun check() {
        if (Thread.currentThread().isInterrupted) throw SubscriptionCancelledException()
        if (clockNanos() >= deadline) throw SubscriptionDeadlineException()
    }

    fun remainingMillis(): Int {
        check()
        return TimeUnit.NANOSECONDS.toMillis(deadline - clockNanos()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    fun <T> withConnection(connection: HttpURLConnection, block: () -> T): T {
        check()
        val owner = Thread.currentThread()
        // HttpURLConnection reads ignore Thread.interrupt(). Disconnect the active
        // socket explicitly on cancellation or deadline, without changing TLS trust.
        val watcher = watchdog.scheduleWithFixedDelay({
            if (owner.isInterrupted || clockNanos() >= deadline) connection.disconnect()
        }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            val result = block()
            check()
            return result
        } catch (error: Exception) {
            check()
            throw error
        } finally {
            watcher.cancel(false)
            connection.disconnect()
        }
    }

    private companion object {
        val watchdog = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "subscription-deadline").apply { isDaemon = true }
        }
    }
}
