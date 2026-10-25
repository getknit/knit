package app.getknit.knit.mesh.crypto.scope

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The primitive layer of the spool plane's scope crypto (see docs/SPOOL_PROTOCOL.md, the normative
 * spec this object is the reference implementation of): scope-id and sealing-key derivation from the
 * ratchet export secrets, the deterministic outer seal over the frozen custody unit, content-addressed
 * blob ids, the per-scope set digest, and the attachment chunk seal (§4.5). Pure byte-array functions
 * over Tink's subtle HKDF and JDK AES-GCM, so everything runs unchanged under JVM unit tests and
 * doubles as the normative reference for the spool daemon and any non-Kotlin implementation.
 *
 * Domain separation: every derivation is labeled under `knit/scope/v1/...`, disjoint from
 * `knit/dm/v2/...`, `knit/group/v1/...`, and RFC 9180's HPKE labels. The DM input is
 * `RatchetCrypto.exportRoot`'s pairwiseRoot; the group input is the shared group root the spec's §3
 * mints — never a raw session or chain secret.
 *
 * The seal is deliberately deterministic (SIV-style synthetic nonce keyed by a scope secret): any
 * member sealing the same frame produces the identical blob, so spool-side dedup by [blobId] and
 * cross-uploader digest convergence hold by construction. See the spec's nonce-reuse analysis.
 *
 * All functions are deterministic; none of them touch Android, IO, or state.
 */
@Suppress("TooManyFunctions") // the spec's whole primitive surface — one object mirrors docs/SPOOL_PROTOCOL.md, the RatchetCrypto shape
object ScopeCrypto {
    const val KEY_BYTES = 32
    const val SCOPE_ID_BYTES = 32
    const val BLOB_ID_BYTES = 32
    const val NONCE_BYTES = 12
    const val DIGEST_BYTES = 8

    /** Raw Ed25519 signature length — the fixed prefix of every sealed plaintext. */
    const val SIG_BYTES = 64

    /** Outer-seal scheme version, the blob's first byte; `2` is reserved for an epoch-keyed seal. */
    const val SEAL_VERSION: Byte = 0x01

    /**
     * Attachment-chunk seal version, the chunk blob's first byte (spec §4.5). Distinct from
     * [SEAL_VERSION] so a frame blob and a chunk blob can never be fed to each other's opener, and
     * distinct from the `2` reserved for the epoch-keyed frame seal (§11).
     */
    const val ATTACH_SEAL_VERSION: Byte = 0x03

    /**
     * Cast seal version, a cast's first byte (experimental, docs/VOICE_CALLS.md §2.7). Distinct from [SEAL_VERSION] and
     * [ATTACH_SEAL_VERSION], with an aad of its own, so a cast never opens as a frame blob or a chunk and neither of
     * those opens as a cast: nothing slips a custodial frame into the unstored lane, or a cast into the digest.
     */
    const val CAST_SEAL_VERSION: Byte = 0x04

    /** A cast's plaintext is padded to a multiple of this, so a spool sees a size class rather than a frame's size. */
    const val CAST_PAD_BYTES = 128

    /** The u16 length a cast plaintext carries ahead of `signed`, and so the bound on `signed`. */
    private const val CAST_LENGTH_BYTES = 2
    private const val MAX_CAST_SIGNED = 0xFFFF

    /**
     * Plaintext bytes per attachment chunk. **Structural, not tunable**: a fixed size is what makes a
     * chunk's position derivable from the attachment alone, so no manifest object is needed. Sized so a
     * sealed chunk (1 + 12 + [ATTACH_HEADER_BYTES] + this + 16) stays inside the 64 KiB `maxBlob`.
     */
    const val ATTACH_CHUNK_BYTES = 48 * 1024

    /** The per-chunk header sealed ahead of the data: `aHash(32) ‖ u32be(index) ‖ u32be(total)`. */
    const val ATTACH_HEADER_BYTES = SCOPE_ID_BYTES + 4 + 4

