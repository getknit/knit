package app.getknit.knit.mesh.protocol

import java.security.SecureRandom
import java.util.Base64

/**
 * Mints a compact, globally-unique frame/request id: 128 random bits ([SecureRandom]) rendered as a
 * 22-char unpadded base64url string. Replaces `UUID.randomUUID().toString()` (36 chars — hex with
 * hyphens — for the same entropy). The id is opaque and forwarded verbatim (it is the dedup key, the
 * custody key, and what a receipt/reaction references), so only uniqueness matters; a tighter encoding
 * just trims every chat/reaction/receipt frame that carries one. base64url (`[A-Za-z0-9_-]`, no padding)
 * keeps it CBOR-text-, URL-, and filename-safe. Changing this format is *not* a wire break — every node
 * treats the id as an opaque string — as long as it stays collision-resistantly unique.
 */
object FrameId {
    private const val ID_BYTES = 16
    private val rng = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** A fresh random id. Thread-safe: [SecureRandom] and the stateless [Base64] encoder allow concurrent use. */
    fun new(): String = encoder.encodeToString(ByteArray(ID_BYTES).also { rng.nextBytes(it) })
}
