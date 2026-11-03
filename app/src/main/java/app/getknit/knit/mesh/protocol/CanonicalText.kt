package app.getknit.knit.mesh.protocol

import java.util.Base64
import java.util.Locale

/**
 * The canonical text forms the wire carries for bytes — a lowercase 64-hex content hash, a `g-` + 24-hex
 * group id, a 16-hex device tag, a padded standard-base64 key or key bundle — and their exact inverses. Every
 * `…OrNull` is a **round-trip check** (decode, re-encode, compare) in the [FrameId.toBytesOrNull] /
 * `NodeId.toBytesOrNull` mould: a string is accepted only when its bytes re-encode to precisely it, so a
 * codec that swaps text for bytes (the v3 sealed plaintext, the `0x05` frame transcoder) can never turn a
 * string into a different one. Pure; exercised through those codecs' tests.
 */
internal object CanonicalText {
    /** A SHA-256 content hash. */
    const val HASH_BYTES = 32

    /** The bytes behind a `g-` group id (`Conversations.groupIdFor`: the first 12 of a SHA-256). */
    const val GROUP_ID_BYTES = 12

    /** A device tag (`identity/DeviceTag`). */
    const val TAG_BYTES = 8

    /** A raw key bundle: the Ed25519 signing key ‖ the X25519 identity key (`PublicKeyBundle`'s two fields). */
    const val BUNDLE_RAW_BYTES = 64

    private const val GROUP_ID_PREFIX = "g-"
    private const val HEX_RADIX = 16
    private const val KEY_BYTES = 32
    private const val CBOR_TEXT_BASE = 0x60
    private const val CBOR_BSTR_ONE_BYTE_LENGTH = 0x58
    private const val CBOR_MAP_2 = 0xA2

    /** `PublicKeyBundle.encoded` is base64 of this fixed definite-length CBOR: `{sigPub: bstr32, hpkePub: bstr32}`. */
    private val BUNDLE_SIG_HEADER = byteArrayOf(CBOR_MAP_2.toByte()) + cborText("sigPub") + bstr32Header()
    private val BUNDLE_HPKE_HEADER = cborText("hpkePub") + bstr32Header()

    /** The 32 bytes behind a lowercase 64-hex content hash, or null when [hex] is not exactly that. */
    fun hashBytesOrNull(hex: String): ByteArray? = hexBytesOrNull(hex, HASH_BYTES)

    fun hashText(bytes: ByteArray): String {
        require(bytes.size == HASH_BYTES) { "a content hash is $HASH_BYTES bytes, got ${bytes.size}" }
        return hex(bytes)
    }

    /** The 12 bytes behind a `g-` + 24-hex group id, or null when [id] is not exactly that. */
    fun groupIdBytesOrNull(id: String): ByteArray? =
        if (id.startsWith(GROUP_ID_PREFIX)) hexBytesOrNull(id.substring(GROUP_ID_PREFIX.length), GROUP_ID_BYTES) else null

    fun groupIdText(bytes: ByteArray): String {
        require(bytes.size == GROUP_ID_BYTES) { "a group id is $GROUP_ID_BYTES bytes, got ${bytes.size}" }
        return GROUP_ID_PREFIX + hex(bytes)
    }

    /** The 8 bytes behind a 16-hex device tag, or null when [hex] is not exactly that. */
    fun hex8BytesOrNull(hex: String): ByteArray? = hexBytesOrNull(hex, TAG_BYTES)

    fun hex8Text(bytes: ByteArray): String {
        require(bytes.size == TAG_BYTES) { "a device tag is $TAG_BYTES bytes, got ${bytes.size}" }
        return hex(bytes)
    }

    /** The bytes behind a standard padded-base64 [text], or null unless they re-encode to exactly it. */
    fun base64BytesOrNull(text: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf {
            base64Text(it) ==
                text
        }

    fun base64Text(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * The 64 raw key bytes (sigPub ‖ hpkePub) behind a `PublicKeyBundle.encoded` string, or null unless it is
     * exactly the canonical base64 of the bundle's fixed CBOR framing — re-framing them yields the same string.
     */
    fun bundleRawOrNull(encoded: String): ByteArray? {
        val cbor = base64BytesOrNull(encoded) ?: return null
        val sigAt = BUNDLE_SIG_HEADER.size
        val hpkeHeaderAt = sigAt + KEY_BYTES
        val hpkeAt = hpkeHeaderAt + BUNDLE_HPKE_HEADER.size
        val framed =
            cbor.size == hpkeAt + KEY_BYTES &&
                cbor.copyOfRange(0, sigAt).contentEquals(BUNDLE_SIG_HEADER) &&
                cbor.copyOfRange(hpkeHeaderAt, hpkeAt).contentEquals(BUNDLE_HPKE_HEADER)
        return if (framed) cbor.copyOfRange(sigAt, hpkeHeaderAt) + cbor.copyOfRange(hpkeAt, cbor.size) else null
    }

    fun bundleText(raw: ByteArray): String {
        require(raw.size == BUNDLE_RAW_BYTES) { "a raw bundle is $BUNDLE_RAW_BYTES bytes, got ${raw.size}" }
        return base64Text(
            BUNDLE_SIG_HEADER + raw.copyOfRange(0, KEY_BYTES) + BUNDLE_HPKE_HEADER + raw.copyOfRange(KEY_BYTES, BUNDLE_RAW_BYTES),
        )
    }

    private fun hexBytesOrNull(
        hex: String,
        size: Int,
    ): ByteArray? {
        if (hex.length != size * 2) return null
        val bytes = runCatching { ByteArray(size) { i -> hex.substring(2 * i, 2 * i + 2).toInt(HEX_RADIX).toByte() } }.getOrNull()
        return bytes?.takeIf { hex(it) == hex }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

    /** A definite-length CBOR text item for a short ASCII [s] (≤ 23 chars: one header byte). */
    private fun cborText(s: String): ByteArray = byteArrayOf((CBOR_TEXT_BASE + s.length).toByte()) + s.encodeToByteArray()

    private fun bstr32Header(): ByteArray = byteArrayOf(CBOR_BSTR_ONE_BYTE_LENGTH.toByte(), KEY_BYTES.toByte())
}
