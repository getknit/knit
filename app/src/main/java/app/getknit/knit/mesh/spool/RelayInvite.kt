package app.getknit.knit.mesh.spool

import app.getknit.knit.TextLimits
import app.getknit.knit.clampToUtf16
import app.getknit.knit.mesh.crypto.cryptoCbor
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.normalizeSingleLine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.util.Base64

/**
 * The **relay invite** — one relay as a shareable link, so an operator (or a member) hands a newcomer
 * everything the Internet-relays screen would otherwise ask them to retype: the spool URL, its `?k=`
 * bearer token when the spool is private (spec §7.1), and, when the spool runs a commons (§7.4), the
 * room's 32-byte secret and name (docs/RELAY_INVITE.md). Tapping it raises one sheet that names the host
 * and applies the lot on one confirmation — never silently, which is what ADR 042's rule about relay
 * hints was protecting.
 *
 * Layout: the CBOR of [Body], base64url without padding. **Unsigned**, unlike the contact card: the URL
 * *is* the capability (the token and the secret are bearer credentials), and a relay has no identity key
 * to sign as. What the link cannot prove — that the host is who the sender says — the sheet shows in the
 * largest type on it. A field added to [Body] later is additive under `ignoreUnknownKeys`; [VERSION]
 * exists for the change that rule cannot absorb.
 *
 * Two optional fields arrived later, additively ([Body.i] and [Body.k], ADR 2026-10.3sk7): the origin of a ticket issuer
 * and a customer key it trades for this relay's daily tickets. They come as a pair or not at all, and only an issuer's
 * operator or storefront writes them — [mint] has no way to, because a forwarded key lends out its holder's quota.
 *
 * Text forms, both accepted by [parse]: the App Link `https://getknit.app/r#<invite>` (the invite rides
 * the fragment, which a browser never sends to the server — it carries a bearer token) and the scheme
 * link `knit://r/<invite>`. A link is found anywhere in pasted text. There is deliberately **no bare
 * form**: an invite is short and only ever minted as a link, and [looksLikeInvite] feeds the share
 * intent's "this is not a draft" gate, where a base64-looking paragraph must not be swallowed.
 *
 * Pure and Android-free (JVM-tested with golden vectors in `RelayInviteTest`); [parse] never throws.
 */
object RelayInvite {
    /** Invite layout version — bump only for a change `ignoreUnknownKeys` cannot absorb. */
    const val VERSION = 1

    const val URL_PREFIX = "https://getknit.app/r#"
    const val SCHEME_PREFIX = "knit://r/"

    /** Refused before decoding: bounds the work a hostile paste can cause (a real invite is ~60–330 bytes). */
    const val MAX_BYTES = 512

    /** A spool URL longer than this is not one anyone typed into a daemon's config. */
    const val MAX_URL_CHARS = 256

    /**
     * The invite: the relay, the room it runs when the minter had joined one, and the ticket issuer's origin [i] with
     * the customer key [k] it takes when the link came from an issuer's operator. Declared in wire order — the house
     * CBOR writes keys as declared, so `i` and `k` follow `n` (docs/RELAY_INVITE.md §2).
     */
    @Serializable
    @OptIn(ExperimentalSerializationApi::class) // @ByteString is an experimental kotlinx API
    private class Body(
        val v: Int,
        val u: String,
        @ByteString val c: ByteArray? = null,
        val n: String? = null,
        val i: String? = null,
        val k: String? = null,
    )

    /** What [parse] yields. */
    sealed interface Parsed {
        /**
         * A well-formed invite: the spool [url] exactly as it should be stored (token included), the commons
         * [secret] and [name] when the link carries a room, and the ticket [issuer] with its customer [key] when it
         * carries one (both or neither). A plain class on purpose: a data class's `toString` would print the token,
         * the secret and the key into any log line that mentions the invite.
         */
        class Invite(
            val url: String,
            val secret: ByteArray?,
            val name: String?,
            val issuer: String? = null,
            val key: String? = null,
        ) : Parsed {
            override fun toString(): String = "Invite(host=${SpoolUrl.host(url)}, room=${secret != null}, key=${key != null})"
        }

        data class Invalid(
            val reason: Reason,
        ) : Parsed
    }

    enum class Reason {
        /** Nothing invite-shaped in the text at all (the common "pasted the wrong thing" case). */
        NOT_AN_INVITE,
        TOO_LARGE,
        MALFORMED,
        BAD_VERSION,

        /**
         * The URL is not one this build may dial — the dialer's own rule, [SpoolUrl.isAcceptable], whose shape half
         * refuses an authority that is not a bare host[:port] (#340) — or it is longer than [MAX_URL_CHARS].
         */
        BAD_URL,

        /** A room secret of the wrong length: a typo must never hash into a subscription to nothing. */
        BAD_SECRET,

        /** A key with no issuer, or an issuer that is not an origin this build may contact ([IssuerOrigin]). */
        BAD_ISSUER,

        /** An issuer with no key, or a key that is not a customer key's shape ([CustomerKey]). */
        BAD_KEY,
    }

