package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.TextLimits
import app.getknit.knit.clampToUtf16
import app.getknit.knit.identity.NameKey
import app.getknit.knit.mesh.MeshcoreReactions
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.PublicPostKey
import kotlin.math.roundToInt

/**
 * A MeshCore board's slot 0 as the handshake read it (ADR 2026-11.gsqu's Stage 6 amendment): whatever it holds, never a
 * constant `Public`. The companion adds Public at boot and then lets the saved channels replace it (`MyMesh.cpp:1002-1003`),
 * and Knit's setup keeps slot 0 as it finds it, so the user's own board may hold a cleared slot (which would air under the
 * all-zero key and reach nobody, while receivers skip an unnamed slot, `BaseChatMesh.cpp:401`) or a private group's key.
 * The slot's secret is never kept: only whether it is the published Public key.
 */
internal data class MeshcorePublicSlot(
    /** The slot's own name, cleaned for one line ([TextLimits.cleanReceived]); null when nothing printable is left. */
    val name: String?,
    /** Its secret is [MeshcoreProto.PUBLIC_GROUP_PSK]: every radio and every uploader holds the key. */
    val keyIsPublic: Boolean,
    /**
     * No channel to post on: no name (receivers skip it, `BaseChatMesh.cpp:401`) or an all-zero secret (`getChannel`
     * succeeds on it and the post airs under the null key, `BaseChatMesh.cpp:926-931`).
     */
    val empty: Boolean,
    /** Slot 0 holds Knit's own channel (a hand-built board): no public channel to mirror or post on. */
    val knit: Boolean,
) {
    companion object {
        /** Slot 0 as its `CHANNEL_INFO` reads ([name] and [secret] as the board sent them); nothing of the secret is kept. */
        fun of(
            name: ByteArray,
            secret: ByteArray,
        ): MeshcorePublicSlot =
            MeshcorePublicSlot(
                name = TextLimits.cleanReceived(name.decodeToString(), MeshcoreProto.NAME_FIELD).ifEmpty { null },
                keyIsPublic = secret.contentEquals(MeshcoreProto.PUBLIC_GROUP_PSK),
                empty = name.isEmpty() || secret.all { it.toInt() == 0 },
                knit =
                    name.contentEquals(KnitChannel.NAME.encodeToByteArray()) &&
                        secret.contentEquals(KnitChannel.PSK),
            )
    }
}

/**
 * A post heard on the board's slot 0, judged ([MeshcorePublicJudge]) and handed to the room (Stage 6b's sink): always
 * unsigned and a **stranger**. [name] is a self-chosen display name the sender's board put in front of its text and proves
 * nothing — never resolved to a contact, by name or otherwise. [key] is what the row's id is derived from (the sender's
 * timestamp and the raw text, before any cleaning), and nothing else may read the timestamp: the row orders and ages by its
 * arrival. [live] says whether it was drained after this connection's first `NO_MORE` (false: the board's offline queue
 * replayed what it heard while the phone was away). [tag] is the post's MeshCore One reaction tag
 * ([MeshcoreReactions.tag], ADR 2026-11.gsqu's 2026-11-21 amendment), computed here in the plane over the body's bytes as
 * drained and the sender's stamp, so only its 8 characters cross the seam: the room never holds the raw body and never
 * hashes heard text. [hops] is the repeaters' hop count the drain's `path_len` carried ([MeshcoreProto.hopsOf]; null when
 * it arrived direct, `0xFF`, or the drain carried none) and [snrDeci] its SNR in tenths of a dB (a V3 frame's quarter-dB
 * reading; null off a V1 frame): what the room's row keeps as `originHops` / `originSnrDeci`, as the Meshtastic room's
 * does. [toString] names nothing.
 */
internal class MeshcorePublicPost(
    val name: String?,
    val body: String,
    val key: PublicPostKey,
    val live: Boolean,
    val tag: String,
    val hops: Int? = null,
    val snrDeci: Int? = null,
) {
    override fun toString(): String = "MeshcorePublicPost(named=${name != null}, ${body.length} ch, live=$live)"
}

