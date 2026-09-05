package app.getknit.knit.data.message

/**
 * The Meshtastic room's channel name: the connected board's primary channel where there is one, else the
 * channel the newest heard post was tagged with, else null — the caller supplies the generic title. Pure, and
 * shared by the thread header and the chat-list row so the two cannot drift apart. [liveChannel] is what the
 * board calls slot 0 now (`LoraFacts.primaryChannel`), which is what a post typed here would go out on; the
 * row's channel is what the room *was* when the board last spoke, and stands in while it is away.
 */
fun meshRoomChannel(
    liveChannel: String?,
    messages: List<MessageEntity>,
): String? =
    liveChannel?.takeIf { it.isNotBlank() }
        ?: messages.lastOrNull { !it.originChannel.isNullOrBlank() }?.originChannel