    /** An attachment's per-scope identifier — the blinded handle a spool stores chunks under. */
    const val ATTACH_ID_BYTES = 32

    /** HKDF output for [sealKeysInternal]: first 32 bytes seal, second 32 key the synthetic nonce. */
    const val SEAL_OKM_BYTES = 64

    /** The commons invite secret (spec §7.4): 32 random bytes, the daemon's `Commons.SECRET_BYTES`. */
    const val COMMONS_SECRET_BYTES = 32

    /** A commons room's tag (spec §7.4, B-7.4-16): the first 8 bytes of a hash of its scope id. */
    const val COMMONS_TAG_BYTES = 8

    private const val MAC = "HMACSHA256"
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val ZERO_SALT = ByteArray(KEY_BYTES)

    /**
     * On-the-wire size of one sealed attachment chunk — `sealv(1) ‖ nonce(12) ‖ header(40) ‖ data ‖ tag(16)`
     * = 49 221 B, the §12 constant. Derived from its parts rather than written as a literal so it cannot
     * drift away from what [sealChunk] actually produces.
     *
     * Every chunk is this size including the last one: [sliceAt] pads nothing, so a short final slice
     * seals shorter. Callers sizing a *whole* attachment against a spool's `maxAttachBytes` should
     * therefore treat `chunkCount × this` as the upper bound it is — which is the safe direction, since
     * over-estimating only declines to relay an attachment the mesh still carries.
     */
    const val SEALED_CHUNK_BYTES = 1 + NONCE_BYTES + ATTACH_HEADER_BYTES + ATTACH_CHUNK_BYTES + TAG_BITS / 8

    private val LABEL_DM_ID = "knit/scope/v1/dm/id".toByteArray()
    private val LABEL_PAIR_ID = "knit/scope/v1/pair/id".toByteArray()
    private val LABEL_GROUP_ID = "knit/scope/v1/group/id".toByteArray()
    private val LABEL_SEAL = "knit/scope/v1/seal".toByteArray()
    private val LABEL_NONCE = "knit/scope/v1/nonce".toByteArray()
    private val LABEL_AID = "knit/scope/v1/aid".toByteArray()
    private val LABEL_ANONCE = "knit/scope/v1/anonce".toByteArray()
    private val LABEL_COMMONS_ID = "knit/spool/v1/commons".toByteArray()
    private val LABEL_COMMONS_KEY = "knit/spool/v1/commons-key".toByteArray()
    private val LABEL_COMMONS_TAG = "knit/spool/v1/commons-tag".toByteArray()
    private val AAD_PREFIX = "knit/scope/v1".toByteArray()
    private val AAD_ATTACH_PREFIX = "knit/scope/v1/attach".toByteArray()
    private val LABEL_CNONCE = "knit/scope/v1/cnonce".toByteArray()
    private val AAD_CAST_PREFIX = "knit/scope/v1/cast".toByteArray()

    // FNV-1a 64-bit, mirrored from mesh/StoreDigest (same fold, raw-byte input instead of UTF-8).
    private const val FNV64_OFFSET = -0x340D631B7BDDDCDBL
    private const val FNV64_PRIME = 0x100000001B3L
    private const val BYTE_MASK = 0xFFL
    private const val BYTE_MASK_INT = 0xFF

    /** The two halves of a scope's sealing secret. */
    class SealKeys(
        val sealKey: ByteArray,
        val nonceKey: ByteArray,
    )

    /** The custody unit recovered from a sealed blob — `signed`/`sig` exactly as the mesh floods them. */
    class Unsealed(
        val sig: ByteArray,
        val signed: ByteArray,
    )

    /**
     * One attachment chunk recovered from a sealed chunk blob, with the header that binds its position.
     * [total] is attacker-influenced only within the scope's own membership, but a caller must still
     * bound it before allocating anything sized by it — see `ScopeAttachments`.
     */
    class AttachChunk(
        val aHash: ByteArray,
        val index: Int,
        val total: Int,
        val data: ByteArray,
    )

