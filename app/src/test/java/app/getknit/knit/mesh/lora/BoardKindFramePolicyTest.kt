package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.lora.meshcore.ContactRecord
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * ADR 2026-10.jend's own invariant, carried by ADR 2026-11.gsqu (its closing list, built in the Stage 5c-1 amendment):
 * every frame type, in every addressing, is classified on each board kind as **flood** (any repeater may air it again),
 * **zero-hop** (it reaches the next radio only) or **refused** (it never airs). On a MeshCore board only an own
 * Nearby-room post floods; a compact DM, its ✓✓ and a DM reaction are zero-hop, pinned on both boards; everything else is
 * refused. A frame type added to [FrameType] fails this test until it is classified here.
 *
 * What decides, per kind: the fan-out's gate ([LoraFramePolicy.eligible], the one predicate both kinds share) and the
 * room's [BoardTraits.roomCeiling]; and for a compact DM, the kind's carrier — on Meshtastic a `hop_limit`
 * [LoraMeshTransport.HOP_LIMIT] PKI unicast every router may re-air (`MeshtasticBoardLink`), on MeshCore a contact text
 * DM the session writes only after a zero-hop pin read back on this connection (`MeshcoreSession`, its tests).
 */
class BoardKindFramePolicyTest {
    private enum class Air { FLOOD, ZERO_HOP, REFUSED }

    /** Every [FrameType] constant, read off the object so a new one cannot be missed. */
    private val frameTypes: List<String> =
        FrameType::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }

    private enum class Form { ROOM, DM, GROUP }

    private fun env(
        type: String,
        form: Form,
    ) = RelayEnvelope(
        type = type,
        id = "id",
        senderId = "alice",
        sentAt = 0L,
        recipientId = if (form == Form.DM) "bob" else null,
        group = if (form == Form.GROUP) GroupInfo(id = "g-x", members = listOf("alice", "bob"), createdBy = "alice") else null,
        payload = ByteArray(0),
    )

    /** What the plane does with a mesh frame on [traits]' kind: the fan-out's gate, then the room's ceiling. */
    private fun fanout(
        traits: BoardTraits,
        env: RelayEnvelope,
    ): Air =
        when {
            !LoraFramePolicy.eligible(env) -> Air.REFUSED

            // A room post is a broadcast on the bound slot (Meshtastic, hop_limit 3) or a scoped GRP_DATA flood (MeshCore,
            // admitted under the flood ceiling): every repeater may re-air it on either kind.
            traits.roomCeiling == Ceiling.FLOOD || traits.kind == BoardKind.MESHTASTIC -> Air.FLOOD

            else -> error("a room post on ${traits.kind} with no carrier")
        }

    /**
     * What becomes of a compact DM — any [DirectKind]: they share one carrier — on [traits]' kind, read off the production
     * fact that decides it: Meshtastic's unicast `hop_limit`; on MeshCore the route of the contact record Knit teaches and
     * re-pins ([ContactRecord.knit], [ContactRecord.pinned]), whose `out_path_len` 0 is what keeps the firmware from
     * flooding the DM and its ack.
     */
    private fun direct(traits: BoardTraits): Air =
        when (traits.kind) {
            BoardKind.MESHTASTIC -> {
                if (LoraMeshTransport.HOP_LIMIT > 0) Air.FLOOD else Air.ZERO_HOP
            }

            BoardKind.MESHCORE -> {
                val taught = ContactRecord.knit(ByteArray(MeshcoreProto.KEY_BYTES) { 1 }, "Knit 0101")
                if (taught.outPathLen == 0 && taught.pinned().outPathLen == 0) Air.ZERO_HOP else Air.FLOOD
            }
        }

    /**
     * The table, the same on both kinds: a Nearby-room chat floods; DM-form frames ride compact or not at all (ADR
     * 2026-10.x9fa), and nothing else is carried (ADR 2026-10.rp3s).
     */
    private fun expected(
        type: String,
        form: Form,
    ): Air = if (type == FrameType.CHAT && form == Form.ROOM) Air.FLOOD else Air.REFUSED

    @Test
    fun everyFrameTypeIsFloodZeroHopOrRefusedOnEachKind() {
        assertTrue("the type list was read (${frameTypes.size})", TYPES_AT_LEAST <= frameTypes.size)
        for (traits in listOf(BoardTraits.MESHTASTIC, MeshcoreTraits)) {
            for (type in frameTypes) {
                for (form in Form.entries) {
                    assertEquals("$type as $form on ${traits.kind}", expected(type, form), fanout(traits, env(type, form)))
                }
            }
        }
    }

    @Test
    fun onMeshcoreOnlyAnOwnRoomPostFloodsAndEveryCompactDmIsZeroHop() {
        val floods =
            frameTypes.flatMap { type -> Form.entries.map { type to it } }.filter { (type, form) ->
                fanout(MeshcoreTraits, env(type, form)) == Air.FLOOD
            }
        assertEquals(listOf(FrameType.CHAT to Form.ROOM), floods)
        assertEquals(Ceiling.FLOOD, MeshcoreTraits.roomCeiling)
        val kinds = DirectKind.entries.joinToString()
        assertEquals("a compact DM ($kinds, one carrier) on MeshCore", Air.ZERO_HOP, direct(MeshcoreTraits))
        assertEquals("a compact DM ($kinds) on Meshtastic, a hop_limit-3 unicast", Air.FLOOD, direct(BoardTraits.MESHTASTIC))
        assertEquals("Meshtastic's room keeps the whole allowance", Ceiling.FULL, BoardTraits.MESHTASTIC.roomCeiling)
    }

    private companion object {
        /** The types this test was written against, `chat` to `link`. */
        const val TYPES_AT_LEAST = 15
    }
}
