package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupLeaveContent
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
 * retiring scope, but never seal fresh frames into it.
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
    // one spool that runs it — the only scope that is not subscribed at every relay.
    val commonsId: String? = null,
    val spoolUrl: String? = null,
    // A pair scope (spec §3.5): DM-form, the same [peerId] as the DM scope that supersedes it, keyed by the
    // identity pair secret rather than a session root. Kept apart here so a reader of the status list can
    // tell the two scopes of one peer apart — nothing downstream treats them differently.
    val pair: Boolean = false,
) {
    /** The spec's display form — lowercase hex — and this scope's identity in maps/logs/diagnostics. */
    val idHex: String = hex(id)

    /** What this scope is *of*, for diagnostics: the DM peer's node id, the group id, or the commons' thread id. */
    val label: String = peerId ?: groupId ?: commonsId.orEmpty()

    /** Whether [url] is a relay this scope belongs at: every relay, unless it is a commons bound to one. */
    fun belongsAt(url: String): Boolean = spoolUrl == null || spoolUrl == url

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
     * The commons half of the §4.4 rule (spec §7.4). A commons has no roster — whoever holds the invite is
     * in — so the rule is about shape, not membership:
     *
     * - `type = profile` — from **anyone** on the way in (self-certifying, the [eligibleForDm] argument),
     *   and **only our own** on the way out: every member keeps its own profile live in the room, and
     *   re-pushing every pinned peer's profile from custody would turn the room into a directory of
     *   everyone this device ever met.
     * - `type = commons` — a [CommonsPost] naming *this* scope, addressed to nobody, in the clear inside
     *   the room's seal (every member shares the key; `enc` set is a frame from nowhere). The scope check
     *   is what stops a member of two rooms re-sealing one room's signed post into the other.
     *
     * Nothing else: no receipts (N of them per post would evict the posts out of a 500-frame room — a post's
     * tick takes the group's route, a sealed DM to the author in the pair's own scope), no
     * reactions, no DM-form chat — a pair's DM has its own scope. The in-direction asymmetry on `profile`
     * is deliberate and is the one place [eligibleFor] is not the same rule both ways.
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
                post != null && post.scope.contentEquals(scopeId) && post.chat.enc == null && post.chat.attachmentHash == null
            }

            else -> {
                false
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
     * also enforces the seal version and the scope-bound aad), the envelope decode, and the frame-set
     * rule. Returns null for **any** failure — the caller quarantines the blob id in its invalid set
     * (§9.3) rather than retrying it, because the fault is always the uploader's and never the spool's.
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
        if (!eligibleFor(env, selfId, scope)) return null
        return Opened(WireEnvelope(sig = unsealed.sig, signed = unsealed.signed), env)
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
