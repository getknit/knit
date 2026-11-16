package app.getknit.knit.mesh.crypto

import app.getknit.knit.TextLimits
import app.getknit.knit.clampToUtf16
import app.getknit.knit.identity.NodeId
import app.getknit.knit.normalizeSingleLine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.util.Base64

/**
 * The **contact card** — a device's identity as a signed, shareable string, so two people who can only
 * pass each other a short text out of band (SMS, e-mail, a call, another messenger) can pin each other's
 * key without a camera or a radio in range (docs/CONTACT_CARD.md). It is the QR payload's job done at a
 * distance: the card carries the same self-certifying [PublicKeyBundle] the `knit-id:v1` code does, plus
 * a display name and, optionally, the relays its owner uses.
 *
 * Layout: `Signed { body: bstr, sig: bstr }` where `body` is the CBOR of [Body] and `sig` is a raw Ed25519
 * signature over [SIGNING_LABEL] `‖ body` under the identity signing key inside the card. The body bytes
 * stay **opaque** end to end — a parser verifies the signature over exactly the bytes it received and
 * never re-encodes them — the `WireEnvelope.signed`/`sig` discipline (docs/WIRE_COMPAT.md rule 4), so a
 * field added to [Body] later is additive under `ignoreUnknownKeys`. The card is self-certifying like a
 * profile frame: the node id *is* the hash of the bundle it carries, so a forged card for someone else's
 * id is a hash collision, and the signature stops anyone editing a genuine card's name or relay list.
 *
 * Text forms, all accepted by [parse]: the App Link `https://getknit.app/c#<card>` (the card rides the
 * fragment, which a browser never sends to the server), the scheme link `knit://c/<card>`, the bare
 * base64url card, and the legacy QR string `knit-id:v1:<nodeId>:<bundle>`. A link is found anywhere in
 * pasted text ("Add me on Knit: https://…"), since that is how it arrives.
 *
 * Pure and Android-free (JVM-tested with golden vectors in `ContactCardTest`); [parse] never throws.
 */
object ContactCard {
    /** Card layout version — bump only for a change `ignoreUnknownKeys` cannot absorb. */
    const val VERSION = 1

    const val URL_PREFIX = "https://getknit.app/c#"
    const val SCHEME_PREFIX = "knit://c/"

    /** Refused before decoding: bounds the work a hostile paste can cause (a real card is ~180–260 bytes). */
    const val MAX_BYTES = 512

    /** Relay hints a card may carry — enough for a self-hoster's primary plus a fallback, never a list. */
    const val MAX_SPOOLS = 3

    /** The signing domain: keeps a card signature from ever being confused with a frame or prekey signature. */
    private const val SIGNING_LABEL = "knit/card/v1"

    /** The signed body: the identity, its presentation, and the optional relay hints. */
    @Serializable
    @OptIn(ExperimentalSerializationApi::class) // @ByteString is an experimental kotlinx API
    private class Body(
        val v: Int,
        val id: String,
        @ByteString val pk: ByteArray,
        val name: String? = null,
        val sp: List<String>? = null,
        val iat: Long = 0L,
    )

    /** The outer wrapper: opaque signed bytes plus the raw Ed25519 signature over them. */
    @Serializable
    @OptIn(ExperimentalSerializationApi::class) // @ByteString is an experimental kotlinx API
    private class Signed(
        @ByteString val body: ByteArray,
        @ByteString val sig: ByteArray,
    )

    /** What [parse] yields. */
    sealed interface Parsed {
        /**
         * A valid card: the self-certifying id and its [bundle] in the wire/pin form (`PublicKeyBundle.encoded`
         * — what `PeerEntity.pubKey` stores and the safety number hashes), the owner's name, relay hints, and
         * when it was issued.
         */
        data class Card(
            val nodeId: String,
            val bundle: String,
            val name: String,
            val spools: List<String>,
            val issuedAt: Long,
        ) : Parsed

        data class Invalid(
            val reason: Reason,
        ) : Parsed
    }

