package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.VectorParty
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlin.random.Random

/**
 * An identity a modified client mints for free (the malicious-fork review): fresh keys from [seed], so a self-certifying
 * [profile] every node pins like any other, and frames it signs itself. Nothing stands behind it, no stack answers for
 * it: a [LabNode] floods what it signs ([LabNode.flood]), as a fork's radio would. Its frames are sealed DMs ([sealedNoise])
 * to [nodeId]s that do not exist either, so no honest node opens one; they reach custody and nothing else.
 */
class LabSybil(
    seed: Int,
    private val name: String,
) {
    private val party = Random(seed).let { VectorParty(it.nextBytes(KEY_BYTES), it.nextBytes(KEY_BYTES)) }

    val nodeId: String = party.nodeId

    /** The profile that pins this identity everywhere, published at [at]; no prekey, so nobody seals to it. */
    fun profile(at: Long): WireEnvelope =
        signed(
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = FrameId.profileId(nodeId, at),
                senderId = nodeId,
                sentAt = at,
                payload =
                    WireCodec.encodePayload(
                        ProfileContent(
                            name = name,
                            status = "",
                            pubKey = party.bundle.encoded,
                            protoVersion = Protocol.VERSION,
                            version = at,
                        ),
                    ),
            ),
        )

    /** A DM to [to] stamped [sentAt], which the fork picks: a custodial frame every carrier holds for a day. */
    fun dm(
        to: String,
        sentAt: Long,
    ): WireEnvelope =
        signed(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.new(),
                senderId = nodeId,
                sentAt = sentAt,
                recipientId = to,
                payload = WireCodec.encodePayload(ChatContent(enc = sealedNoise(Random(sentAt)))),
            ),
        )

    private fun signed(env: RelayEnvelope): WireEnvelope {
        val bytes = WireCodec.encodeEnvelope(env)
        return WireEnvelope(sig = party.sign(bytes), signed = bytes)
    }

    companion object {
        private const val KEY_BYTES = 32

        /**
         * A sealed body nobody can open: random bytes in a v1 envelope wrapped to no one. Custody carries a DM or group
         * frame only sealed (`InboundPipeline.canCarry`), and a fork pays nothing to fake the seal.
         */
        fun sealedNoise(random: Random): EncEnvelope =
            EncEnvelope(nonce = random.nextBytes(NONCE_BYTES), ct = random.nextBytes(KEY_BYTES), keys = emptyList())

        private const val NONCE_BYTES = 12
    }
}
