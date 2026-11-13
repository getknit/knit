package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Whether a room post heard over LoRa was written by the author it names: its Ed25519 signature verifies against that
 * author's **pinned** key, which hashes to their node id (ADR 2026-11.gsqu's Stage 5 decisions, the security design pass's
 * B6). What a kind whose packets name no sender it vouches for (MeshCore) keys a hearing on — never a sender prefix, and
 * never a key the frame brought itself. A stranger's post, with no pinned key, never verifies.
 *
 * The router verifies the same frame again on its own path, whatever this answers; this only decides whether the post
 * is a hearing of its author's radio. `di/MeshModule` binds it to the peer table. Never throws a refusal: false.
 */
internal fun interface LoraAuthorCheck {
    suspend fun verified(
        wire: WireEnvelope,
        env: RelayEnvelope,
    ): Boolean

    companion object {
        /** Verifies nothing: no hearing is ever keyed on an author. */
        val None = LoraAuthorCheck { _, _ -> false }
    }
}
