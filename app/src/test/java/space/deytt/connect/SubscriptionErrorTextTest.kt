package space.deytt.connect

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionErrorTextTest {
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
}
