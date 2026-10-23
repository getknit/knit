package app.getknit.knit.mesh.spool

import app.getknit.knit.isValidReactionEmoji
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * One conversation's Internet presence: the scope id its members derive from a shared secret, the seal
 * keys bound to it, and the retention [bounds] members declare at SUB. Exactly one of [peerId] (the DM
 * counterpart) and [groupId] is set — the same discriminator the spec's `ScopeConfigPayload` uses
 * (`groupId` null ⇒ the DM pair). [roster] is the group's **founding** roster, carried here so the
 * group frame-set rule (§4.4) stays a pure function of the scope.
 *
 * [retiring] marks a scope derived from a *superseded* secret, kept subscribed for its drain window so a
 * rotation doesn't strand blobs: a replaced DM session (spec §3.1) or a rotated-away group root, whether
 * rotated by a departure re-mint or by losing a competing v1 mint (§3.2/§3.3). We still pull and heal a
 * retiring scope, but never seal fresh frames into it — with one exception, [resetInit].
 *
 * [resetInit] is set on both DM scopes of a peer while **our own reset** to it is unanswered (spec C-3.1-4/5,
 * ADR 2026-10.j67x): the public ephemeral of that reset's init. On the retiring one — the root the reset
 * replaced, which the peer is still on — it is the one refill that scope takes; on both it is the only flagged
 * reset of ours either may take ([ScopeFrames.mayRefill]). It is null on every other scope, so a reset of ours
 * that is no longer the pending one — a race we lost on our own reset, a reset an earlier one superseded — is
 * never pushed anywhere again.
 */
class Scope(
    val id: ByteArray,
    val keys: ScopeCrypto.SealKeys,
    val bounds: ScopeBounds,
    val retiring: Boolean = false,
    val peerId: String? = null,
    val groupId: String? = null,
    val roster: Set<String> = emptySet(),
    // The third form (spec §7.4): a commons, named by the conversation its posts land in, and bound to the
    // one spool that runs it.
    val commonsId: String? = null,
    val spoolUrl: String? = null,
    // A pair scope (spec §3.5): DM-form, the same [peerId] as the DM scope that supersedes it, keyed by the
    // identity pair secret rather than a session root. Kept apart here so a reader of the status list can
    // tell the two scopes of one peer apart — nothing downstream treats them differently.
    val pair: Boolean = false,
    val resetInit: ByteArray? = null,
    // The relays a DM, pair or group scope is synced at (ADR 2026-10.8kfx, `ScopeSpools`), or null for every relay
    // this phone dials: a registry built without placement, as every test that predates homes is.
    val spools: Set<String>? = null,
) {
    /** The spec's display form — lowercase hex — and this scope's identity in maps/logs/diagnostics. */
    val idHex: String = hex(id)

    /** What this scope is *of*, for diagnostics: the DM peer's node id, the group id, or the commons' thread id. */
    val label: String = peerId ?: groupId ?: commonsId.orEmpty()

    /** Whether [url] is a relay this scope belongs at: a commons' one relay, else its [spools], else every relay. */
    fun belongsAt(url: String): Boolean = spoolUrl?.let { it == url } ?: spools?.contains(url) ?: true

    override fun equals(other: Any?): Boolean = other is Scope && other.idHex == idHex

    override fun hashCode(): Int = idHex.hashCode()
}

/**
 * The member-side frame rules of `docs/SPOOL_PROTOCOL.md` — what may ride a scope (§4.4), how a frame
 * becomes a blob and back (§4.3/§4.4), and the outward dead-on-arrival guard (§9.2). Pure: no Android,
 * no IO, no state, so the whole rule set is unit-testable against fixtures.
 *
 * The Ed25519 sender check the spec's §4.4 pipeline also demands is deliberately **not** re-implemented
 * here — it is `InboundPipeline.canCarry`, which already resolves the pinned key and verifies the
 * signature byte-exact (never the block list: ADR 2026-09.bts9). [ScopeSync] runs it as the step between [open] and the
 * mesh bridge, the way [app.getknit.knit.mesh.ForwardSync] injects its `authenticate` hook.
 */
object ScopeFrames {
    /** A sealed custody unit ready to PUSH: the blob and its content address. */
    class Sealed(
        val blobId: ByteArray,
        val blob: ByteArray,
    )