    /**
     * A DM pair's scope id: keyed by the pairwiseRoot (`RatchetCrypto.exportRoot`), with the sorted
     * node ids as defense-in-depth context. Symmetric — both members derive the same id; anyone
     * holding only the public ids cannot.
     */
    fun dmScopeId(
        pairwiseRoot: ByteArray,
        nodeIdA: String,
        nodeIdB: String,
    ): ByteArray = Hkdf.computeHkdf(MAC, pairwiseRoot, ZERO_SALT, LABEL_DM_ID + dmContext(nodeIdA, nodeIdB), SCOPE_ID_BYTES)

    /** A DM scope's sealing secret, derived beside [dmScopeId] from the same root and context. */
    fun dmSealKeys(
        pairwiseRoot: ByteArray,
        nodeIdA: String,
        nodeIdB: String,
    ): SealKeys = sealKeysInternal(pairwiseRoot, dmContext(nodeIdA, nodeIdB))

    /**
     * The pair secret behind a **pair scope** (spec §3.5): the static-static X25519 agreement between the
     * two members' *identity* DH keys — the `hpkePub` half of each [app.getknit.knit.mesh.crypto.PublicKeyBundle]
     * against our own identity private scalar. Symmetric, computable by exactly the two parties from a
     * pinned bundle alone, and used nowhere else (X3DH has no identity-identity term, HPKE pairs the
     * identity key with an ephemeral), so it is the one input that exists *before* any session does. Throws
     * on an invalid public key, like `RatchetCrypto.dh`.
     */
    fun pairSecret(
        ownIkPriv: ByteArray,
        peerIkPub: ByteArray,
    ): ByteArray = X25519.computeSharedSecret(ownIkPriv, peerIkPub)

    /**
     * A pair scope's id (spec §3.5): keyed by [pairSecret] under its own label, with the same sorted-id
     * context a DM scope uses. Both members derive the same id; a spool, a node-id holder or a contact-card
     * holder cannot. Distinct from the pair's DM scope by ikm, so the two never coincide.
     */
    fun pairScopeId(
        pairSecret: ByteArray,
        nodeIdA: String,
        nodeIdB: String,
    ): ByteArray = Hkdf.computeHkdf(MAC, pairSecret, ZERO_SALT, LABEL_PAIR_ID + dmContext(nodeIdA, nodeIdB), SCOPE_ID_BYTES)

    /** A pair scope's sealing secret, derived beside [pairScopeId] from the same secret and context. */
    fun pairSealKeys(
        pairSecret: ByteArray,
        nodeIdA: String,
        nodeIdB: String,
    ): SealKeys = sealKeysInternal(pairSecret, dmContext(nodeIdA, nodeIdB))

    /**
     * A group's scope id: keyed by the shared group root, with the group id and root version as
     * context. The version doubles as the scope epoch — a re-mint bumps it, so scope id and sealing
     * keys rotate together and the old scope is unlinkable to the new one.
     */
    fun groupScopeId(
        groupRoot: ByteArray,
        groupId: String,
        rootVersion: Int,
    ): ByteArray = Hkdf.computeHkdf(MAC, groupRoot, ZERO_SALT, LABEL_GROUP_ID + groupContext(groupId, rootVersion), SCOPE_ID_BYTES)

    /** A group scope's sealing secret, derived beside [groupScopeId] from the same root and context. */
    fun groupSealKeys(
        groupRoot: ByteArray,
        groupId: String,
        rootVersion: Int,
    ): SealKeys = sealKeysInternal(groupRoot, groupContext(groupId, rootVersion))

