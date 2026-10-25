package app.getknit.knit.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sensors
import app.getknit.knit.R
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one definition of "how far did it get", shared by the chat bubble's tick description and the
 * message-details screen's visible line. The rule worth pinning: the plane only qualifies a delivery that
 * actually happened — an un-acked send names no plane, however it was eventually going to travel.
 */
class DeliveryStatusTest {
    @Test
    fun `pendingKey outranks the tick`() {
        assertEquals(DeliveryStatus.Pending, DeliveryStatus.of(message(pendingKey = true)))
        assertEquals(DeliveryStatus.Sent, DeliveryStatus.of(message()))
        assertEquals(DeliveryStatus.Delivered, DeliveryStatus.of(message(received = true)))
    }

    @Test
    fun `our own message reports sent, delivered, or delivered over the Internet`() {
        assertEquals(
            DeliveryText(R.string.chat_status_pending_key),
            deliveryLabel(DeliveryStatus.Pending, DeliveryPlane.Unknown, mine = true),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_sent),
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Unknown, mine = true),
        )
        // A receipt over either phone radio reads exactly as Nearby does (ADR 2026-10.e42d records which one).
        for (plane in listOf(DeliveryPlane.Nearby, DeliveryPlane.Bluetooth, DeliveryPlane.WifiAware)) {
            assertEquals(
                DeliveryText(R.string.chat_status_delivered),
                deliveryLabel(DeliveryStatus.Delivered, plane, mine = true),
            )
        }
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_internet),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = true),
        )
    }

    @Test
    fun `a group send reports the ratio the tick cannot — but only once a receipt is recorded`() {
        // ✓✓ flips on the FIRST member's ack, so on a group the glyph means "at least one". The count is
        // what makes the description say which many.
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_count, listOf(2, 3)),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true, delivered = 2, total = 3),
        )
        // The count outranks the plane: a group's stored plane is whichever member acked first.
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_count, listOf(3, 3)),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = true, delivered = 3, total = 3),
        )
        // Ticked with nothing recorded = acked before this device kept receipts. "Delivered to 0 of 3"
        // would contradict its own tick, so it falls back to the plain wording.
        assertEquals(
            DeliveryText(R.string.chat_status_delivered),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true, delivered = 0, total = 3),
        )
        // Not yet delivered at all: still "Sent", never "Delivered to 0 of 3".
        assertEquals(
            DeliveryText(R.string.chat_status_sent),
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Unknown, mine = true, delivered = 0, total = 3),
        )
    }

    @Test
    fun `callers with no roster to hand get exactly the old wording`() {
        // The chat list, every DM, every broadcast message — omitting the counts must change nothing.
        assertEquals(
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true, delivered = null, total = null),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_internet),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = true, delivered = 2, total = 0),
        )
    }

    @Test
    fun `an un-acked send names no plane even when one is recorded`() {
        assertEquals(
            DeliveryText(R.string.chat_status_sent),
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Internet, mine = true),
        )
    }

    @Test
    fun `a received message describes its own arrival, not our delivery`() {
        assertEquals(
            DeliveryText(R.string.chat_status_arrived_internet),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = false),
        )
        // All three radio values read as "nearby" — see DeliveryPlane.
        for (plane in listOf(DeliveryPlane.Nearby, DeliveryPlane.Bluetooth, DeliveryPlane.WifiAware, DeliveryPlane.Unknown)) {
            assertEquals(
                DeliveryText(R.string.chat_status_arrived_nearby),
                deliveryLabel(DeliveryStatus.Delivered, plane, mine = false),
            )
        }
    }

    @Test
    fun `a LoRa delivery names its plane in both directions`() {
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_lora),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.LoRa, mine = true),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_arrived_lora),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.LoRa, mine = false),
        )
        // An un-acked send still names no plane, and the group count still outranks it.
        assertEquals(
            DeliveryText(R.string.chat_status_sent),
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.LoRa, mine = true),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_count, listOf(1, 4)),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.LoRa, mine = true, delivered = 1, total = 4),
        )
    }

    @Test
    fun `the plane glyph is painted for the Internet and LoRa only`() {
        assertEquals(Icons.Filled.Public, planeGlyph(DeliveryPlane.Internet))
        assertEquals("relay", planeTag(DeliveryPlane.Internet))
        assertEquals(Icons.Filled.Sensors, planeGlyph(DeliveryPlane.LoRa))
        assertEquals("lora", planeTag(DeliveryPlane.LoRa))
        // A phone radio (and an unknown plane) paints nothing — the plain case needs no ornament.
        for (plane in listOf(DeliveryPlane.Nearby, DeliveryPlane.Bluetooth, DeliveryPlane.WifiAware, DeliveryPlane.Unknown)) {
            assertNull(planeGlyph(plane))
            assertNull(planeTag(plane))
        }
    }

    private fun message(
        received: Boolean = false,
        pendingKey: Boolean = false,
    ) = MessageEntity(
        id = "m1",
        senderId = "me",
        body = "hi",
        sentAt = 0L,
        received = received,
        pendingKey = pendingKey,
    )
}