    /** A blob that survived [open]: the re-serve wrapper and its decoded routing envelope. */
    class Opened(
        val wire: WireEnvelope,
        val env: RelayEnvelope,
    )

    /**
     * The §4.4 frame-set rule for whichever form [scope] is. It governs **both** directions — a frame
     * that fails it is neither sealed into the scope nor accepted out of it — and a scope with neither
     * form set carries nothing, which is the safe default for a malformed scope table.
     */
    fun eligibleFor(
        env: RelayEnvelope,
        selfId: String,
        scope: Scope,
    ): Boolean =
        when {
            scope.peerId != null -> eligibleForDm(env, selfId, scope.peerId)
            scope.groupId != null -> eligibleForGroup(env, scope.groupId, scope.roster)
            scope.commonsId != null -> eligibleForCommons(env, scope.id)
            else -> false
        }

    /**
     * Whether a frame that matches [scope]'s frame-set rule may be **pushed** into it (spec C-3.1-3, C-3.1-5,
     * C-4.4-3; ADR 2026-10.j67x). The pull side never asks this — a retiring scope is drained of whatever the
     * rule admits, the peer's frames above all.
     *
     * - **A reset of ours** ([ownResetInit]) goes into a DM scope only while it is the pending one
     *   ([Scope.resetInit]), live or retiring. A flagged init we minted and then left — we lost the race on it and
     *   adopted the peer's root, or a later reset superseded it — is still in our custody for a day, and pushing it
     *   into a scope the peer pulls after it has confirmed us hands it a fresh-looking `FLAG_RESET` init it adopts
     *   as a replacement: a pair on two roots. A pair scope (§3.5) is not the ratchet's and keeps the plain rule.
     * - **Anything else** goes into a live scope, never into a retiring one (C-3.1-3).
     *
     * So a retiring scope takes exactly one frame, the pending reset — and only the flagged one. Every frame
     * sealed on the unanswered session carries the same init (#90), but only the flagged one is adopted under the
     * peer's one-minute reset floor and is exempt from its race-remnant guard; an unflagged copy reaching the
     * peer first meets the hour-long incidental floor or is read without adoption. And the peer needs only the
     * reset from that scope: once it adopts, it derives the new scope, where everything else of ours went.
     */
    fun mayRefill(
        env: RelayEnvelope,
        selfId: String,
        scope: Scope,
    ): Boolean {
        val reset = if (scope.pair) null else ownResetInit(env, selfId, scope)
        return reset?.contentEquals(scope.resetInit) ?: !scope.retiring
    }

    /**
     * The init ephemeral of [env] when it is **our own** session reset to [scope]'s peer, read off the cleartext v2
     * ratchet header (the `CTL_SESSION_RESET` inside is sealed): a DM-form `chat` from us to the peer whose header
     * sets `FLAG_RESET` and carries an init. Null for anything else.
     */
    fun ownResetInit(
        env: RelayEnvelope,
        selfId: String,
        scope: Scope,
    ): ByteArray? {
        val peerId = scope.peerId ?: return null
        val ours = env.senderId == selfId && env.recipientId == peerId
        if (!ours || env.group != null || env.type != FrameType.CHAT) return null
        val r = WireCodec.decodePayload<ChatContent>(env.payload)?.enc?.r ?: return null
        return r.init?.eph?.takeIf { r.flags and RatchetHeader.FLAG_RESET != 0 }
    }