    enum class Reason {
        /** Nothing card-shaped in the text at all (the common "pasted the wrong thing" case). */
        NOT_A_CARD,
        TOO_LARGE,
        MALFORMED,
        BAD_VERSION,
        BAD_ID,
        BAD_KEY,
        ID_MISMATCH,
        BAD_SIGNATURE,
    }

    /**
     * Encodes and signs a card as its bare base64url form (no padding). [sign] is the identity's raw
     * Ed25519 signer (`MessageCrypto.signRaw`); the card is only ever minted for one's own identity.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(
        bundle: PublicKeyBundle,
        name: String?,
        spools: List<String>,
        issuedAt: Long,
        sign: (ByteArray) -> ByteArray,
    ): String {
        val body =
            Body(
                v = VERSION,
                id = NodeId.fromPublicKeyBundle(bundle.encoded),
                pk = bundle.sigPublicKey() + bundle.dhPublicKey(),
                name = name?.let { clampName(it) }?.takeIf { it.isNotEmpty() },
                sp = spools.take(MAX_SPOOLS).takeIf { it.isNotEmpty() },
                iat = issuedAt,
            )
        val bodyBytes = cryptoCbor.encodeToByteArray(body)
        val signed = Signed(body = bodyBytes, sig = sign(SIGNING_LABEL.toByteArray() + bodyBytes))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cryptoCbor.encodeToByteArray(signed))
    }

    /** The App Link form of a [compact] card. */
    fun url(compact: String): String = URL_PREFIX + compact

    /** The custom-scheme form of a [compact] card. */
    fun schemeUrl(compact: String): String = SCHEME_PREFIX + compact

