package app.getknit.knit.mesh.wifiaware

/**
 * Pure codec for the Wi-Fi Aware **coordination plane** text payloads: a peer's `nodeId|version` **cue** and
 * the trailing `|d<version>` **digest segment** of its publish SSI. Both are folded over the [StoreDigest]
 * content version, best-effort, no data path. Extracted verbatim from `WifiAwareTransport` so the parse/encode
 * halves stay symmetric and are exercised on the JVM ([NanCueCodecTest]); the transport keeps the Android-typed
 * handle/session bookkeeping (`CueTarget`) and the fast-frame discriminator (`MSG_FRAME_TAG`), which tags a
 * fanned-out frame *before* [parseCue] ever runs. No Android deps.
 */
object NanCueCodec {
    // Separator in a cue's `nodeId|epoch` payload (nodeIds/epochs never contain it).
    const val CUE_SEP = '|'

    // The publish-SSI digest segment prefix ("|d<version>", always the fourth |-segment). Protocol.parse
    // ignores segments past the third, so old and new peers alike read the advert unchanged.
    const val SSI_DIGEST_PREFIX = "|d"

    data class Cue(
        val nodeId: String,
        val version: Long,
    )

    /** Encode this node's cue payload: `nodeId|version`. */
    fun encodeCue(
        localNodeId: String,
        version: Long,
    ): ByteArray = "$localNodeId$CUE_SEP$version".encodeToByteArray()

    /** Parse a peer's cue payload, or null if it isn't a well-formed `nodeId|version` UTF-8 string. */
    fun parseCue(bytes: ByteArray): Cue? {
        val s = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
        val i = s.lastIndexOf(CUE_SEP)
        if (i <= 0) return null
        val version = s.substring(i + 1).toLongOrNull() ?: return null
        return Cue(s.substring(0, i), version)
    }

    /** Encode the trailing digest segment appended to a publish SSI: `|d<version>`. */
    fun encodeSsiDigest(version: Long): String = "$SSI_DIGEST_PREFIX$version"

    /** The trailing `|d<version>` digest segment of a peer's publish SSI, or null (no segment / older build). */
    fun parseSsiDigest(ssi: String): Long? {
        val i = ssi.lastIndexOf(SSI_DIGEST_PREFIX)
        if (i < 0) return null
        return ssi.substring(i + SSI_DIGEST_PREFIX.length).toLongOrNull()
    }
}