    /**
     * The commons half of the §4.4 rule (spec §7.4). A commons has no roster — whoever holds the invite is
     * in — so the rule is about shape, not membership:
     *
     * - `type = profile` — from **anyone** on the way in (self-certifying, the [eligibleForDm] argument),
     *   and **only our own** on the way out: every member keeps its own profile live in the room, and
     *   re-pushing every pinned peer's profile from custody would turn the room into a directory of
     *   everyone this device ever met.
     * - `type = commons` — a [CommonsPost] naming *this* scope, addressed to nobody, in the clear inside
     *   the room's seal (every member shares the key; `enc` set is a frame from nowhere). The scope check
     *   is what stops a member of two rooms re-sealing one room's signed post into the other. It carries
     *   exactly one content ([commonsContentOk]): a chat, whose attachment names its type and its key, or a
     *   reaction to a post by a canonical id.
     *
     * Nothing else: no receipts (N of them per post would evict the posts out of a 500-frame room — a post's
     * tick takes the group's route, a sealed DM to the author in the pair's own scope), no DM-form chat — a
     * pair's DM has its own scope. A reaction is a person's act, like a post, so it costs the room what a post
     * does. The in-direction asymmetry on `profile` is deliberate and is the one place [eligibleFor] is not
     * the same rule both ways.
     */
    fun eligibleForCommons(
        env: RelayEnvelope,
        scopeId: ByteArray,
    ): Boolean =
        when {
            env.recipientId != null || env.group != null -> {
                false
            }

            env.type == FrameType.PROFILE -> {
                true
            }

            env.type == FrameType.COMMONS -> {
                val post = WireCodec.decodePayload<CommonsPost>(env.payload)
                post != null && post.scope.contentEquals(scopeId) && commonsContentOk(post)
            }

            else -> {
                false
            }
        }

    /**
     * A commons post's content shape: exactly one of chat and reaction. A chat is cleartext (`enc` null) and an
     * attachment it names is a well-formed address with its type and its key beside it: there is no sealed content
     * to carry them, and a key-less blob's address would be its plaintext's, which anyone holding the same bytes
     * could ask the radios about. A reaction targets a canonical frame id with an emoji the wire takes, or none (a
     * retraction).
     */
    private fun commonsContentOk(post: CommonsPost): Boolean {
        val chat = post.chat
        val reaction = post.reaction
        return when {
            chat != null && reaction == null -> {
                val hash = chat.attachmentHash
                chat.enc == null && (hash == null || (isValidBlobHash(hash) && chat.attachmentMime != null && chat.attachmentKey != null))
            }

            reaction != null && chat == null -> {
                FrameId.toBytesOrNull(reaction.messageId) != null && reaction.emoji?.let(::isValidReactionEmoji) != false
            }

            else -> {
                false
            }
        }
    }

    /** The push-side narrowing of [eligibleForCommons]: only our own frames leave this device for a commons. */
    fun pushableToCommons(
        env: RelayEnvelope,
        selfId: String,
        scopeId: ByteArray,
    ): Boolean = env.senderId == selfId && eligibleForCommons(env, scopeId)

    /**
     * The DM half of the §4.4 frame-set rule. Two forms ride a DM scope:
     *
     * - `type = chat` — the sender/recipient pair is exactly this scope's two members, and the payload is
     *   v2-sealed with the DM ratchet header.
     * - `type = profile` — from either member. It addresses nobody, so it is matched on **sender alone**;
     *   requiring a recipient pair (as this rule did until ADR 022) is what kept it off the plane.
     *
     * The profile form is what carries `ProfileContent.prekey`, and the prekey is the one thing a sealed
     * `CTL_PROFILE` can never carry — sealing a session-starter under a session that must already exist is
     * circular. Without it a peer reachable only over the Internet can never learn a rotated prekey, so a
     * broken DM session can never be re-established and the group sender-key seeds that ride as ctl DMs
     * never arrive either. A profile is safe inside a scope for the same reason it is safe on the mesh: it
     * is self-certifying, authenticated against the `pubKey` in its own payload (`InboundPipeline.canCarry`
     * re-derives the nodeId from it), so carrying it grants the sender nothing a flood does not.
     *
     * The plaintext broadcast room and the cleartext receipt/reaction frames stay excluded: a scope-eligible
     * pair is ratchet-capable by construction, so its receipts and reactions already ride as sealed ctl
     * frames inside chat (`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`).
     */
    fun eligibleForDm(
        env: RelayEnvelope,
        selfId: String,
        peerId: String,
    ): Boolean {
        if (env.group != null) return false
        if (env.type == FrameType.PROFILE) return env.senderId == selfId || env.senderId == peerId
        if (env.type != FrameType.CHAT) return false
        val recipient = env.recipientId ?: return false
        val pairMatches =
            (env.senderId == selfId && recipient == peerId) ||
                (env.senderId == peerId && recipient == selfId)
        if (!pairMatches) return false
        val enc = WireCodec.decodePayload<ChatContent>(env.payload)?.enc ?: return false
        return EncEnvelope.isDmRatchetVersion(enc.v) && enc.r != null
    }