    /**
     * A commons scope's id (spec §7.4): the plain hash of the invite secret under the transport-plane
     * label, exactly as the `knit-spool` daemon computes it from `SPOOL_COMMONS_ID`'s secret — the one
     * scope id an operator holds, and the only half of the invite a spool is ever given. The daemon owns
     * this derivation (`Commons.scopeId` in that repo), which is why it sits under `knit/spool/v1/…`
     * rather than the key plane's `knit/scope/v1/…`, and why it is a bare SHA-256 rather than HKDF.
     */
    fun commonsScopeId(secret: ByteArray): ByteArray {
        require(secret.size == COMMONS_SECRET_BYTES) { "commons secret must be $COMMONS_SECRET_BYTES bytes" }
        return sha256(LABEL_COMMONS_ID + secret)
    }

    /**
     * A commons room's tag (spec §7.4, B-7.4-16): how a HELLO's `rooms` names the room without its id. A bare hash of
     * the scope id under a transport-plane label, truncated, because the spool computes it too — from the id it was
     * configured with, never the secret. One-way: a 64-bit prefix gives back nothing of a uniformly random 256-bit id,
     * so its width sets only the odds of an accidental match.
     */
    fun commonsTag(scopeId: ByteArray): ByteArray {
        require(scopeId.size == SCOPE_ID_BYTES) { "commons scope id must be $SCOPE_ID_BYTES bytes" }
        return sha256(LABEL_COMMONS_TAG + scopeId).copyOf(COMMONS_TAG_BYTES)
    }

    /**
     * A commons scope's sealing secret (spec §7.4): the half of the invite the spool never sees. Derived
     * from the same 32-byte secret under its own label with no context — the secret is unique per
     * commons and the seal's aad already binds the scope id — and split like every other [SealKeys].
     * Deliberately not routed through the `knit/scope/v1/seal` helper: the daemon's documentation names
     * this label, and the two halves of the invite are meant to read as one derivation family.
     */
    fun commonsSealKeys(secret: ByteArray): SealKeys {
        require(secret.size == COMMONS_SECRET_BYTES) { "commons secret must be $COMMONS_SECRET_BYTES bytes" }
        val okm = Hkdf.computeHkdf(MAC, secret, ZERO_SALT, LABEL_COMMONS_KEY, SEAL_OKM_BYTES)
        return SealKeys(sealKey = okm.copyOfRange(0, KEY_BYTES), nonceKey = okm.copyOfRange(KEY_BYTES, SEAL_OKM_BYTES))
    }