    /**
     * Encodes an invite as its bare base64url form (no padding). [url] is stored verbatim, token and all —
     * the whole point of the link is to carry it. The [name] is clamped like a group title. There is no issuer
     * or key parameter, on purpose: the phone never mints one, whatever key it holds.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun mint(
        url: String,
        secret: ByteArray? = null,
        name: String? = null,
    ): String {
        require(secret == null || secret.size == ScopeCrypto.COMMONS_SECRET_BYTES) {
            "commons secret must be ${ScopeCrypto.COMMONS_SECRET_BYTES} bytes"
        }
        val body =
            Body(
                v = VERSION,
                u = url.trim(),
                c = secret,
                n = name?.let { clampName(it) }?.takeIf { it.isNotEmpty() },
            )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cryptoCbor.encodeToByteArray(body))
    }

    /** The App Link form of a [compact] invite. */
    fun url(compact: String): String = URL_PREFIX + compact

    /** The custom-scheme form of a [compact] invite. */
    fun schemeUrl(compact: String): String = SCHEME_PREFIX + compact

    /**
     * Parses either link form found anywhere in [text]. Never throws: every decode step is caught and
     * mapped to a [Reason], because this is fed straight from the clipboard or a share intent.
     * [allowCleartext] is the build's `ws://` policy, passed in so the codec stays pure — the caller hands
     * it `BuildConfig.DEBUG`, exactly as the relay editor does.
     */
    fun parse(
        text: String,
        allowCleartext: Boolean,
    ): Parsed {
        val compact = extractCompact(text.trim()) ?: return Parsed.Invalid(Reason.NOT_AN_INVITE)
        if (compact.length > MAX_COMPACT_CHARS) return Parsed.Invalid(Reason.TOO_LARGE)
        val raw = runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull() ?: return Parsed.Invalid(Reason.MALFORMED)
        if (raw.size > MAX_BYTES) return Parsed.Invalid(Reason.TOO_LARGE)
        return decodeBody(raw, allowCleartext)
    }

    /**
     * The cheap "is this an invite at all" probe for an incoming intent: one of the link forms anywhere
     * in the text with something after it. Disjoint from `ContactCard.looksLikeCard` by construction —
     * the two prefixes differ — and pinned so.
     */
    fun looksLikeInvite(text: String): Boolean = extractCompact(text.trim()) != null

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeBody(
        raw: ByteArray,
        allowCleartext: Boolean,
    ): Parsed {
        val body = runCatching { cryptoCbor.decodeFromByteArray<Body>(raw) }.getOrNull() ?: return Parsed.Invalid(Reason.MALFORMED)
        if (body.v != VERSION) return Parsed.Invalid(Reason.BAD_VERSION)
        val url = body.u
        if (!isUsableUrl(url, allowCleartext)) return Parsed.Invalid(Reason.BAD_URL)
        val secret = body.c
        if (secret != null && secret.size != ScopeCrypto.COMMONS_SECRET_BYTES) return Parsed.Invalid(Reason.BAD_SECRET)
        // Both or neither, and each refused as a whole invite rather than dropped: a link that half-carries a key is
        // one somebody built wrong, and applying its relay alone would hide that from the person who sent it.
        val issuer = body.i
        val key = body.k
        if (key != null && (issuer == null || !IssuerOrigin.isAcceptable(issuer, allowCleartext))) return Parsed.Invalid(Reason.BAD_ISSUER)
        if (issuer != null && (key == null || !CustomerKey.isWellFormed(key))) return Parsed.Invalid(Reason.BAD_KEY)
        return Parsed.Invite(
            url = url,
            secret = secret,
            name = body.n?.let { clampName(it) }?.takeIf { it.isNotEmpty() },
            issuer = issuer,
            key = key,
        )
    }

    /**
     * A URL the dialer would accept, and no longer than a daemon's config would hold. A link is attacker-typed, so
     * the shape matters as much as the scheme (ADR 019 M6): the sheet titles the invite with [SpoolUrl.host], and
     * [SpoolUrl.isAcceptable] refuses every form where that could name a host other than the one the socket dials —
     * user info, a `\` or `#` in the authority, a percent-escape or a non-ASCII letter in the host (ADR 2026-11.ut8g).
     */
    private fun isUsableUrl(
        url: String,
        allowCleartext: Boolean,
    ): Boolean = url.length <= MAX_URL_CHARS && SpoolUrl.isAcceptable(url, allowCleartext)

    /** The compact invite after the first link prefix found in [text], or null when there is none. */
    private fun extractCompact(text: String): String? {
        for (prefix in listOf(URL_PREFIX, SCHEME_PREFIX)) {
            val at = text.indexOf(prefix)
            if (at >= 0) {
                val run = text.substring(at + prefix.length).takeWhile { it in COMPACT_ALPHABET }
                return run.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun clampName(name: String): String = normalizeSingleLine(name).clampToUtf16(TextLimits.GROUP_NAME)

    private const val COMPACT_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_="

    /** base64url of [MAX_BYTES] with padding headroom — checked before decoding so the decoder never sees more. */
    private const val MAX_COMPACT_CHARS = (MAX_BYTES + 2) / 3 * 4
}