    /**
     * The group half of the §4.4 frame-set rule: a ratcheted group chat frame, a `groupupdate`, a
     * `groupleave` for this scope's group, or a member's `profile` — all from a founding-roster sender.
     *
     * Two details the rule turns on, both easy to get subtly wrong:
     *
     * - **Where the group id lives is per type.** `chat` and `groupupdate` carry it in the envelope's
     *   [RelayEnvelope.group]; `groupleave` carries it in its *payload* and leaves that field null (see
     *   `MeshManager.sendGroupLeave`). Reading only the envelope would silently exclude departures — the
     *   one frame the remaining members most need to reach each other over the Internet, since it is
     *   what drives the leave-rekey and the scope rotation.
     * - **[roster] is the FOUNDING roster, not the effective one.** A leaver is already departed by the
     *   time its own `groupleave` is evaluated, and a departed member's pre-departure frames stay
     *   legitimately re-servable. Admitting them is safe because the departure re-mint rotates the scope
     *   id: a departed member cannot reach the new scope at all, whatever this rule says.
     *
     * v1-wrapped group chat is excluded like every other non-ratcheted form — a group with a scope is
     * fully ratchet-capable by construction (§3.3), so a v1 frame in one is a peer that has since
     * regressed, not a case to carry.
     */
    fun eligibleForGroup(
        env: RelayEnvelope,
        groupId: String,
        roster: Set<String>,
    ): Boolean {
        if (env.senderId !in roster) return false
        return when (env.type) {
            FrameType.CHAT -> {
                if (env.group?.id != groupId) {
                    false
                } else {
                    val enc = WireCodec.decodePayload<ChatContent>(env.payload)?.enc
                    enc != null && enc.v == EncEnvelope.VERSION_RATCHET && enc.g != null
                }
            }

            FrameType.GROUP_UPDATE -> {
                env.group?.id == groupId
            }

            FrameType.GROUP_LEAVE -> {
                WireCodec.decodePayload<GroupLeaveContent>(env.payload)?.groupId == groupId
            }

            // A member's own profile, carrying the prekey a co-member needs to open a DM session with them
            // — and therefore to receive the CTL_GROUP_KEY seeds that make this group's frames readable at
            // all. It names no group, so the founding-roster check above is the whole rule (see
            // [eligibleForDm] for why a self-certifying frame is safe inside a scope).
            FrameType.PROFILE -> {
                true
            }

            else -> {
                false
            }
        }
    }

    /**
     * Seals a custody unit into [scope] (§4.3). Deterministic by construction, so every member seals the
     * same frame to the same blob id — that is what makes spool dedup and cross-uploader digest
     * convergence hold without coordination.
     */
    fun seal(
        scope: Scope,
        sig: ByteArray,
        signed: ByteArray,
    ): Sealed {
        val blob = ScopeCrypto.seal(scope.keys, scope.id, sig, signed)
        return Sealed(blobId = ScopeCrypto.blobId(blob), blob = blob)
    }

    /**
     * Runs §4.4's ordered validation over a pulled or evented blob: the content address, the AEAD (which
     * also enforces the seal version and the scope-bound aad), the envelope decode, the id's shape
     * (C-4.4-14, [FrameId.admits]), and the frame-set rule. Returns null for **any** failure — the caller
     * quarantines the blob id in its invalid set (§9.3) rather than retrying it, because the fault is always
     * the uploader's and never the spool's.
     *
     * The returned [Opened.wire] is the custody re-serve shape: the same `signed`/`sig` bytes under a
     * fresh wrapper with a full hop budget (ttl default, hops 0), exactly what
     * [app.getknit.knit.mesh.ForwardSync.onDigest] stamps when it re-serves to a neighbor.
     */
    @Suppress("ReturnCount") // one guard per §4.4 step reads better than a nested pyramid
    fun open(
        scope: Scope,
        selfId: String,
        blobId: ByteArray,
        blob: ByteArray,
    ): Opened? {
        if (!ScopeCrypto.blobId(blob).contentEquals(blobId)) return null
        // ScopeCrypto.open throws IllegalArgumentException on a structurally invalid blob and the JDK
        // AEAD exception on a wrong key/scope/tamper; both mean "quarantine", so collapse them here.
        val unsealed = runCatching { ScopeCrypto.open(scope.keys, scope.id, blob) }.getOrNull() ?: return null
        val env = WireCodec.decodeEnvelope(unsealed.signed) ?: return null
        // The router refuses the same frame, but only a quarantine here keeps it from being pulled again on every
        // connection: custody holds its unmarked twin, so §9.6 would never account it (#178). It is also the commons
        // door's only gate, since a commons post never meets the router.
        if (!FrameId.admits(env)) return null
        if (!eligibleFor(env, selfId, scope)) return null
        return Opened(WireEnvelope(sig = unsealed.sig, signed = unsealed.signed), env)
    }

