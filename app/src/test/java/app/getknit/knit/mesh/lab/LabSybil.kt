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

    /**
     * The profile that pins this identity everywhere, published at [at]; no prekey, so nobody seals to it. With
     * [signedBytes], its status is padded so the frame's `signed` is exactly that long (the profile cap, #234).
     */
    fun profile(
        at: Long,
        signedBytes: Int? = null,
    ): WireEnvelope {
        fun frame(pad: Int) =
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = FrameId.profileId(nodeId, at),
                senderId = nodeId,
                sentAt = at,
                payload =
                    WireCodec.encodePayload(
                        ProfileContent(
                            name = name,
                            status = "x".repeat(pad),
                            pubKey = party.bundle.encoded,
                            protoVersion = Protocol.VERSION,
                            version = at,
                        ),
                    ),
            )
        if (signedBytes == null) return signed(frame(0))
        // A text string's length prefix grows at 24 and 256, so a step can overshoot by its width: walk back to the size.
        var pad = maxOf(0, signedBytes - WireCodec.encodeEnvelope(frame(0)).size)
        while (pad > 0 && WireCodec.encodeEnvelope(frame(pad)).size > signedBytes) pad--
        val env = frame(pad)
        check(WireCodec.encodeEnvelope(env).size == signedBytes) { "no status length makes a $signedBytes-byte profile" }
        return signed(env)
    }

    /**
     * A DM to [to] stamped [sentAt], which the fork picks: a custodial frame every carrier holds for a day. With
     * [signedBytes], its noise is sized so the frame's `signed` is exactly that long (custody's byte cap, #227).
     */
    fun dm(
        to: String,
        sentAt: Long,
        signedBytes: Int? = null,
    ): WireEnvelope {
        val id = FrameId.new()

        fun frame(ct: Int) =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = nodeId,
                sentAt = sentAt,
                recipientId = to,
                payload = WireCodec.encodePayload(ChatContent(enc = sealedNoise(Random(sentAt), ct))),
            )
        if (signedBytes == null) return signed(frame(KEY_BYTES))
        // A byte string's length prefix grows at 24 and 256, so a step can overshoot by its width: walk back to the size.
        var ct = maxOf(0, signedBytes - WireCodec.encodeEnvelope(frame(0)).size)
        while (ct > 0 && WireCodec.encodeEnvelope(frame(ct)).size > signedBytes) ct--
        val env = frame(ct)
        check(WireCodec.encodeEnvelope(env).size == signedBytes) { "no noise length makes a $signedBytes-byte frame" }
        return signed(env)
    }

    /** A Nearby-room post of [body] stamped [sentAt]: cleartext, delivered to everyone who hears it, carried for six hours. */
    fun room(
        body: String,
        sentAt: Long,
    ): WireEnvelope =
        signed(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.new(),
                senderId = nodeId,
                sentAt = sentAt,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            ),
        )

    private fun signed(env: RelayEnvelope): WireEnvelope {
        val bytes = WireCodec.encodeEnvelope(env)
        return WireEnvelope(sig = party.sign(bytes), signed = bytes)
    }

    companion object {
        private const val KEY_BYTES = 32

        /**
         * A sealed body nobody can open: [ctBytes] random bytes in a v1 envelope wrapped to no one. Custody carries a DM or
         * group frame only sealed (`InboundPipeline.canCarry`), and a fork pays nothing to fake the seal.
         */
        fun sealedNoise(
            random: Random,
            ctBytes: Int = KEY_BYTES,
        ): EncEnvelope = EncEnvelope(nonce = random.nextBytes(NONCE_BYTES), ct = random.nextBytes(ctBytes), keys = emptyList())

        private const val NONCE_BYTES = 12
    }
}
