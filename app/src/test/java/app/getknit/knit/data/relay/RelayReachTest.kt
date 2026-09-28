package app.getknit.knit.data.relay

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.ui.peer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules behind the chat's relay indicators. Pure JVM by design — no Robolectric, both because the
 * logic needs none and because mixing Robolectric into the unit suite trips a Gradle result-serialization
 * race on this toolchain.
 */
class RelayReachTest {
    private val covered =
        RelayFacts(
            enabled = true,
            configured = 1,
            active = 1,
            connected = 1,
            coveredLabels = setOf("peer-a", "group-1"),
            maxAttachBytes = 16 * 1024 * 1024,
        )

    @Test
    fun `plane off says nothing at all`() {
        val off = covered.copy(enabled = false)
        assertEquals(RelayReach.Silent, reachFor("peer-a", off))
        assertEquals(RelayReach.Silent, reachFor(Conversations.NEARBY, off))
    }

    @Test
    fun `no relay configured says nothing`() {
        assertEquals(RelayReach.Silent, reachFor("peer-a", covered.copy(configured = 0, active = 0, connected = 0)))
    }

    @Test
    fun `a list whose relays are all parked reaches nothing, however long it is`() {
        // The user's own switches, not an outage: the rules turn on how many relays may carry, so a
        // configured-but-parked list must read exactly like an empty one.
        val parked = covered.copy(configured = 3, active = 0, connected = 0)
        assertEquals(RelayPlane.Off, planeFor(parked))
        assertEquals(RelayReach.Silent, reachFor("peer-a", parked))
    }

    @Test
    fun `an outage stays silent rather than painting every thread`() {
        // Enabled and configured, but nothing connected: coverage is unknown, not absent. Reporting
        // "not covered" here would flip a notice onto every open chat during a transient outage.
        assertEquals(RelayReach.Silent, reachFor("peer-a", covered.copy(connected = 0)))
    }

    @Test
    fun `a scoped conversation is covered`() {
        assertEquals(RelayReach.Covered, reachFor("peer-a", covered))
        assertEquals(RelayReach.Covered, reachFor("group-1", covered))
    }

    @Test
    fun `the broadcast room is never covered, even if a scope somehow claimed its id`() {
        // The room is excluded structurally by the frame-set rule (spec 4.4), so it outranks the
        // coverage set rather than being looked up in it.
        val odd = covered.copy(coveredLabels = covered.coveredLabels + Conversations.NEARBY)
        assertEquals(RelayReach.Room, reachFor(Conversations.NEARBY, odd))
    }

    @Test
    fun `the Meshtastic room says nothing about relays, ever`() {
        // Its posts are a local mirror of the board's own channel and never enter Knit's mesh, so no
        // relay could carry them under any configuration — "not covered yet" would promise a coverage
        // that is never coming. Silent on a live plane, and silent even if a scope claimed its id.
        assertEquals(RelayReach.Silent, reachFor(Conversations.MESHTASTIC, covered))
        val odd = covered.copy(coveredLabels = covered.coveredLabels + Conversations.MESHTASTIC)
        assertEquals(RelayReach.Silent, reachFor(Conversations.MESHTASTIC, odd))
        assertEquals(RelayReach.Silent, noticeFor(Conversations.MESHTASTIC, covered, roomNoticeDismissed = false))
    }

    @Test
    fun `an unscoped conversation is pending`() {
        assertEquals(RelayReach.Pending, reachFor("peer-unknown", covered))
    }

    @Test
    fun `a DM to a peer without forward secrecy says so instead of promising coverage`() {
        // No ratchet session can form, so no DM scope: "not covered yet" would wait forever, while an
        // update on the peer's side really would bring it.
        assertEquals(RelayReach.NoForwardSecrecy, reachFor("peer-z", covered, peerLacksForwardSecrecy = true))
        assertEquals(RelayReach.NoForwardSecrecy, noticeFor("peer-z", covered, roomNoticeDismissed = true, peerLacksForwardSecrecy = true))
        assertEquals(false, dismissable(RelayReach.NoForwardSecrecy))
    }

