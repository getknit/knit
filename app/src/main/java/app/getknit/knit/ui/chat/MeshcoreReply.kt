package app.getknit.knit.ui.chat

import app.getknit.knit.mesh.MeshcoreMentions
import app.getknit.knit.mesh.protocol.ReplyRef

/**
 * What a reply in the MeshCore room puts in front of its words (ADR 2026-11.gsqu's 2026-11-19 amendment): MeshCore's own
 * `@[name] ` form, addressed to the name the quoted row [replyTo] came under as [rows] hold it — a heard post's own name, or
 * [ownName] (the board's name now, its owner's handle) for one of ours. Null for no reply, a row no longer loaded, an
 * unnamed post, one whose name the screen hides, or a name the token cannot carry ([MeshcoreMentions.replyPrefix]): the
 * reply then goes as a plain post. One rule for the composer's byte cap and the post itself, so the two always agree.
 */
internal fun meshcoreReplyPrefix(
    replyTo: ReplyRef?,
    rows: List<ChatRow>,
    ownName: String?,
): String? {
    val row = replyTo?.let { quoted -> rows.firstOrNull { it.id == quoted.messageId } } ?: return null
    val name = if (row.mine) ownName else row.origin?.takeIf { it.meshcore }?.name
    return MeshcoreMentions.replyPrefix(name)
}