    /**
     * Parses any accepted text form. Never throws: every decode step is caught and mapped to a
     * [Reason], because this is fed straight from the clipboard, a share intent, or a scanned code.
     */
    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.startsWith(LEGACY_PREFIX)) return parseLegacy(trimmed)
        val compact = extractCompact(trimmed) ?: return Parsed.Invalid(Reason.NOT_A_CARD)
        // A bare run shorter than any card is a word or an id, not a card someone mangled.
        if (!hasLinkPrefix(trimmed) && compact.length < MIN_BARE_CHARS) return Parsed.Invalid(Reason.NOT_A_CARD)
        if (compact.length > MAX_COMPACT_CHARS) return Parsed.Invalid(Reason.TOO_LARGE)
        val raw = runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull() ?: return Parsed.Invalid(Reason.MALFORMED)
        if (raw.size > MAX_BYTES) return Parsed.Invalid(Reason.TOO_LARGE)
        return decodeSigned(raw)
    }

    /**
     * The cheap "is this a card at all" probe for an incoming intent: one of the link forms anywhere in
     * the text, the legacy code, or a bare card of plausible length. Deliberately stricter than [parse]'s
     * bare form — a shared word or a node id must not read as a card and hijack the share flow. A bare run
     * is only checked for its alphabet and length, so text another app shares goes through [isSharedCard].
     */
    fun looksLikeCard(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith(LEGACY_PREFIX)) return true
        if (hasLinkPrefix(trimmed)) return extractCompact(trimmed) != null
        return trimmed.length >= MIN_BARE_CHARS && extractCompact(trimmed) != null
    }

    /**
     * Whether text another app shared is a card to import rather than a message. A link form or the legacy
     * code counts as soon as it [looksLikeCard], so a damaged link still reaches Add contact, which names
     * what is wrong with it. A bare run counts only when [parse] accepts it: an API key, a digest or an
     * encoded blob is in the same alphabet, and taking one from the share flow leaves it no way into a chat
     * (#303).
     */
    fun isSharedCard(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith(LEGACY_PREFIX) || hasLinkPrefix(trimmed)) return looksLikeCard(trimmed)
        return looksLikeCard(trimmed) && parse(trimmed) is Parsed.Card
    }

    private fun hasLinkPrefix(text: String): Boolean = text.contains(URL_PREFIX) || text.contains(SCHEME_PREFIX)

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeSigned(raw: ByteArray): Parsed {
        val signed = runCatching { cryptoCbor.decodeFromByteArray<Signed>(raw) }.getOrNull() ?: return Parsed.Invalid(Reason.MALFORMED)
        val body = runCatching { cryptoCbor.decodeFromByteArray<Body>(signed.body) }.getOrNull() ?: return Parsed.Invalid(Reason.MALFORMED)
        if (body.v != VERSION) return Parsed.Invalid(Reason.BAD_VERSION)
        if (!isNodeId(body.id)) return Parsed.Invalid(Reason.BAD_ID)
        if (body.pk.size != 2 * PublicKeyBundle.RAW_KEY_BYTES) return Parsed.Invalid(Reason.BAD_KEY)
        val bundle =
            PublicKeyBundle.fromRaw(
                sigPub = body.pk.copyOfRange(0, PublicKeyBundle.RAW_KEY_BYTES),
                hpkePub = body.pk.copyOfRange(PublicKeyBundle.RAW_KEY_BYTES, 2 * PublicKeyBundle.RAW_KEY_BYTES),
            ) ?: return Parsed.Invalid(Reason.BAD_KEY)
        if (NodeId.fromPublicKeyBundle(bundle.encoded) != body.id) return Parsed.Invalid(Reason.ID_MISMATCH)
        if (!MessageCrypto.verify(
                bundle,
                signed.sig,
                SIGNING_LABEL.toByteArray() + signed.body,
            )
        ) {
            return Parsed.Invalid(Reason.BAD_SIGNATURE)
        }
        return Parsed.Card(
            nodeId = body.id,
            bundle = bundle.encoded,
            name = body.name?.let { clampName(it) }.orEmpty(),
            spools = body.sp.orEmpty().take(MAX_SPOOLS),
            issuedAt = body.iat,
        )
    }

    /**
     * The QR string: no name, no relays, no signature beyond the bundle's own self-certification — the
     * exact check the scanner always applied (the id must be the hash of the bundle string).
     */
    private fun parseLegacy(text: String): Parsed {
        val parsed = VerifyPayload.parse(text) ?: return Parsed.Invalid(Reason.MALFORMED)
        if (!isNodeId(parsed.nodeId)) return Parsed.Invalid(Reason.BAD_ID)
        if (NodeId.fromPublicKeyBundle(parsed.bundle) != parsed.nodeId) return Parsed.Invalid(Reason.ID_MISMATCH)
        return Parsed.Card(nodeId = parsed.nodeId, bundle = parsed.bundle, name = "", spools = emptyList(), issuedAt = 0L)
    }

    /**
     * Finds the compact card in [text]: after the first link prefix if one is present anywhere (people
     * paste whole sentences), else the whole text when it is a plain base64url run.
     */
    private fun extractCompact(text: String): String? {
        for (prefix in listOf(URL_PREFIX, SCHEME_PREFIX)) {
            val at = text.indexOf(prefix)
            if (at >= 0) {
                val run = text.substring(at + prefix.length).takeWhile { it in COMPACT_ALPHABET }
                return run.takeIf { it.isNotEmpty() }
            }
        }
        return text.takeIf { it.isNotEmpty() && it.all { c -> c in COMPACT_ALPHABET } }
    }

    private fun isNodeId(id: String): Boolean = id.length == NodeId.LENGTH && id.all { it in NodeId.ALPHABET }

    private fun clampName(name: String): String = normalizeSingleLine(name).clampToUtf16(TextLimits.DISPLAY_NAME)

    private const val LEGACY_PREFIX = "knit-id:v1:"
    private const val COMPACT_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_="

    /** base64url of [MAX_BYTES] with padding headroom — checked before decoding so the decoder never sees more. */
    private const val MAX_COMPACT_CHARS = (MAX_BYTES + 2) / 3 * 4

    /** A bare card is never shorter than this (a minimal card is ~180 bytes ≈ 240 chars). */
    private const val MIN_BARE_CHARS = 200
}