    /**
     * Seals a custody unit for a scope. Deterministic: the nonce is HKDF-derived from the plaintext
     * hash under the scope's [SealKeys.nonceKey] (keyed, so a spool holding candidate frame bytes
     * cannot confirm membership by recomputing it), and the aad binds [scopeId], so a blob replanted
     * into another scope fails authentication. [sig] must be the raw 64-byte frame signature.
     */
    fun seal(
        keys: SealKeys,
        scopeId: ByteArray,
        sig: ByteArray,
        signed: ByteArray,
    ): ByteArray {
        require(sig.size == SIG_BYTES) { "sig must be $SIG_BYTES raw Ed25519 bytes" }
        require(signed.isNotEmpty()) { "signed must be non-empty" }
        val pt = sig + signed
        val nonce = Hkdf.computeHkdf(MAC, keys.nonceKey, ZERO_SALT, LABEL_NONCE + sha256(pt), NONCE_BYTES)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_PREFIX + scopeId)
            }
        return byteArrayOf(SEAL_VERSION) + nonce + cipher.doFinal(pt)
    }

    /**
     * Opens a sealed blob back into its custody unit. Throws [IllegalArgumentException] on a
     * structurally invalid blob (unknown seal version, truncated) and the JDK AEAD exception on a
     * wrong key, wrong scope, or tampered ciphertext. The caller still verifies the inner Ed25519
     * frame signature and the scope's frame-set rules — this only undoes the outer seal.
     */
    fun open(
        keys: SealKeys,
        scopeId: ByteArray,
        blob: ByteArray,
    ): Unsealed {
        require(blob.isNotEmpty() && blob[0] == SEAL_VERSION) { "unknown seal version" }
        require(blob.size > 1 + NONCE_BYTES + SIG_BYTES) { "blob too short" }
        val nonce = blob.copyOfRange(1, 1 + NONCE_BYTES)
        val ct = blob.copyOfRange(1 + NONCE_BYTES, blob.size)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_PREFIX + scopeId)
            }
        val pt = cipher.doFinal(ct)
        require(pt.size > SIG_BYTES) { "sealed unit too short" }
        return Unsealed(sig = pt.copyOfRange(0, SIG_BYTES), signed = pt.copyOfRange(SIG_BYTES, pt.size))
    }

    /**
     * Seals one frame as a cast (experimental, docs/VOICE_CALLS.md §2.7): `0x04 ‖ nonce ‖ AES-256-GCM(pt)` with
     * `pt = sig(64) ‖ u16be(|signed|) ‖ signed ‖ 0x00…` padded to a multiple of [CAST_PAD_BYTES]. The construction is
     * [seal]'s, under its own nonce label and aad (`knit/scope/v1/cast ‖ scopeId`, 50 bytes against a frame's 45 and a
     * chunk's 52, so none can alias). It is deterministic for the same reason, though nothing dedups a cast: the same
     * frame cast to two spools is the same bytes. [sig] must be the frame's raw Ed25519 signature — every cast is
     * signed, whatever its radio copy carries.
     */
    fun sealCast(
        keys: SealKeys,
        scopeId: ByteArray,
        sig: ByteArray,
        signed: ByteArray,
    ): ByteArray {
        require(sig.size == SIG_BYTES) { "sig must be $SIG_BYTES raw Ed25519 bytes" }
        require(signed.isNotEmpty() && signed.size <= MAX_CAST_SIGNED) { "signed must be 1..$MAX_CAST_SIGNED bytes" }
        val body = sig + byteArrayOf((signed.size ushr Byte.SIZE_BITS).toByte(), signed.size.toByte()) + signed
        val pt = body + ByteArray(Math.floorMod(-body.size, CAST_PAD_BYTES))
        val nonce = Hkdf.computeHkdf(MAC, keys.nonceKey, ZERO_SALT, LABEL_CNONCE + sha256(pt), NONCE_BYTES)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_CAST_PREFIX + scopeId)
            }
        return byteArrayOf(CAST_SEAL_VERSION) + nonce + cipher.doFinal(pt)
    }

    /**
     * Opens a cast back into the frame it carries. Throws like [open]: [IllegalArgumentException] on an unknown version,
     * a short or mis-padded plaintext, a length past the end, or padding that is not zero; the JDK AEAD exception on a
     * wrong key, wrong scope or tamper. The caller still verifies the frame's signature and the cast set.
     */
    fun openCast(
        keys: SealKeys,
        scopeId: ByteArray,
        data: ByteArray,
    ): Unsealed {
        require(data.isNotEmpty() && data[0] == CAST_SEAL_VERSION) { "unknown cast seal version" }
        require(data.size > 1 + NONCE_BYTES + SIG_BYTES + CAST_LENGTH_BYTES) { "cast too short" }
        val nonce = data.copyOfRange(1, 1 + NONCE_BYTES)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_CAST_PREFIX + scopeId)
            }
        val pt = cipher.doFinal(data, 1 + NONCE_BYTES, data.size - 1 - NONCE_BYTES)
        require(pt.size % CAST_PAD_BYTES == 0) { "cast plaintext is not padded" }
        val n = ((pt[SIG_BYTES].toInt() and BYTE_MASK_INT) shl Byte.SIZE_BITS) or (pt[SIG_BYTES + 1].toInt() and BYTE_MASK_INT)
        val end = SIG_BYTES + CAST_LENGTH_BYTES + n
        require(n > 0 && end <= pt.size) { "cast length outside its plaintext" }
        require(end + CAST_PAD_BYTES > pt.size) { "cast padded past one block" }
        require((end until pt.size).all { pt[it] == 0.toByte() }) { "cast padding is not zero" }
        return Unsealed(sig = pt.copyOfRange(0, SIG_BYTES), signed = pt.copyOfRange(SIG_BYTES + CAST_LENGTH_BYTES, end))
    }

    /**
     * A blob's content address: SHA-256 over the entire stored blob (version byte, nonce, ct). Also the
     * `cid` of a sealed attachment chunk (§4.5) — the shape is identical, only the plaintext differs.
     */
    fun blobId(blob: ByteArray): ByteArray = sha256(blob)

    /**
     * An attachment's identifier **within one scope** (spec §4.5). Keyed by the scope's
     * [SealKeys.nonceKey], deliberately: the attachment's own content address [aHash] rides the mesh in
     * cleartext (`ChatContent.attachmentHash`, the DB v19 precedent), so an unkeyed id would hand a
     * spool that happened to observe the mesh a confirmation oracle linking a frame to a scope. Same
     * argument as [seal]'s keyed nonce.
     */
    fun attachmentId(
        keys: SealKeys,
        scopeId: ByteArray,
        aHash: ByteArray,
    ): ByteArray {
        require(aHash.size == BLOB_ID_BYTES) { "aHash must be $BLOB_ID_BYTES bytes" }
        return Hkdf.computeHkdf(MAC, keys.nonceKey, ZERO_SALT, LABEL_AID + scopeId + aHash, ATTACH_ID_BYTES)
    }

    /**
     * Seals one attachment chunk (spec §4.5). Deterministic exactly like [seal], so every member
     * produces byte-identical chunks and a spool dedupes them by `cid` without coordination.
     *
     * [aHash] is the attachment ciphertext's SHA-256 — the value the mesh already carries — and it is
     * sealed **inside** the chunk together with `index`/`total`, so a chunk cannot be replayed at a
     * different position or against a different attachment even by a scope member.
     *
     * The aad prefix is `knit/scope/v1/attach`, not [seal]'s `knit/scope/v1`. They cannot alias: the
     * scope id that follows is fixed-width, so a frame aad is always 45 bytes and a chunk aad 52.
     */
    fun sealChunk(
        keys: SealKeys,
        scopeId: ByteArray,
        aHash: ByteArray,
        index: Int,
        total: Int,
        chunk: ByteArray,
    ): ByteArray {
        require(aHash.size == BLOB_ID_BYTES) { "aHash must be $BLOB_ID_BYTES bytes" }
        require(total >= 1) { "total must be positive" }
        require(index in 0 until total) { "index $index outside 0 until $total" }
        require(chunk.isNotEmpty() && chunk.size <= ATTACH_CHUNK_BYTES) { "chunk must be 1..$ATTACH_CHUNK_BYTES bytes" }
        val pt = aHash + u32be(index) + u32be(total) + chunk
        val nonce = Hkdf.computeHkdf(MAC, keys.nonceKey, ZERO_SALT, LABEL_ANONCE + sha256(pt), NONCE_BYTES)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_ATTACH_PREFIX + scopeId)
            }
        return byteArrayOf(ATTACH_SEAL_VERSION) + nonce + cipher.doFinal(pt)
    }

    /**
     * Opens a sealed attachment chunk back into its header and data. Throws like [open] does —
     * [IllegalArgumentException] on a structurally invalid chunk, the JDK AEAD exception on a wrong
     * key, wrong scope, or tamper. The caller still checks the returned header against what it asked
     * for and, once every chunk is in hand, that the reassembled bytes hash to [AttachChunk.aHash].
     */
    fun openChunk(
        keys: SealKeys,
        scopeId: ByteArray,
        blob: ByteArray,
    ): AttachChunk {
        require(blob.isNotEmpty() && blob[0] == ATTACH_SEAL_VERSION) { "unknown attachment seal version" }
        require(blob.size > 1 + NONCE_BYTES + ATTACH_HEADER_BYTES) { "chunk blob too short" }
        val nonce = blob.copyOfRange(1, 1 + NONCE_BYTES)
        val ct = blob.copyOfRange(1 + NONCE_BYTES, blob.size)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_ATTACH_PREFIX + scopeId)
            }
        val pt = cipher.doFinal(ct)
        require(pt.size > ATTACH_HEADER_BYTES) { "sealed chunk too short" }
        val index = u32beValue(pt, BLOB_ID_BYTES)
        val total = u32beValue(pt, BLOB_ID_BYTES + Int.SIZE_BYTES)
        require(total >= 1 && index in 0 until total) { "chunk header out of range" }
        return AttachChunk(
            aHash = pt.copyOfRange(0, BLOB_ID_BYTES),
            index = index,
            total = total,
            data = pt.copyOfRange(ATTACH_HEADER_BYTES, pt.size),
        )
    }

    /**
     * The per-scope set digest: XOR fold of [fnv64] over the raw blob ids — order-independent and
     * self-inverse, the `StoreDigest.fingerprint` shape over bytes instead of UTF-8 strings. Empty
     * set digests to 0.
     */
    fun scopeDigest(blobIds: Iterable<ByteArray>): Long = blobIds.fold(0L) { acc, id -> acc xor fnv64(id) }

    /** FNV-1a 64-bit over raw bytes (StoreDigest.hash64's input domain is UTF-8 strings; this one is bytes). */
    fun fnv64(bytes: ByteArray): Long {
        var h = FNV64_OFFSET
        for (b in bytes) {
            h = h xor (b.toLong() and BYTE_MASK)
            h *= FNV64_PRIME
        }
        return h
    }

    /** A digest's wire form: 8 bytes big-endian (a byte string on the wire, never a CBOR integer). */
    fun digestBytes(digest: Long): ByteArray = ByteArray(DIGEST_BYTES) { (digest ushr ((DIGEST_BYTES - 1 - it) * Byte.SIZE_BITS)).toByte() }

    /** Parses the 8-byte big-endian wire form back into the fold value. */
    fun digestValue(bytes: ByteArray): Long {
        require(bytes.size == DIGEST_BYTES) { "digest must be $DIGEST_BYTES bytes" }
        return bytes.fold(0L) { acc, b -> (acc shl Byte.SIZE_BITS) or (b.toLong() and BYTE_MASK) }
    }

    private fun sealKeysInternal(
        root: ByteArray,
        context: ByteArray,
    ): SealKeys {
        val okm = Hkdf.computeHkdf(MAC, root, ZERO_SALT, LABEL_SEAL + context, SEAL_OKM_BYTES)
        return SealKeys(sealKey = okm.copyOfRange(0, KEY_BYTES), nonceKey = okm.copyOfRange(KEY_BYTES, SEAL_OKM_BYTES))
    }

    // The `|` separator convention: it cannot occur in a base32 node id or a `g-`-hex group id, so
    // contexts cannot be made to alias each other (and the `g-` prefix keeps the two apart anyway).
    private fun dmContext(
        nodeIdA: String,
        nodeIdB: String,
    ): ByteArray {
        val low = minOf(nodeIdA, nodeIdB)
        val high = maxOf(nodeIdA, nodeIdB)
        return "$low|$high|".toByteArray()
    }

    private fun groupContext(
        groupId: String,
        rootVersion: Int,
    ): ByteArray = "$groupId|".toByteArray() + u32be(rootVersion)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Suppress("MagicNumber") // big-endian byte-lane shifts; naming 24/16/8 would only obscure them
    private fun u32be(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    /** Reads the big-endian u32 at [offset] — the inverse of [u32be], for the sealed chunk header. */
    private fun u32beValue(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (0 until Int.SIZE_BYTES).fold(0) { acc, i ->
            (acc shl Byte.SIZE_BITS) or (bytes[offset + i].toInt() and BYTE_MASK.toInt())
        }
}