    @Test
    fun `a peer without forward secrecy changes nothing the plane itself already decides`() {
        // Plane off, parked or down stays silent; a scope that does exist (a pair scope needs no session)
        // still reads as covered.
        assertEquals(RelayReach.Silent, reachFor("peer-z", covered.copy(enabled = false), peerLacksForwardSecrecy = true))
        assertEquals(RelayReach.Silent, reachFor("peer-z", covered.copy(connected = 0), peerLacksForwardSecrecy = true))
        assertEquals(RelayReach.Covered, reachFor("peer-a", covered, peerLacksForwardSecrecy = true))
    }

    @Test
    fun `only a pinned profile without the ratchet bit lacks forward secrecy`() {
        assertEquals(true, lacksForwardSecrecy(peer("z", pubKey = "k", capabilities = null)))
        assertEquals(true, lacksForwardSecrecy(peer("z", pubKey = "k", capabilities = 0L)))
        assertEquals(false, lacksForwardSecrecy(peer("z", pubKey = "k", capabilities = Protocol.CAP_RATCHET)))
        // No profile yet: the keys have not arrived, which is what Pending already says.
        assertEquals(false, lacksForwardSecrecy(peer("z", pubKey = null, capabilities = null)))
        assertEquals(false, lacksForwardSecrecy(null))
    }

    @Test
    fun `a commons is covered by its relay and silent without it, never pending`() {
        // Its scope is bound to one relay: covered while that relay carries it, and while it does not
        // there is no other relay that could, so "becoming eligible" would be a promise nothing can keep.
        val room = Conversations.commonsIdFor("cd".repeat(32))
        assertEquals(RelayReach.Covered, reachFor(room, covered.copy(coveredLabels = setOf(room))))
        assertEquals(RelayReach.Silent, reachFor(room, covered))
        assertEquals(RelayReach.Silent, reachFor(room, covered.copy(connected = 0)))
    }

    @Test
    fun `dismissing the room notice silences it for good`() {
        assertEquals(RelayReach.Room, noticeFor(Conversations.NEARBY, covered, roomNoticeDismissed = false))
        assertEquals(RelayReach.Silent, noticeFor(Conversations.NEARBY, covered, roomNoticeDismissed = true))
    }

    @Test
    fun `the dismissal is the room's alone, never a pending thread's`() {
        // One device-wide flag serves every thread, so the rule has to be keyed on the reach and not just
        // on the flag: a dismissed room must leave an uncovered DM's notice — which clears itself once a
        // scope lands — exactly where it was.
        assertEquals(RelayReach.Pending, noticeFor("peer-z", covered, roomNoticeDismissed = true))
        assertEquals(RelayReach.Covered, noticeFor("peer-a", covered, roomNoticeDismissed = true))
    }

    @Test
    fun `only the room offers a close button`() {
        assertEquals(true, dismissable(RelayReach.Room))
        assertEquals(false, dismissable(RelayReach.Pending))
        assertEquals(false, dismissable(RelayReach.Covered))
        assertEquals(false, dismissable(RelayReach.Silent))
    }

    @Test
    fun `the header plane is off whenever the user has nothing armed`() {
        assertEquals(RelayPlane.Off, planeFor(RelayFacts()))
        assertEquals(RelayPlane.Off, planeFor(covered.copy(enabled = false)))
        // Enabled with an empty relay list is still nothing to report — the plane cannot carry anything.
        assertEquals(RelayPlane.Off, planeFor(covered.copy(configured = 0, active = 0, connected = 0)))
    }

    @Test
    fun `an outage dims the header even though it silences the per-thread notice`() {
        // Deliberately the opposite call from reachFor on the same facts: one glyph the user can read as
        // transient, versus a notice that would appear across every open conversation.
        val outage = covered.copy(connected = 0)
        assertEquals(RelayPlane.Down, planeFor(outage))
        assertEquals(RelayReach.Silent, reachFor("peer-a", outage))
    }

    @Test
    fun `one connected relay is enough for the header to read live`() {
        assertEquals(RelayPlane.Live, planeFor(covered.copy(configured = 3, active = 3, connected = 1)))
        // Coverage is per conversation; the plane being up says nothing about which scopes exist yet.
        assertEquals(RelayPlane.Live, planeFor(covered.copy(coveredLabels = emptySet())))
    }

    @Test
    fun `attachment reach is silent wherever the conversation notice already speaks`() {
        // The thread-level notice covers these; repeating it per photo would be noise.
        assertEquals(AttachmentRelay.Silent, attachmentReach(Conversations.NEARBY, 1_000, covered))
        assertEquals(AttachmentRelay.Silent, attachmentReach("peer-unknown", 1_000, covered))
        assertEquals(AttachmentRelay.Silent, attachmentReach("peer-a", 1_000, covered.copy(enabled = false)))
    }

