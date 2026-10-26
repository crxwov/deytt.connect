package space.deytt.connect

import java.io.IOException
import java.net.Socket

class RouteProbeCancellation {
    private val sockets = mutableSetOf<Socket>()
    private var cancelled = false

    fun track(socket: Socket) {
        val closeNow = synchronized(this) {
            if (cancelled) true else {
                sockets += socket
                false
            }
        }
        if (closeNow) {
            runCatching { socket.close() }
            throw IOException("Route probe cancelled")
        }
    }

    @Synchronized
    fun release(socket: Socket) {
        sockets -= socket
    }

    @Synchronized
    fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("Route probe cancelled")
    }

    fun cancel() {
        val closing = synchronized(this) {
            if (cancelled) emptyList() else {
                cancelled = true
                sockets.toList().also { sockets.clear() }
            }
        }
        closing.forEach { runCatching { it.close() } }
    }
}