    /**
     * The cast set (docs/VOICE_CALLS.md §2.7), the §4.4 analogue for the lane a spool never stores. Only a walkie's
     * `talk` frame, only on a pair's own DM scope — never a pair scope, a group or the commons — and only between the
     * scope's two members in the direction [inbound] names: peer to us on the way in, us to the peer on the way out.
     * `isCustodial` is checked as well, belt and braces, so a later amendment that widens the set can never admit a
     * custodial type by accident and let it dodge custody and the digest.
     *
     * The signature is not checked here. As for a blob it is `InboundPipeline.canCarry`, which `ScopeSync` runs next.
     */
    fun castEligible(
        env: RelayEnvelope,
        selfId: String,
        scope: Scope,
        inbound: Boolean,
    ): Boolean {
        val peerId = scope.peerId ?: return false
        if (scope.pair || env.group != null) return false
        if (env.type != FrameType.TALK || FrameType.isCustodial(env.type)) return false
        val from = if (inbound) peerId else selfId
        val to = if (inbound) selfId else peerId
        return env.senderId == from && env.recipientId == to
    }

    /** Seals one signed frame as a cast for [scope] (docs/VOICE_CALLS.md §2.7). */
    fun sealCast(
        scope: Scope,
        sig: ByteArray,
        signed: ByteArray,
    ): ByteArray = ScopeCrypto.sealCast(scope.keys, scope.id, sig, signed)

    /**
     * Opens a cast and applies the cast set, or null on any failure. Nothing is quarantined: a cast is never listed,
     * so a bad one cannot drive a re-pull loop, and nothing about it is remembered.
     *
     * The returned wire is a delivery to this phone and nowhere further: `ttl = 0`, `relay = false`, so the router
     * delivers it and floods it to no neighbour. Its signature rides as the frame's own, so the ordinary signed door
     * verifies it again.
     */
    fun openCast(
        scope: Scope,
        selfId: String,
        data: ByteArray,
    ): Opened? {
        val unsealed = runCatching { ScopeCrypto.openCast(scope.keys, scope.id, data) }.getOrNull() ?: return null
        val env = WireCodec.decodeEnvelope(unsealed.signed) ?: return null
        if (!castEligible(env, selfId, scope, inbound = true)) return null
        return Opened(WireEnvelope(ttl = 0, hops = 0, relay = false, sig = unsealed.sig, signed = unsealed.signed), env)
    }

    /**
     * §9.2's outward guard: never push a frame whose frame-global expiry has already lapsed. The mesh's
     * custody store applies the same rule inward (`ForwardRepository.store` refuses a dead-on-arrival
     * frame), so an expired frame can neither enter local custody nor bounce between our uploads and the
     * spool's eviction. [ttlMs] is the scope's, not the mesh custody TTL — they are different numbers.
     */
    fun deadOnArrival(
        env: RelayEnvelope,
        ttlMs: Long,
        now: Long,
    ): Boolean = env.sentAt + ttlMs <= now
}

/**
 * Lowercase hex — the spec's display form for scope ids, blob ids, and digests (§2). A nibble table
 * rather than `String.format`: this runs on every id on every inbound record, and a formatter call per
 * byte was most of a listing's cost. (`java.util.HexFormat` is API 34; minSdk is 29.)
 */
fun hex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    bytes.forEachIndexed { i, b ->
        val v = b.toInt() and BYTE_MASK
        out[i * 2] = HEX_DIGITS[v ushr NIBBLE_BITS]
        out[i * 2 + 1] = HEX_DIGITS[v and NIBBLE_MASK]
    }
    return String(out)
}

private const val HEX_DIGITS = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val NIBBLE_BITS = 4
