package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BleFastRoutePolicy
import app.getknit.knit.mesh.bluetooth.SideCarousel
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [BleFastRoutePolicy] — where the Bluetooth plane's fast path goes: links always, the page sometimes. */
class BleFastRoutePolicyTest {
    private fun env(
        type: String = FrameType.CHAT,
        sender: String = "alice",
        recipient: String? = null,
    ) = RelayEnvelope(type = type, id = "id-$type", senderId = sender, recipientId = recipient, payload = ByteArray(0))

    private val linked = setOf("bob", "carol")

    @Test
    fun fanoutAlwaysTakesEveryLink() {
        // The link copy the composite used to send for a plane without a fast plane — a typing cue is never
        // flooded, so this is how it reaches a linked peer at all.
        val route = BleFastRoutePolicy.fanout(env(), linked, sideAvailable = false)
        assertEquals(linked, route.linkTargets)
        assertNull(route.side)
    }

    @Test
    fun fanoutNeverRoutesAFrameBackToItsAuthor() {
        // A page carries no hop id: a frame first heard off one is re-fanned with the author as its hop, so
        // the router's split horizon cannot exclude the link the copy would have come by. The author has its
        // own frame by definition.
        val route = BleFastRoutePolicy.fanout(env(sender = "bob"), linked, sideAvailable = false)
        assertEquals(setOf("carol"), route.linkTargets)
    }

    @Test
    fun fanoutOffersThePageOnlyWhenTheChannelIsAvailable() {
        val route = BleFastRoutePolicy.fanout(env(FrameType.REACTION), linked, sideAvailable = true)
        assertEquals(linked, route.linkTargets)
        assertEquals(BleFastRoutePolicy.SideOffer(SideCarousel.Kind.CONTENT, null), route.side)
    }

    @Test
    fun aTypingCueCoalescesPerSender() {
        val route = BleFastRoutePolicy.fanout(env(FrameType.TYPING, sender = "alice"), emptySet(), sideAvailable = true)
        assertEquals(BleFastRoutePolicy.SideOffer(SideCarousel.Kind.TYPING, "typing:alice"), route.side)
    }

    @Test
    fun sendGoesToTheLinkedAddresseeAndNeverThePage() {
        assertEquals(BleFastRoutePolicy.Route(setOf("bob"), null), BleFastRoutePolicy.send("bob", linked))
        assertEquals(BleFastRoutePolicy.Route(emptySet(), null), BleFastRoutePolicy.send("dave", linked))
    }
}
