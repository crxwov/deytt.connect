package space.deytt.connect

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubscriptionErrorTextTest {
    @Test
    fun typedErrorsHaveDistinctActionableSafeMessages() {
        val failures = listOf(
            java.net.UnknownHostException("secret") to "DNS",
            java.net.ConnectException("secret") to "другую сеть",
            javax.net.ssl.SSLHandshakeException("secret") to "дату и время",
            SubscriptionPayloadException() to "некорректную подписку",
            SubscriptionStorageException() to "свободное место",
            SubscriptionCancelledException() to "отменена",
            SubscriptionDeadlineException() to "не ответил вовремя",
        )
        failures.forEach { (error, hint) ->
            val message = SubscriptionErrorText.userMessage(error)
            org.junit.Assert.assertTrue(message, message.contains(hint))
            assertFalse(message.contains("secret"))
        }
    }

    @Test
    fun hidesOkHttpStreamInternals() {
        assertEquals(
            "Сервер оборвал соединение. Проверьте ссылку и повторите обновление.",
            SubscriptionErrorText.userMessage(IOException("unexpected end of stream on okhttp.Address@123")),
        )
    }

    @Test
    fun explainsGatewayFailureAsRetryable() {
        assertEquals(
            "Сервер подписки временно недоступен. Повторите обновление через несколько секунд.",
            SubscriptionErrorText.userMessage(SubscriptionHttpFailure(502, "bad gateway")),
        )
    }

    @Test
    fun explainsReadTimeoutWithoutExposingTransportDetails() {
        assertEquals(
            "Сервер не ответил вовремя. Повторите обновление через несколько секунд.",
            SubscriptionErrorText.userMessage(IOException("Read timed out for token=secret")),
        )
    }

    @Test
    fun neverReturnsRawTransportAddressOrToken() {
        val message = SubscriptionErrorText.userMessage(
            IOException("failed to connect to deytt.space/192.0.2.10:443 with token=secret"),
        )

        assertFalse(message.contains("192.0.2.10"))
        assertFalse(message.contains("secret"))
        assertEquals(
            "Не удалось обновить подписку. Проверьте ссылку и повторите попытку.",
            message,
        )
    }
}
