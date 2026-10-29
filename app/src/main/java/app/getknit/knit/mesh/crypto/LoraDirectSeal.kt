package app.getknit.knit.mesh.crypto

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.lora.LORA_DIRECT_OVERHEAD
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LoraDirectCrypto
import app.getknit.knit.mesh.lora.LoraDirectOpen
import app.getknit.knit.mesh.lora.OpenedDirect
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.Protocol
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * The pair seal of a compact LoRa DM (ADR 2026-10.x9fa): the crypto half of [LoraDirectCrypto]. One packet is
 *
 * ```
 * tag 0x11 | pairTag 4 | salt 8 | frameId 16 | sentAt u48 6 | AES-256-GCM(kDir, nonce, plaintext, aad) + 16
 * ```
 *
 * keyed by the **static** identity agreement `X25519(our identity key, their identity key)` — the pair secret the
 * spool's pair scopes use (`ScopeCrypto.pairSecret`, docs/SPOOL_PROTOCOL.md C-3.5-5), under its own labels so the two
 * families never meet. No forward secrecy on this leg: the maintainer accepted a static pairwise key for one packet a
 * message, and the ratchet still seals the signed copy every other plane carries.
 *
 * Every key is **directional** — `info = label ‖ senderId ‖ recipientId` over the 16-byte canonical node ids (#178) —
 * so a packet reflected back at its author finds no tag in its index, and a forged one under a colliding tag fails
 * the AEAD. The nonce comes from its own key ([LABEL_NONCE]), never from `kDir`, and a fresh 8-byte salt per seal, so
 * a resend of one frame id never reuses a nonce. The AAD is the 35 header bytes and both node ids sender first.
 *
 * The **trust boundary**: the sender is never read off the packet — the pairTag picks candidates out of an index over
 * our pinned peers (never ourselves, 45763fb6), and only a candidate whose key authenticates it names the sender. A
 * packet that authenticates outside the replay window ([open]'s [LoraDirectOpen.ClockRefused]) is refused here, so
 * delivery never sees a stale one. Nothing here feeds the ratchet's reset heuristic, and nothing here is a
 * `WireEnvelope`.
 *
 * Pure apart from [ownDhPrivate] (the identity key store's read) and [random]; the index is rebuilt by the caller off
 * the peer table ([rebuild]) and topped up for a peer pinned since ([learn]). Keys are derived once per (peer, key) and
 * held until that peer's key or our own id changes.
 */
internal class LoraDirectSeal(
    /** Our raw X25519 identity scalar (`IdentityKeyStore.dhIdentityPrivate`), read only when a pair's keys are derived. */
    private val ownDhPrivate: () -> ByteArray,
    /** The wall clock the replay window is judged against — our own clock, the sender's `sentAt` against it. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Fills a salt; a test injects fixed bytes, production draws from [SecureRandom]. */
    private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
) : LoraDirectCrypto {
    /** The keys one pinned peer shares with us, both directions, derived from one identity agreement. */
    private class PairKeys(
        val peerId: String,
        val peerIdBytes: ByteArray,
        /** The peer's X25519 identity key these were derived from: a change re-derives them. */
        val peerDhPub: ByteArray,
        val sendKey: ByteArray,
        val sendNonceKey: ByteArray,
        val sendTag: ByteArray,
        val recvKey: ByteArray,
        val recvNonceKey: ByteArray,
        val recvTag: Int,
    )

    /** One generation of the index: our own id, every pair by peer id, and the inbound tags they answer to. */
    private class Index(
        val selfId: String,
        val selfIdBytes: ByteArray,
        val pairs: Map<String, PairKeys>,
    ) {
        /** Inbound pairTag → every pair whose tag it is: a collision returns them all and the AEAD decides. */
        val byTag: Map<Int, List<PairKeys>> = pairs.values.groupBy { it.recvTag }
    }

    @Volatile
    private var index: Index? = null

    /**
     * Replaces the index with [pinned] (peer id → that peer's X25519 identity key, as pinned), keeping every pair whose
     * key did not change. Our own id is never indexed: a node never holds a pair with itself (45763fb6). A key X25519
     * refuses (a low-order point, FSR §2.1), or an id that is not canonical, leaves that peer out.
     */
    @Synchronized
    fun rebuild(
        selfId: String,
        pinned: Map<String, ByteArray>,
    ) {
        val selfBytes = NodeId.toBytesOrNull(selfId) ?: return
        val previous = index?.takeIf { it.selfId == selfId }?.pairs.orEmpty()
        val pairs = HashMap<String, PairKeys>(pinned.size)
        var own: ByteArray? = null
        for ((peerId, dhPub) in pinned) {
            if (peerId == selfId) continue
            val kept = previous[peerId]?.takeIf { it.peerDhPub.contentEquals(dhPub) }
            val pair = kept ?: derive(selfId, selfBytes, own ?: ownDhPrivate().also { own = it }, peerId, dhPub)
            pair?.let { pairs[peerId] = it }
        }
        index = Index(selfId, selfBytes, pairs)
    }

    /**
     * Adds or refreshes one pair ahead of the next [rebuild] — a peer pinned a moment ago, whose first compact DM must not
     * wait for the peer table's flow to come round. A no-op until the first [rebuild] names our own id, and for a pair
     * already held under [dhPub].
     */
    @Synchronized
    fun learn(
        peerId: String,
        dhPub: ByteArray,
    ) {
        val current = index ?: return
        if (peerId == current.selfId) return
        if (current.pairs[peerId]?.peerDhPub?.contentEquals(dhPub) == true) return
        val pair = derive(current.selfId, current.selfIdBytes, ownDhPrivate(), peerId, dhPub) ?: return
        index = Index(current.selfId, current.selfIdBytes, current.pairs + (peerId to pair))
    }

    /** Whether [peerId] is in the pair index now — for the `mesh/lab` harness, which waits on the index's hand-off. */
    internal fun knows(peerId: String): Boolean = index?.pairs?.containsKey(peerId) == true

    override fun seal(
        peerId: String,
        frameId: String,
        sentAtMs: Long,
        plaintext: ByteArray,
    ): ByteArray? {
        val current = index ?: return null
        val pair = current.pairs[peerId] ?: return null
        val salt = ByteArray(SALT_BYTES).also(random)
        return sealWith(current, pair, frameId, sentAtMs, plaintext, salt)
    }

    /** [seal] with a caller's [salt]: the vectors' fixed draw. Null for no pair, a non-canonical id or an unstampable time. */
    internal fun sealWithSalt(
        peerId: String,
        frameId: String,
        sentAtMs: Long,
        plaintext: ByteArray,
        salt: ByteArray,
    ): ByteArray? {
        val current = index ?: return null
        val pair = current.pairs[peerId] ?: return null
        return sealWith(current, pair, frameId, sentAtMs, plaintext, salt)
    }

    private fun sealWith(
        current: Index,
        pair: PairKeys,
        frameId: String,
        sentAtMs: Long,
        plaintext: ByteArray,
        salt: ByteArray,
    ): ByteArray? {
        require(salt.size == SALT_BYTES) { "a compact DM's salt is $SALT_BYTES bytes" }
        val id = FrameId.toBytesOrNull(frameId) ?: return null
        if (sentAtMs !in 0..MAX_SENT_AT) return null
        val header =
            ByteArray(HEADER_BYTES).also { h ->
                h[0] = LORA_DIRECT_TAG
                pair.sendTag.copyInto(h, TAG_AT)
                salt.copyInto(h, SALT_AT)
                id.copyInto(h, FRAME_ID_AT)
                writeU48(h, SENT_AT_AT, sentAtMs)
            }
        val nonce = nonce(pair.sendNonceKey, id, salt)
        val aad = header + current.selfIdBytes + pair.peerIdBytes
        val (_, ct) = AesGcm.encrypt(pair.sendKey, plaintext, aad, nonce)
        return header + ct
    }

    override fun open(packet: ByteArray): LoraDirectOpen {
        if (packet.size < LORA_DIRECT_OVERHEAD || packet[0] != LORA_DIRECT_TAG) return LoraDirectOpen.Malformed
        val current = index ?: return LoraDirectOpen.Stranger
        val candidates = current.byTag[readInt(packet, TAG_AT)] ?: return LoraDirectOpen.Stranger
        val header = packet.copyOfRange(0, HEADER_BYTES)
        val salt = packet.copyOfRange(SALT_AT, SALT_AT + SALT_BYTES)
        val id = packet.copyOfRange(FRAME_ID_AT, FRAME_ID_AT + FrameId.ID_BYTES)
        val sentAt = readU48(packet, SENT_AT_AT)
        val ct = packet.copyOfRange(HEADER_BYTES, packet.size)
        for (pair in candidates) {
            val aad = header + pair.peerIdBytes + current.selfIdBytes
            val plaintext =
                try {
                    AesGcm.decrypt(pair.recvKey, nonce(pair.recvNonceKey, id, salt), ct, aad)
                } catch (_: GeneralSecurityException) {
                    continue
                }
            if (!inWindow(sentAt)) return LoraDirectOpen.ClockRefused
            return LoraDirectOpen.Opened(OpenedDirect(pair.peerId, FrameId.fromBytes(id), sentAt, plaintext))
        }
        return LoraDirectOpen.AuthFailed
    }

    /**
     * The replay window (ADR 2026-10.x9fa, Crypto 2): no older than custody's TTL, no further ahead than the skew any
     * signed frame is allowed. The sender's clock against ours, bounded on both sides — a captured packet replays for
     * at most a day, and the `lora_direct_seen` table that refuses a replay outlives that day.
     */
    private fun inWindow(sentAt: Long): Boolean {
        val now = clock()
        return sentAt >= now - WINDOW_MS && sentAt <= now + Protocol.MAX_FUTURE_SKEW_MS
    }

    private fun derive(
        selfId: String,
        selfIdBytes: ByteArray,
        ownPriv: ByteArray,
        peerId: String,
        peerDhPub: ByteArray,
    ): PairKeys? {
        if (peerId == selfId) return null
        val peerIdBytes = NodeId.toBytesOrNull(peerId) ?: return null
        val secret =
            try {
                X25519.computeSharedSecret(ownPriv, peerDhPub)
            } catch (_: GeneralSecurityException) {
                return null
            }
        val out = keys(secret, selfIdBytes, peerIdBytes)
        val back = keys(secret, peerIdBytes, selfIdBytes)
        return PairKeys(
            peerId = peerId,
            peerIdBytes = peerIdBytes,
            peerDhPub = peerDhPub.copyOf(),
            sendKey = out.key,
            sendNonceKey = out.nonceKey,
            sendTag = out.tag,
            recvKey = back.key,
            recvNonceKey = back.nonceKey,
            recvTag = readInt(back.tag, 0),
        )
    }

    companion object {
        /** The 8-byte salt drawn fresh for every seal (Crypto 8): two seals of one frame id never share a nonce. */
        const val SALT_BYTES = 8

        /** The header the AAD covers: tag, pairTag, salt, frame id and the 48-bit stamp — 35 bytes. */
        const val HEADER_BYTES = 1 + 4 + SALT_BYTES + FrameId.ID_BYTES + 6

        /** The replay window's lower edge: custody's TTL, the longest any copy of a frame is still being served. */
        val WINDOW_MS: Long = ForwardRepository.DEFAULT_TTL_MS

        private const val TAG_AT = 1
        private const val TAG_BYTES = 4
        private const val SALT_AT = TAG_AT + TAG_BYTES
        private const val FRAME_ID_AT = SALT_AT + SALT_BYTES
        private const val SENT_AT_AT = FRAME_ID_AT + FrameId.ID_BYTES
        private const val U48_BYTES = 6
        private const val KEY_BYTES = 32
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFF
        private const val MAX_SENT_AT = (1L shl 48) - 1
        private const val MAC = "HMACSHA256"
        private val ZERO_SALT = ByteArray(KEY_BYTES)

        // The `knit/lora/v1/…` family (docs/SPOOL_PROTOCOL.md C-3.5-5): disjoint from the pair scopes' `knit/scope/v1/…`
        // and the ratchet's `knit/dm/v2/…`, so the one identity agreement keys each without the two ever meeting.
        private val LABEL_KEY = "knit/lora/v1/key".toByteArray()
        private val LABEL_NONCE = "knit/lora/v1/nonce".toByteArray()
        private val LABEL_TAG = "knit/lora/v1/tag".toByteArray()

        /** One direction's three derivations. */
        internal class DirectionKeys(
            val key: ByteArray,
            val nonceKey: ByteArray,
            val tag: ByteArray,
        )

        /** The keys for [senderIdBytes] → [recipientIdBytes] off the pair [secret]; `info = label ‖ sender ‖ recipient`. */
        internal fun keys(
            secret: ByteArray,
            senderIdBytes: ByteArray,
            recipientIdBytes: ByteArray,
        ): DirectionKeys {
            val context = senderIdBytes + recipientIdBytes
            return DirectionKeys(
                key = Hkdf.computeHkdf(MAC, secret, ZERO_SALT, LABEL_KEY + context, KEY_BYTES),
                nonceKey = Hkdf.computeHkdf(MAC, secret, ZERO_SALT, LABEL_NONCE + context, KEY_BYTES),
                tag = Hkdf.computeHkdf(MAC, secret, ZERO_SALT, LABEL_TAG + context, TAG_BYTES),
            )
        }

        /** The GCM nonce for one seal: `HKDF(kNonce, frameId ‖ salt)`, its own key, never `kDir` fed back in. */
        internal fun nonce(
            nonceKey: ByteArray,
            frameId: ByteArray,
            salt: ByteArray,
        ): ByteArray = Hkdf.computeHkdf(MAC, nonceKey, ZERO_SALT, frameId + salt, AesGcm.IV_BYTES)

        private fun readInt(
            bytes: ByteArray,
            at: Int,
        ): Int {
            var v = 0
            for (i in 0 until TAG_BYTES) v = (v shl BYTE_BITS) or (bytes[at + i].toInt() and BYTE_MASK)
            return v
        }

        private fun writeU48(
            into: ByteArray,
            at: Int,
            value: Long,
        ) {
            for (i in 0 until U48_BYTES) into[at + i] = (value ushr (BYTE_BITS * (U48_BYTES - 1 - i))).toByte()
        }

        private fun readU48(
            bytes: ByteArray,
            at: Int,
        ): Long {
            var v = 0L
            for (i in 0 until U48_BYTES) v = (v shl BYTE_BITS) or (bytes[at + i].toLong() and BYTE_MASK.toLong())
            return v
        }
    }
}
