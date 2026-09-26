package space.deytt.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HappDeviceConfirmationTest {
    @Test fun acceptsOnlyConfirmedRequestedState() {
        for (blocked in listOf(true, false)) {
            TelegramPairingClient.requireDeviceUpdateConfirmation(JSONObject().put("ok", true).put("blocked", blocked), blocked)
        }
    }

    @Test fun absentBlockedFieldDoesNotFalselyConfirmRestore() {
        val failure = assertThrows(TelegramPairingException::class.java) {
            TelegramPairingClient.requireDeviceUpdateConfirmation(JSONObject().put("ok", true), false)
        }
        assertEquals("device_update_unconfirmed", failure.code)
    }

    @Test fun oppositeStateAndStringAreRejected() {
        for (value in listOf<Any>(true, "false", JSONObject.NULL)) {
            assertThrows(TelegramPairingException::class.java) {
                TelegramPairingClient.requireDeviceUpdateConfirmation(JSONObject().put("ok", true).put("blocked", value), false)
            }
        }
    }
}