    @Test
    fun `relays advertising no attachment support report unsupported`() {
        assertEquals(
            AttachmentRelay.Unsupported,
            attachmentReach("peer-a", 1_000, covered.copy(maxAttachBytes = null)),
        )
    }

    @Test
    fun `an attachment exactly filling the budget still relays`() {
        val twoChunks = ScopeCrypto.ATTACH_CHUNK_BYTES * 2
        val budget = ScopeCrypto.SEALED_CHUNK_BYTES * 2
        assertEquals(
            AttachmentRelay.Relayable,
            attachmentReach("peer-a", twoChunks, covered.copy(maxAttachBytes = budget)),
        )
    }

    @Test
    fun `one byte past a chunk boundary needs another whole sealed chunk`() {
        // Chunking is fixed-size and structural: a single extra byte buys a whole further chunk, so a
        // budget sized for exactly two chunks cannot take it.
        val twoChunksPlusOne = ScopeCrypto.ATTACH_CHUNK_BYTES * 2 + 1
        val budget = ScopeCrypto.SEALED_CHUNK_BYTES * 2
        assertEquals(
            AttachmentRelay.TooLarge,
            attachmentReach("peer-a", twoChunksPlusOne, covered.copy(maxAttachBytes = budget)),
        )
    }

    @Test
    fun `a missing attachment's second line names the connected relays only`() {
        // Work item 50's shape: two relays configured, the one that carries photos down (or parked) and
        // the frames-only one up. The line must say the connected relays carry messages only — never
        // promise the photo relay that is not there — and flip to the relay wording once it is back.
        val photoRelayDown = covered.copy(configured = 2, active = 2, connected = 1, maxAttachBytes = null)
        assertEquals(AttachmentWait.RelayFramesOnly, attachmentWait("peer-a", photoRelayDown))
        assertEquals(AttachmentWait.Relay, attachmentWait("peer-a", photoRelayDown.copy(connected = 2, maxAttachBytes = 1)))
        assertEquals(AttachmentWait.RelayFramesOnly, attachmentWait("group-1", covered.copy(maxAttachBytes = null)))
    }

    @Test
    fun `a missing attachment off the plane has nothing to add`() {
        // The room is never covered, an outage stays silent, a pending thread has no scope to wait on,
        // and the plane off is the user's own choice: in each the first line already says everything.
        assertEquals(AttachmentWait.Nearby, attachmentWait(Conversations.NEARBY, covered))
        assertEquals(AttachmentWait.Nearby, attachmentWait("peer-a", covered.copy(connected = 0)))
        assertEquals(AttachmentWait.Nearby, attachmentWait("peer-unknown", covered))
        assertEquals(AttachmentWait.Nearby, attachmentWait("peer-a", covered.copy(enabled = false)))
    }

    @Test
    fun `the default relay budget carries a maximal attachment`() {
        // 8 MiB is the app's own cap and 16 MiB the spec's default per-scope budget, so the stock
        // pairing must not mark every large photo nearby-only.
        val maximal = 8 * 1024 * 1024
        assertEquals(AttachmentRelay.Relayable, attachmentReach("peer-a", maximal, covered))
    }

    @Test
    fun `sealed size rounds up whole chunks and treats empty as zero`() {
        assertEquals(0, sealedAttachmentBytes(0))
        assertEquals(0, sealedAttachmentBytes(-1))
        assertEquals(ScopeCrypto.SEALED_CHUNK_BYTES, sealedAttachmentBytes(1))
        assertEquals(ScopeCrypto.SEALED_CHUNK_BYTES, sealedAttachmentBytes(ScopeCrypto.ATTACH_CHUNK_BYTES))
        assertEquals(ScopeCrypto.SEALED_CHUNK_BYTES * 2, sealedAttachmentBytes(ScopeCrypto.ATTACH_CHUNK_BYTES + 1))
    }

    @Test
    fun `the sealed chunk size matches the specs pinned constant`() {
        // Spec 12 pins 49221 = 1 + 12 + 40 + 49152 + 16. Derived in code, asserted here, so a change to
        // any part shows up as a failing test rather than as attachments a spool silently refuses.
        assertEquals(49_221, ScopeCrypto.SEALED_CHUNK_BYTES)
    }
}
