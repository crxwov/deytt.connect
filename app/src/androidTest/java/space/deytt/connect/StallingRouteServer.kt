package space.deytt.connect

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/** Holds synthetic VLESS handshakes open so cancellation tests never depend on Internet routing. */
internal class StallingRouteServer : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = mutableListOf<Socket>()
    private val accepted = CountDownLatch(1)

    init {
        thread(name = "probe-test-server", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(sockets) {
                    if (server.isClosed) socket.close() else sockets += socket
                }
                accepted.countDown()
            }
        }
    }

    fun configure(config: String): String = JSONObject(config).apply {
        put("dns", JSONObject("""{"servers":[{"type":"local","tag":"local-dns"}],"final":"local-dns"}"""))
        val outbounds = getJSONArray("outbounds")
        repeat(outbounds.length()) {
            outbounds.getJSONObject(it).takeIf { item -> item.optString("type") == "vless" }
                ?.put("server", "127.0.0.1")?.put("server_port", server.localPort)
        }
    }.toString()

    fun awaitConnection(): Boolean = accepted.await(5, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
    }
}
