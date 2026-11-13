package app.getknit.knit.mesh

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.protocol.Protocol

/**
 * Whether a pinned peer's profile claims the compact-DM door of the board kind a DM is about to go through (ADR 2026-11.gsqu,
 * Stage 4's owed list, built in Stage 5a-2): the kind `LoraDirectRouter.sinkFor` answered, never the Meshtastic bit alone.
 * - **Meshtastic:** [Protocol.CAP_LORA_DIRECT] — without it their board would ack a packet their app throws away.
 * - **MeshCore:** a stored `meshcoreKey`, the sealed-only field whose presence is the claim (there is no bit). It is stored
 *   only once checked (`MeshcoreKey`) by the sealed writer, so a key is never read here unchecked.
 *
 * Asked once per offer, for the compact copy of a DM, a reaction and every compact ✓✓ alike (`offerLoraTicks` goes through
 * the same offer).
 */
internal object LoraDoorClaim {
    fun claims(
        kind: BoardKind,
        peer: PeerEntity,
    ): Boolean =
        when (kind) {
            BoardKind.MESHTASTIC -> (peer.capabilities ?: 0L) and Protocol.CAP_LORA_DIRECT != 0L
            BoardKind.MESHCORE -> peer.meshcoreKey != null
        }
}