/**
 * Decides what a `CHANNEL_MSG_RECV` on slot 0 says (ADR 2026-11.gsqu's Stage 6 amendment, under the design passes' lora and
 * security N1, N10). Pure, so the whole rule is pinned on the JVM ([MeshcorePublicJudgeTest]).
 *
 * **The form.** A companion composes `"%s: "` from its own `node_name` and copies the text after it
 * (`BaseChatMesh.cpp:518-531`, the name at most 31 B: `char node_name[32]`, `NodePrefs.h:17`); the receiving board hands the
 * phone the text up to its first NUL (`strlen`), cut to `MAX_FRAME_SIZE` less the frame's header (`MyMesh.cpp:577-580`). So
 * the text splits at the **first** `": "` only when what comes before it is 1–31 bytes; otherwise there is no name and the
 * whole text is the body. A name that itself holds `": "` splits early — the rest of it lands in the body, which is all a
 * reader can know. Nothing here distinguishes a stock client from a forger: any radio on the key can write any name.
 *
 * **What is not checked.** The frame's text type: the board always reports `TXT_TYPE_PLAIN` (`MyMesh.cpp:574`) and passes
 * every group text whose flags are under 4 (`BaseChatMesh.cpp:418`), so a signed-plain sender's 4-byte prefix arrives as
 * body bytes. Our own echo by name: a name match is a claim, and refusing on it would drop another Knit user whose board's
 * tag is ours; the row id absorbs our own echo ([PublicPostKey]). And the text is never a Knit frame: it never reaches
 * `LoraFrameCodec`, COBS or `IdElision`, so a text whose bytes happen to be a valid `0x06` frame is a plain post.
 *
 * **Cleaning.** Decoded leniently (a malformed byte is U+FFFD). The name through [TextLimits.cleanReceived] at the display
 * name's limit, null when its [NameKey] is empty (only invisible characters); the body trimmed and clamped to
 * [TextLimits.MESSAGE], refused when nothing is left. The id's inputs are the raw bytes, before either.
 *
 * **The reaction tag** ([MeshcorePublicPost.tag], ADR 2026-11.gsqu's 2026-11-21 amendment) is over the body's raw bytes after
 * the same split — the whole text when there is no name — before any cleaning, trimming or clamping, and the sender's
 * stamp: the text a MeshCore One reactor hashes is the post without the board's `"name: "` prefix (checked against a real
 * reaction to our own post, 2026-11-21). A name the split refuses (over 31 B) stays in the hashed text, as it stays in the
 * body.
 *
 * **The reception.** The hop count is [MeshcoreProto.hopsOf] over the drain's `path_len` (a flooded text's low six bits; the
 * hash-size bits are never a count; `0xFF` is a direct arrival whose count is not carried, null), and the SNR the V3 frame's
 * quarter-dB byte in tenths, rounded as the Meshtastic room's ([app.getknit.knit.mesh.lora.PublicChannelPolicy]); a V1 frame
 * carries none. Facts about the last hop to our board, never about the sender.
 *
 * Receive-only: nothing here transmits, and nothing it reads is logged.
 */
internal object MeshcorePublicJudge {
    /** The longest name a companion can hold (`char node_name[32]`, `NodePrefs.h:17`): the split's bound. */
    const val NAME_MAX_BYTES = 31

    private const val COLON: Byte = 0x3A
    private const val DECI = 10f
    private const val SPACE: Byte = 0x20

    /** Why a slot-0 text was not a post, for the plane's counters. */
    enum class Refusal {
        /** The drain carried no text or no timestamp: nothing to show and nothing to key a row on. */
        NO_TEXT,

        /** Empty after decoding and trimming. */
        EMPTY_BODY,
    }

    sealed interface Verdict {
        class Post(
            val post: MeshcorePublicPost,
        ) : Verdict

        data class Refused(
            val reason: Refusal,
        ) : Verdict
    }

    /**
     * Judges the drained [text] of a slot-0 channel message stamped [timestampSecs] by its sender, [live] or replayed, received
     * under [pathLen] (the raw `path_len`, null when unknown) at [snr] dB (null off a V1 frame).
     */
    fun judge(
        text: ByteArray?,
        timestampSecs: Long?,
        live: Boolean,
        pathLen: Int? = null,
        snr: Float? = null,
    ): Verdict {
        if (text == null || timestampSecs == null) return Verdict.Refused(Refusal.NO_TEXT)
        val at = separatorAt(text)
        val nameBytes = if (at != null) text.copyOfRange(0, at) else null
        val bodyBytes = if (at != null) text.copyOfRange(at + 2, text.size) else text
        val body =
            bodyBytes
                .decodeToString()
                .trim()
                .clampToUtf16(TextLimits.MESSAGE)
        if (body.isEmpty()) return Verdict.Refused(Refusal.EMPTY_BODY)
        val name =
            nameBytes
                ?.let { TextLimits.cleanReceived(it.decodeToString(), TextLimits.DISPLAY_NAME) }
                ?.takeIf { NameKey.of(it).isNotEmpty() }
        val tag = MeshcoreReactions.tag(bodyBytes, timestampSecs)
        return Verdict.Post(
            MeshcorePublicPost(
                name,
                body,
                PublicPostKey(timestampSecs, text),
                live,
                tag,
                hops = pathLen?.let(MeshcoreProto::hopsOf),
                snrDeci = snr?.let { (it * DECI).roundToInt() },
            ),
        )
    }

    /** Where the first `": "` starts, when what precedes it is a name a companion can hold (1–31 bytes); else null. */
    private fun separatorAt(text: ByteArray): Int? {
        for (i in 0 until text.size - 1) {
            if (text[i] == COLON && text[i + 1] == SPACE) return i.takeIf { it in 1..NAME_MAX_BYTES }
        }
        return null
    }
}
