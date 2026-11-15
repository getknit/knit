package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.PublicPostRefusal

/**
 * A post on a board's public channel as every receiver reads it (MeshCore, ADR 2026-11.gsqu's Stage 6 amendment): the
 * sender's 4-byte timestamp ([timestampSecs]) and the whole text the receiving board hands its phone ([raw]), the
 * `"<name>: "` prefix the sending board wrote included (`BaseChatMesh.cpp:523`), before any cleaning or clamping.
 *
 * What a mirrored row's id is derived from (`FrameId.forMeshcorePost`, built with the room in Stage 6b) on both sides: a
 * heard post's from the drained frame, our own from what we queued ([PublicPostResult.Queued]), so an echo of our own post
 * — a re-air after the board's 160-entry seen ring forgot it (`SimpleMeshTables.h:9`) — lands on our own row's id and
 * dedups there. The timestamp is the sender's claim and is read for nothing else: never to order or age a row.
 *
 * Equal by content. [toString] names neither the text nor the time (a stranger's words never reach a log).
 */
internal class PublicPostKey(
    val timestampSecs: Long,
    raw: ByteArray,
) {
    private val bytes = raw.copyOf()

    /** A copy of the text as the receiving board drained it. */
    val raw: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PublicPostKey && other.timestampSecs == timestampSecs && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * timestampSecs.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "PublicPostKey(${bytes.size} B)"
}

/**
 * A post [PublicPostResult.Queued] answered for that ended without airing (ADR 2026-11.gsqu's Stage 6 amendment): the
 * room's own row, written under [key], need not read as sent. On `LoraMeshTransport.publicPostsEnded`, a hot flow with no
 * replay: collect it from the plane's start.
 */
internal data class PublicPostEnded(
    val key: PublicPostKey,
    val reason: Reason,
) {
    enum class Reason {
        /** Older than `LoraFramePolicy.FRESH_MS` past its stamp at the write: never aired, and never will. */
        STALE,

        /**
         * Given up at the write: the board was no longer set up for Knit, slot 0 no longer a channel, the board refused it —
         * or the write's answer never came, in which case it may have aired after all.
         */
        ENDED_AT_WRITE,
    }
}

/**
 * What became of the user's post handed to a board's public channel ([BoardKindHooks.postPublic]): queued, with what it is
 * on the air where the kind can say ([Queued.key], MeshCore's), or refused for a [PublicPostRefusal] the composer words.
 */
internal sealed interface PublicPostResult {
    /** The refusal, or null once queued: the shape `PublicChannelSink.postToPublicChannel` answers. */
    val refusal: PublicPostRefusal?

    /**
     * In the pacer's queue. [key] is the post as its receivers will read it, the timestamp fixed at enqueue and carried by
     * the frame (a `Busy` retry never aired, so it reuses it); null on a kind whose board stamps its own (Meshtastic).
     */
    class Queued(
        val key: PublicPostKey?,
    ) : PublicPostResult {
        override val refusal: PublicPostRefusal? get() = null
    }

    /** Refused before it was queued: nothing aired, nothing to store. */
    data class Refused(
        val reason: PublicPostRefusal,
    ) : PublicPostResult {
        override val refusal: PublicPostRefusal get() = reason
    }
}
