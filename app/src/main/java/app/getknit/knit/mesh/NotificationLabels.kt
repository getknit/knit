package app.getknit.knit.mesh

/**
 * The words a message notification says in place of a body it cannot show: an attachment-only message's stand-in, a
 * shared position, a message content filtering hides, and a reaction to a message we wrote. [file] takes the file's
 * name as its one argument, [reaction] the emoji and the message's words. The pipeline holds no `Context`
 * (`rules/mesh.md`), so the app hands it these in the reader's own language (`notificationLabels(context)`, the same
 * strings the chat list's preview reads).
 */
data class NotificationLabels(
    val photo: String,
    val gif: String,
    val voice: String,
    val link: String,
    val location: String,
    val file: String,
    val hidden: String,
    val video: String = "🎬 Video",
    /** Someone reacted to a message we wrote (ADR 2026-10.59fu): the emoji, then the message's opening words. */
    val reaction: String = $$"Reacted %1$s to “%2$s”",
    /** A MeshCore room post that came with no name (ADR 2026-11.gsqu's Stage 6 amendment): who its notification names. */
    val unnamed: String = "Unnamed",
    /** A MeshCore room post's claimed name, as its notification names every one: quoted, then the marker. */
    val claimedName: String = $$"“%1$s” (unverified)",
) {
    companion object {
        /** The English labels — the test rigs' default; the app hands the pipeline the reader's own. */
        val ENGLISH =
            NotificationLabels(
                photo = "📷 Photo",
                gif = "🎞️ GIF",
                voice = "🎤 Voice message",
                link = "🔗 Link",
                location = "📍 Location",
                file = $$"📎 %1$s",
                hidden = "Message hidden",
                video = "🎬 Video",
                reaction = $$"Reacted %1$s to “%2$s”",
                unnamed = "Unnamed",
                claimedName = $$"“%1$s” (unverified)",
            )
    }
}
