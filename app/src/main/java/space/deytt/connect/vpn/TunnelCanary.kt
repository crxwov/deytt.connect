package space.deytt.connect

import java.io.IOException
import java.util.concurrent.CancellationException

internal object TunnelCanary {
    data class Endpoint(val url: String, val expectedStatus: Int?)

    // Each hostname-backed HTTPS success proves both DNS and transport. An
    // individual provider outage must not reject an otherwise working tunnel.
    val endpoints = listOf(
        Endpoint("https://www.cloudflare.com/cdn-cgi/trace", null),
        Endpoint("https://www.gstatic.com/generate_204", 204),
        Endpoint("https://example.com/", 200),
    )

    class HttpFailure(val status: Int) : IOException("проверочный сайт ответил HTTP $status")

    fun acceptsHttpResponse(actualStatus: Int, expectedStatus: Int?): Boolean =
        actualStatus in 200..299 && (expectedStatus == null || actualStatus == expectedStatus)

    /** Android may keep a replaced VPN in allNetworks briefly after teardown. */
    fun <T> currentNetwork(candidates: List<T>, previousNetworks: Set<T>): T? =
        candidates.firstOrNull { it !in previousNetworks }

    fun verifyAny(checkpoint: () -> Unit, probe: (Endpoint) -> Unit) {
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            checkpoint()
            var passed = false
            try {
                probe(endpoint)
                passed = true
            } catch (error: InterruptedException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
            // A late success from a stopped generation cannot connect the next one.
            checkpoint()
            if (passed) return
        }
        throw lastError ?: IOException("Не удалось выполнить HTTPS-проверку")
    }
}
