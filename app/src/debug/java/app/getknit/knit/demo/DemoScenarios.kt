@file:Suppress("MagicNumber")

package app.getknit.knit.demo

import app.getknit.knit.BuildConfig
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.BodyStyle

/*
 * Declarative content for the demo-screenshot builds (see [DemoSeeder]). Each [DemoScenario] is one
 * marketing "theme" — a self-contained cast, conversation history, and group — that the seeder writes
 * verbatim through the real repositories. Adding a theme is pure data here; no seeder changes.
 *
 * The numeric fields are "minutes ago" offsets (how long before now a message was sent / a group was
 * created), so [MagicNumber] is suppressed for the whole file — naming each offset would only add noise.
 *
 * Convention: message lists are ordered oldest-first. For a group, the first message's sender/time is
 * taken as the group's creator/creation time.
 *
 * The plane-shaped demo state that is NOT cast-flavoured — the Internet relays and the LoRa board — is
 * theme-independent and lives with the seeder ([DemoSeeder.DEMO_RELAYS]) and the fake board
 * ([app.getknit.knit.mesh.DemoLoraPlane]), for the same reason [DemoSeeder.ONLINE_NODE_IDS] does: a
 * relay URL and a Heltec's node number read the same at a trailhead and on the playa.
 */

/** A conversation participant. [ME] is the local profile; the rest map to the stable demo node ids in
 *  [DemoSeeder] (so [DemoSeeder.ONLINE_NODE_IDS] and the fake transport stay theme-independent). */
enum class Slot { ME, SAM, DANI, THEO, PRIYA, JONAS, LENA, JONAS_TWO, RIVER, NOAH, MARLO }

/** A reaction left on a message: who reacted, the emoji, and how long ago. */
data class DemoReaction(
    val reactor: Slot,
    val emoji: String,
    val minsAgo: Long,
)

/**
 * A link-preview card attached to a message (ADR 2026-09.n752). The **sender** fetches the page and sends
 * the card as an ordinary attachment under its own MIME, so a seeded one is the same thing the real path
 * produces: [url] must appear verbatim in the message body — a recipient draws a card only when it can find
 * the card's link there — and [image] is the base name of a bundled asset under
 * `demo/images/<theme>/<image>.jpg`, kept small enough to clear `LinkPreviewBlob.IMAGE_MAX_BYTES`.
 */
data class DemoLink(
    val url: String,
    val title: String,
    val description: String,
    val image: String? = null,
)

/**
 * One seeded message. [mentionsMe] adds an @-mention of the local user (highlighted in the room);
 * [reactions] attach an emoji cluster. [id] must be unique within a scenario.
 *
 * [replyTo] makes this a quoted reply to another message in the same scenario (by that message's [id]);
 * the seeder denormalizes the quoted author/snippet onto the row, so the referenced original need not be
 * loaded for the quote to render. [image] attaches an inline photo: the base name of a bundled asset under
 * `demo/images/<theme>/<image>.jpg`, seeded as a plaintext blob (see [DemoSeeder]).
 */
data class DemoMsg(
    val id: String,
    val from: Slot,
    val body: String,
    val minsAgo: Long,
    val mentionsMe: Boolean = false,
    val reactions: List<DemoReaction> = emptyList(),
    // Members whose delivery receipt has come back, for one of OUR messages — the per-recipient split on
    // "Message info". Seeded rather than implied by `received`, because a partial delivery (some members
    // in, one still waiting) is exactly the state worth auditing and no aggregate flag can express it.
    val deliveredTo: List<Slot> = emptyList(),
    val replyTo: String? = null,
    val image: String? = null,
    // MIME of [image]: "image/jpeg" for the bundled scene photos, "image/webp" for the animated GIF beat
    // (an animated WebP under demo/images/<theme>/<image>.webp, played by Coil's AnimatedImageDecoder).
    val imageMime: String = "image/jpeg",
    // Seconds of voice note. Set it and the row renders as a voice-note bubble with a synthetic waveform,
    // so the seeded UI and accessibility suites cover that bubble the way they cover a photo one. The blob
    // is a valid but silent ADTS stream — the bubble is what is being audited, not the audio.
    val voiceSeconds: Int? = null,
    /**
     * The plane this row crossed ([app.getknit.knit.data.message.MessageEntity.receivedVia]) — for an
     * inbound message the plane its own frame arrived on, and for one of ours the plane its first delivery
     * receipt came back on. It is what puts the globe (Internet) or the board glyph (LoRa) beside a bubble
     * and beside its ✓✓ tick, so a marketing capture can show a conversation being carried by something
     * other than the radios without the demo build ever opening a socket. [DeliveryPlane.Nearby] — a radio
     * — renders no glyph, which is why it is the default.
     */
    val via: DeliveryPlane = DeliveryPlane.Nearby,
    /**
     * A link-preview card for a link in [body], attached the way the sender's own fetch attaches it: a blob
     * of its own MIME on [DemoMsg]'s attachment slot. Mutually exclusive with [image] and [voiceSeconds] —
     * one message, one attachment — and the seeder honours that order.
     */
    val link: DemoLink? = null,
    /**
     * A short video (ADR 2026-10.hmfe): the base name of a bundled clip under `demo/videos/<theme>/<video>.mp4`, already
     * encoded the way the app's pipeline sends one (`.private/scripts/fetch-demo-videos.sh`). Its duration, size and
     * poster are derived from the bytes at seed time, as a sender derives them, and ride on the row like a received
     * description. Mutually exclusive with the other attachments; a missing asset leaves the line text-only.
     */
    val video: String? = null,
    /**
     * The language [body] is in, a model registry code (`es`), declared the way a sender's frame declares it (its
     * `lang`, ADR 2026-10.t2h6): written to the row's `bodyLang` as the inbound path would, which is what puts the
     * composer's "<name> reads Spanish" hint in the chat. Null for English, which is never declared.
     */
    val lang: String? = null,
    /**
     * What a voice note says ([voiceSeconds]), stored as the Transcribe tap stores it (`voiceTranscript`, ADR
     * 2026-10.am8d). Written only in a build with speech wired (`STT_WIRED`), so a listing capture never shows a
     * transcript a shipped build cannot make. The seeded note itself is silent; the text is data, like its body.
     */
    val transcript: String? = null,
    /**
     * Formatting (ADR 2026-10.p95v), as the sender's composer would have marked it: each [DemoStyle] styles the first
     * place its words appear in [body]. Words that do not appear are dropped, the way a receiver drops a span past the
     * body, so a typo costs the style and never the seed.
     */
    val styles: List<DemoStyle> = emptyList(),
)

/** One formatted run of a [DemoMsg]: [words], a substring of the body, in [style]. */
data class DemoStyle(
    val words: String,
    val style: BodyStyle,
)

/** A peer contact. [verified] pins a (fake) key + out-of-band confirmation so the DM header shows the
 *  verified badge; [openToChat] is the profile flag the Profile Details badge renders. The avatar is loaded
 *  from `demo/avatars/<theme>/<nodeId>.jpg`. */
data class DemoPeer(
    val slot: Slot,
    val name: String,
    val status: String,
    val verified: Boolean = false,
    val openToChat: Boolean = false,
    /**
     * The Meshtastic node number this contact's own profile claims as their board (`ProfileContent.loraNode`),
     * which is what lets a post heard on the radio channel resolve to them — the Profile Details "their board"
     * row, and the attributed author in the Meshtastic room. Null for a contact with no board.
     */
    val loraNode: Long? = null,
    /**
     * The key that board signs under (`ProfileContent.loraKey`). A fake value: nothing in a demo build ever
     * checks a signature — the verdict is seeded on the row ([DemoMeshPost.signed]) exactly as ingest froze
     * it — but the column has to be non-null for the profile row to claim a signing board at all.
     */
    val loraKey: String? = null,
)

/**
 * A 1:1 DM thread with [peer]. [read] true seeds a read watermark (no unread badge); false leaves the
 * peer's messages unread so the chat list shows a count.
 *
 * A thread the local user has never posted in — every message [from] the peer, and the peer unverified —
 * is a **message request** rather than a chat (`Conversations.isAccepted`), which is how
 * [DemoScenario.requests] gets the Requests inbox and its chat-list badge populated without a second
 * mechanism. Nothing enforces that here; the seeder simply never accepts the threads in that list.
 */
data class DemoThread(
    val peer: Slot,
    val read: Boolean,
    val messages: List<DemoMsg>,
)

/**
 * One post in the **Meshtastic room** (`Conversations.MESHTASTIC`) — the paired board's own primary channel,
 * mirrored into a room on this phone and nowhere else (ADR 2026-09.26q3). Not a [DemoMsg]: almost nothing a
 * Knit message carries is true here. There is no Knit sender (the row sits in our sender column by
 * convention, and the attribution beside it says the words are somebody else's), no receipts, no reactions,
 * and no verified identity — only what the radio said and what the board could prove about it.
 *
 * [node] is the speaker's Meshtastic node number, rendered as the `!hex` id every Meshtastic client shows.
 * [name] is `User.long_name` as the board's NodeDB had it, or null when it had never heard the speaker named.
 * [peer] is the contact whose profile claimed [node] as their board, resolved once at ingest — an
 * attribution, not an identity, so the bubble keeps the muted styling unless [signed] is
 * `ORIGIN_SIGNED_BY_CONTACT`. [mine] is a post the local user typed here, which leaves through the board and
 * never earns a ✓✓, because nothing on the channel acks.
 */
data class DemoMeshPost(
    val id: String,
    val body: String,
    val minsAgo: Long,
    val node: Long = 0L,
    val name: String? = null,
    val hops: Int? = null,
    val snrDeci: Int? = null,
    val viaMqtt: Boolean = false,
    val peer: Slot? = null,
    val signed: Int = MessageEntity.ORIGIN_UNSIGNED,
    val mine: Boolean = false,
)

/**
 * A call's line in a DM thread (`data/message/CallRecord`, §3.12): placed by the local user when [outgoing], over the
 * mesh as a walkie session when [walkie], [minsAgo] when it began, connected for [seconds] (null: it never connected),
 * or [missed]. Seeded only in a build that wires calls — a build without them never writes one.
 */
data class DemoCallRecord(
    val id: String,
    val peer: Slot,
    val outgoing: Boolean,
    val minsAgo: Long,
    val seconds: Long? = null,
    val walkie: Boolean = false,
    val missed: Boolean = false,
)

/** A group thread: its name, its member set (which derives the group id), and its history. */
data class DemoGroup(
    val name: String,
    val members: List<Slot>,
    val messages: List<DemoMsg>,
)

/** A full marketing theme: the local profile, the contacts, the Nearby room, the DMs, and the groups. */
data class DemoScenario(
    val theme: String,
    val meName: String,
    val meStatus: String,
    val peers: List<DemoPeer>,
    val nearby: List<DemoMsg>,
    val nearbyReadMinsAgo: Long,
    val dms: List<DemoThread>,
    val group: DemoGroup,
    /**
     * Threads from strangers the local user has never answered — seeded exactly like [dms] but never
     * accepted, so they land in the Message Requests inbox and put its badge on the chat list instead of
     * showing up as chats.
     */
    val requests: List<DemoThread> = emptyList(),
    /** A group a stranger added the local user to: a request until somebody they know posts in it. */
    val requestGroup: DemoGroup? = null,
    /** Peers seeded into the blocked set, so the "Blocked users" screen has rows rather than its empty state. */
    val blocked: List<Slot> = emptyList(),
    /**
     * The Meshtastic room's history — what the demo board "heard" on its primary channel, plus one post of
     * our own. Seeded because the room's row appears the moment a board is bound ([DemoPlanes] binds one),
     * so without it every capture of the chat list carries an empty room, and the room screen itself can
     * only be photographed in its "nothing has been said yet" state.
     */
    val meshRoom: List<DemoMeshPost> = emptyList(),
    /** The calls the DM threads remember: one live call and one mesh call, so a thread shows both lines. */
    val calls: List<DemoCallRecord> = emptyList(),
    /**
     * A DM with a contact who writes in another language ([DemoMsg.lang]), for the translation captures. Seeded only
     * in a build with translation (`BuildConfig.TRANSLATE`, see [forBuild]): elsewhere nothing could translate it.
     */
    val translated: DemoThread? = null,
    /**
     * Lines appended to the verified contact's DM ([Slot.DANI]) that crossed a direct Internet link
     * ([DeliveryPlane.DirectLink], ADR 2026-10.a5yj), so the ✓✓ and the bubbles wear the link's glyph. Seeded only in
     * a build with direct links (`BuildConfig.DIRECT_LINKS`, see [forBuild]), which no shipped build is yet.
     */
    val linked: List<DemoMsg> = emptyList(),
)

/**
 * This scenario as the running build can show it: [DemoScenario.translated] joins the DMs where translation is built in,
 * and [DemoScenario.linked] joins the verified contact's DM where direct links are. A listing build (neither flag) seeds
 * exactly what it always has, so the store captures never show a feature the shipped app lacks.
 */
fun DemoScenario.forBuild(
    translate: Boolean = BuildConfig.TRANSLATE,
    directLinks: Boolean = BuildConfig.DIRECT_LINKS,
): DemoScenario {
    val withLinks =
        if (directLinks && linked.isNotEmpty()) {
            dms.map { if (it.peer == Slot.DANI) it.copy(messages = it.messages + linked) else it }
        } else {
            dms
        }
    return copy(
        dms = withLinks + listOfNotNull(translated?.takeIf { translate }),
        translated = null,
        linked = emptyList(),
    )
}

/**
 * This scenario as [slot]'s phone sees it: their profile is the local one and ours is theirs, in every thread — the
 * second phone of a two-phone film (the trailer's callee takes). Only the cast swaps; the words, the photos and the
 * times stay. An @-mention of the local user is dropped rather than moved, since the text still names the original.
 */
fun DemoScenario.mirrored(slot: Slot): DemoScenario {
    fun swap(s: Slot): Slot =
        when (s) {
            Slot.ME -> slot
            slot -> Slot.ME
            else -> s
        }

    fun DemoMsg.mirror(): DemoMsg =
        copy(
            from = swap(from),
            reactions = reactions.map { it.copy(reactor = swap(it.reactor)) },
            deliveredTo = deliveredTo.map(::swap),
            mentionsMe = false,
        )

    fun DemoGroup.mirror(): DemoGroup = copy(members = members.map(::swap), messages = messages.map { it.mirror() })

    val them = peers.first { it.slot == slot }
    return copy(
        meName = them.name,
        meStatus = them.status,
        peers = peers.map { if (it.slot == slot) it.copy(name = meName, status = meStatus) else it },
        nearby = nearby.map { it.mirror() },
        dms = dms.map { thread -> thread.copy(messages = thread.messages.map { it.mirror() }) },
        group = group.mirror(),
        requests = requests.map { thread -> thread.copy(messages = thread.messages.map { it.mirror() }) },
        requestGroup = requestGroup?.mirror(),
        calls = calls.map { if (it.peer == slot) it.copy(outgoing = !it.outgoing) else it },
    )
}

/** Returns the scenario for [theme] (the `-PdemoTheme` build value), falling back to hiking. */
fun demoScenarioFor(theme: String): DemoScenario =
    when (theme) {
        FESTIVAL_SCENARIO.theme -> FESTIVAL_SCENARIO
        else -> HIKING_SCENARIO
    }

// --- Hiking (default) --------------------------------------------------------------------------------

private val HIKING_SCENARIO =
    DemoScenario(
        theme = "hiking",
        meName = "Maya Okonkwo",
        meStatus = "On the trail 🥾",
        peers =
            listOf(
                DemoPeer(Slot.SAM, "Sam Rivera", "Trail mix enthusiast", openToChat = true),
                // Verified, open to chat, and carrying a board of her own: the one Profile Details capture then
                // holds all three — the safety number, the availability badge, and the claimed-board row.
                DemoPeer(
                    Slot.DANI,
                    "Dani Cho",
                    "Summit or bust",
                    verified = true,
                    openToChat = true,
                    loraNode = DANI_BOARD,
                    loraKey = DEMO_LORA_KEY,
                ),
                // The contact who carries a board. His profile claims it, so the post his radio put on the
                // Meshtastic room's channel resolves to him — and, signed, wears the shield.
                DemoPeer(Slot.THEO, "Theo Blake", "Mostly lost", loraNode = THEO_BOARD, loraKey = DEMO_LORA_KEY),
                DemoPeer(Slot.PRIYA, "Priya N.", "Golden hour chaser 🌅"),
                DemoPeer(Slot.JONAS, "Jonas W.", "Will hike for coffee"),
                DemoPeer(Slot.LENA, "Lena F.", "Map nerd"),
                // A second "Jonas W." — the seeded name collision that exercises the ` (Alias)` discriminator
                // (ADR 058) in the room, contacts and the mention picker. No avatar asset, on purpose.
                DemoPeer(Slot.JONAS_TWO, "Jonas W.", "The other Jonas"),
                // Strangers. Neither is verified and the local user has posted in neither thread, so both
                // stay message requests; no avatar assets, so they render the letter-circle fallback the
                // Requests inbox actually shows for someone whose profile has not arrived yet.
                DemoPeer(Slot.RIVER, "River Salas", "Just got here"),
                DemoPeer(Slot.NOAH, "Noah Adeyemi", "Ridge run 2026"),
                DemoPeer(Slot.MARLO, "Marlo K.", ""),
            ),
        nearby =
            listOf(
                DemoMsg("demo-nearby-1", Slot.THEO, "Anyone else seeing the storm roll in over the ridge? ⛈️", 95),
                DemoMsg("demo-nearby-2", Slot.PRIYA, "Yeah just felt the first drops. Heading back to camp.", 92),
                DemoMsg("demo-nearby-3", Slot.ME, "Same here — the east trail's already mud.", 90),
                DemoMsg(
                    "demo-nearby-4",
                    Slot.SAM,
                    "Trail's clear up top @Maya Okonkwo 🎉 come join us!",
                    70,
                    mentionsMe = true,
                ),
                DemoMsg(
                    "demo-nearby-5",
                    Slot.DANI,
                    "Saved you a spot by the fire at site B12 🔥",
                    66,
                    styles = listOf(DemoStyle("B12", BodyStyle.MONOSPACE)),
                ),
                // A correction, struck through the way people fix a number in a chat.
                DemoMsg(
                    "demo-nearby-6",
                    Slot.ME,
                    "On my way — give me 10 20.",
                    64,
                    styles = listOf(DemoStyle("10", BodyStyle.STRIKETHROUGH)),
                ),
                DemoMsg(
                    "demo-nearby-7",
                    Slot.LENA,
                    "Heads up: bridge near the falls is out, take the upper loop.",
                    40,
                    styles = listOf(DemoStyle("bridge near the falls is out", BodyStyle.BOLD)),
                    reactions =
                        listOf(
                            DemoReaction(Slot.SAM, "👍", 39),
                            DemoReaction(Slot.THEO, "👍", 39),
                            DemoReaction(Slot.ME, "👍", 38),
                            DemoReaction(Slot.PRIYA, "❤️", 39),
                            DemoReaction(Slot.DANI, "❤️", 38),
                        ),
                ),
                DemoMsg("demo-nearby-8", Slot.JONAS, "Good call, thanks for the warning.", 38),
                DemoMsg(
                    "demo-nearby-8b",
                    Slot.JONAS_TWO,
                    "Other Jonas here — the upper loop is icy, bring spikes.",
                    25,
                    styles = listOf(DemoStyle("bring spikes", BodyStyle.BOLD)),
                ),
                // Carried the long way: Theo is over the ridge and out of radio range, so his line came in
                // over a LoRa board. The bubble wears the board glyph.
                DemoMsg(
                    "demo-nearby-8c",
                    Slot.THEO,
                    "Made the col — no phones up here for miles, this is going out over the board 📻",
                    18,
                    via = DeliveryPlane.LoRa,
                ),
                DemoMsg("demo-nearby-9", Slot.PRIYA, "Sunset from the summit is unreal tonight 🌄", 12, image = "summit"),
                // The room's NEWEST line, deliberately: a link with the card its SENDER fetched and attached
                // (ADR 2026-09.n752) — nobody who reads it ever contacts the site. Last because a card's
                // picture loads after layout and grows its row, which shoves anything below it off a freshly
                // anchored screen; at the bottom, that growth scrolls the card INTO frame instead of out.
                DemoMsg(
                    "demo-nearby-10",
                    Slot.SAM,
                    "Ranger station just updated the closure list: https://trails.getknit.app/ridge-loop",
                    8,
                    link =
                        DemoLink(
                            url = "https://trails.getknit.app/ridge-loop",
                            title = "Ridge Loop — conditions & closures",
                            description = "Bridge out at the falls; upper loop open. Updated this morning by the ranger station.",
                            image = "ridge-card",
                        ),
                ),
            ),
        nearbyReadMinsAgo = 20,
        dms =
            listOf(
                DemoThread(
                    Slot.DANI,
                    read = true,
                    messages =
                        listOf(
                            DemoMsg("demo-dm-dani-1", Slot.DANI, "Hey! Did you make it down okay?", 180),
                            DemoMsg("demo-dm-dani-2", Slot.ME, "Yeah, just got back. That last descent was sketchy 😅", 178),
                            DemoMsg(
                                "demo-dm-dani-3",
                                Slot.DANI,
                                "Told you the trekking poles were worth it 😏",
                                176,
                                styles = listOf(DemoStyle("were", BodyStyle.ITALIC)),
                            ),
                            DemoMsg(
                                "demo-dm-dani-4",
                                Slot.ME,
                                "Fine, you were right. Same time next weekend?",
                                150,
                                styles = listOf(DemoStyle("Same time next weekend?", BodyStyle.BOLD)),
                            ),
                            DemoMsg("demo-dm-dani-5", Slot.DANI, "Absolutely. I'll bring the good coffee ☕", 148),
                            // Dani has driven home and is off the mesh entirely; her phone acknowledged this
                            // over an Internet relay, so the ✓✓ wears the globe. The one seeded relay
                            // delivery — the DM the marketing capture opens for the relay story.
                            DemoMsg("demo-dm-dani-6", Slot.ME, "Deal.", 120, via = DeliveryPlane.Internet),
                        ),
                ),
                DemoThread(
                    Slot.SAM,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg("demo-dm-sam-1", Slot.ME, "Great hiking with you today!", 30),
                            DemoMsg("demo-dm-sam-2", Slot.SAM, "Likewise! Same crew next time?", 9),
                            // A short video, tall, as a phone films one: the bubble, its poster and its length. Ahead of
                            // the bottle line, which the seeded suites read and a tall bubble below it would push off a
                            // small screen.
                            DemoMsg("demo-dm-sam-3v", Slot.SAM, "The falls on the way down 🌊", 8, video = "stream"),
                            DemoMsg("demo-dm-sam-3", Slot.SAM, "Oh and I found your water bottle 💧", 7),
                            // The one seeded voice note. It lives in this DM because `chat/samr1v00` is the
                            // route the accessibility audit and the seeded UI runs already open, so the
                            // voice bubble gets the same scrutiny the photo bubble does — and a received
                            // one, since the receive side is where the waveform is derived rather than
                            // carried.
                            DemoMsg(
                                "demo-dm-sam-4",
                                Slot.SAM,
                                "",
                                5,
                                voiceSeconds = 9,
                                transcript = "Found it by the trailhead sign. I'll leave it at the ranger station on my way out.",
                            ),
                        ),
                ),
            ),
        group =
            DemoGroup(
                name = "Trailhead Crew",
                members = listOf(Slot.ME, Slot.SAM, Slot.PRIYA, Slot.THEO),
                messages =
                    listOf(
                        DemoMsg(
                            "demo-group-1",
                            Slot.SAM,
                            "Trailhead Crew assemble! Saturday 7am?",
                            300,
                            styles = listOf(DemoStyle("Saturday 7am?", BodyStyle.BOLD)),
                        ),
                        DemoMsg("demo-group-2", Slot.PRIYA, "I'm in 🙌", 298),
                        DemoMsg("demo-group-3", Slot.THEO, "Same. Carpool from the usual spot?", 295),
                        DemoMsg(
                            "demo-group-4",
                            Slot.ME,
                            "Works for me. I'll grab snacks.",
                            290,
                            // Partial delivery, deliberately: this is the message the seeded/a11y runs open
                            // "Message info" on, so it must exercise both halves of the delivered/waiting split.
                            deliveredTo = listOf(Slot.SAM, Slot.PRIYA),
                            // Several reactors, deliberately: this is the message the seeded/a11y runs open
                            // "Message info" on, and one reactor would not exercise the emoji filter chips.
                            reactions =
                                listOf(
                                    DemoReaction(Slot.SAM, "👍", 289),
                                    DemoReaction(Slot.PRIYA, "👍", 288),
                                    DemoReaction(Slot.THEO, "❤️", 287),
                                ),
                            replyTo = "demo-group-3",
                        ),
                        DemoMsg("demo-group-5", Slot.PRIYA, "You're the best 🥟", 288),
                        // A voice note in the group as well as the DM: the group bubble is the wider one, and
                        // it is the layout the store screenshot of a group thread actually shows.
                        DemoMsg("demo-group-6", Slot.THEO, "", 240, voiceSeconds = 12),
                        // A shared position: the group is where "come find us" gets said, and the card it draws is
                        // what the seeded UI, UIAutomator and accessibility runs audit for that bubble.
                        DemoMsg("demo-group-7", Slot.SAM, "Parked at the upper lot, come find us\ngeo:37.876710,-122.245400;u=9", 200),
                        // The group's newest line is a short video, wide: the thread the listing's group shot shows.
                        DemoMsg("demo-group-8", Slot.PRIYA, "Falls are roaring after the storm 🌊", 120, video = "falls"),
                    ),
            ),
        requests =
            listOf(
                DemoThread(
                    Slot.RIVER,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg(
                                "demo-req-river-1",
                                Slot.RIVER,
                                "Hi! Saw you on the mesh at the trailhead — are you with the ridge group?",
                                55,
                            ),
                            DemoMsg("demo-req-river-2", Slot.RIVER, "No worries if not, just trying to find them 🙂", 52),
                        ),
                ),
            ),
        requestGroup =
            DemoGroup(
                name = "Ridge Run 2026",
                members = listOf(Slot.ME, Slot.RIVER, Slot.NOAH),
                messages =
                    listOf(
                        DemoMsg("demo-req-group-1", Slot.NOAH, "Adding everyone I found on the mesh — route drops Friday!", 48),
                    ),
            ),
        blocked = listOf(Slot.MARLO),
        calls =
            listOf(
                // After "Deal.": she rang back from the car. Ours, live, four minutes.
                DemoCallRecord("demo-call-dani", Slot.DANI, outgoing = true, minsAgo = 100, seconds = 252),
                // Sam called over the mesh from the col, out of every network's reach, before his messages.
                DemoCallRecord("demo-call-sam", Slot.SAM, outgoing = false, minsAgo = 14, seconds = 65, walkie = true),
            ),
        // Lena writes in Spanish; the translation captures translate her lines with the phone's own model.
        translated =
            DemoThread(
                Slot.LENA,
                read = true,
                messages =
                    listOf(
                        // Earlier in the day, so the thread fills the screen the captures open on.
                        DemoMsg(
                            "demo-dm-lena-0",
                            Slot.LENA,
                            "¿Sigues en el sendero? Dicen que va a llover esta tarde 🌧️",
                            140,
                            lang = "es",
                        ),
                        DemoMsg("demo-dm-lena-0b", Slot.ME, "Almost at the ridge. Thanks for the heads up!", 138),
                        DemoMsg("demo-dm-lena-1", Slot.LENA, "¡Hola Maya! ¿Llegaste bien al campamento? 🏕️", 46, lang = "es"),
                        DemoMsg("demo-dm-lena-2", Slot.ME, "Just got in, thanks! Dry socks at last.", 44),
                        DemoMsg(
                            "demo-dm-lena-3",
                            Slot.LENA,
                            "El puente de las cataratas sigue cerrado. Mañana toma el sendero de arriba, es más seguro.",
                            41,
                            lang = "es",
                        ),
                        DemoMsg("demo-dm-lena-4", Slot.ME, "Good to know. Upper loop it is.", 39),
                        DemoMsg("demo-dm-lena-5", Slot.LENA, "Perfecto. Nos vemos en el mirador a las siete ☕", 37, lang = "es"),
                    ),
            ),
        // Home, and too far for the radios: the photo and the thanks crossed a direct link, no relay in between.
        linked =
            listOf(
                DemoMsg("demo-dm-dani-7", Slot.DANI, "Home now. Can you send me the summit shot?", 62, via = DeliveryPlane.DirectLink),
                DemoMsg("demo-dm-dani-8", Slot.ME, "Here it is 🏔️", 60, image = "summit", via = DeliveryPlane.DirectLink),
                DemoMsg("demo-dm-dani-9", Slot.DANI, "Wow. That one's going on my wall.", 58, via = DeliveryPlane.DirectLink),
            ),
        meshRoom =
            listOf(
                // A stranger's radio, named by the board's NodeDB, three hops out: the ordinary case, and the
                // one the room's styling is built around — a name off an open channel that proves nothing.
                DemoMeshPost(
                    "demo-mesh-1",
                    "Repeater on Bald Knob is back up, coverage to the north side again.",
                    minsAgo = 84,
                    node = 0x7A41C0E2L,
                    name = "Bald Knob Relay",
                    hops = 3,
                    snrDeci = -78,
                ),
                // Off an MQTT uplink, so it may have come from anywhere on earth — the row says so.
                DemoMeshPost(
                    "demo-mesh-2",
                    "Anyone else on LongFast up here, or is it just me and the repeater?",
                    minsAgo = 61,
                    node = 0x2C90B711L,
                    name = "KJ7ZQF",
                    hops = 4,
                    snrDeci = -102,
                    viaMqtt = true,
                ),
                // Theo, resolved to a contact AND verified under the key his profile advertises (ADR
                // 2026-09.ggq4): the one post in the room that wears the shield and a Knit author's name.
                DemoMeshPost(
                    "demo-mesh-3",
                    "Made the col. No phones for miles — this is the board talking.",
                    minsAgo = 18,
                    node = THEO_BOARD,
                    name = "Knit 4b1e",
                    hops = 1,
                    snrDeci = 64,
                    peer = Slot.THEO,
                    signed = MessageEntity.ORIGIN_SIGNED_BY_CONTACT,
                ),
                // A radio the board has heard but never heard *named*, so the row falls back to the `!hex`
                // id every Meshtastic client shows — the case a name-less NodeDB entry actually produces.
                DemoMeshPost(
                    "demo-mesh-3b",
                    "Weather station at the saddle: 4C, gusting 30 km/h from the west.",
                    minsAgo = 41,
                    node = 0x5D08E4A1L,
                    hops = 2,
                    snrDeci = -35,
                ),
                // Ours, typed here and sent out through our own board. It never earns a ✓✓: nothing on the
                // channel acks.
                DemoMeshPost("demo-mesh-4", "Copy Theo — heading up the upper loop, bridge is out.", minsAgo = 14, mine = true),
                // One more heard post after ours, so the newest-anchored window opens on a full screen rather
                // than a thread that ends halfway up it.
                DemoMeshPost(
                    "demo-mesh-5",
                    "Roger. Falls trail is closed at the washout, sign is down though.",
                    minsAgo = 9,
                    node = 0x7A41C0E2L,
                    name = "Bald Knob Relay",
                    hops = 3,
                    snrDeci = -81,
                ),
            ),
    )

// --- Festival / Burning Man --------------------------------------------------------------------------

private val FESTIVAL_SCENARIO =
    DemoScenario(
        theme = "festival",
        meName = "Zara Vance",
        meStatus = "Deep playa till sunrise ✨",
        peers =
            listOf(
                DemoPeer(Slot.SAM, "Kai Brooks", "Art car captain 🚐", openToChat = true),
                // Verified, open to chat, and board-carrying, mirroring the hiking cast: one capture, all three.
                DemoPeer(
                    Slot.DANI,
                    "Luna Reyes",
                    "Find me at sunrise 🌅",
                    verified = true,
                    openToChat = true,
                    loraNode = DANI_BOARD,
                    loraKey = DEMO_LORA_KEY,
                ),
                // The contact whose profile claims a board — the attributed, signed author in the room.
                DemoPeer(Slot.THEO, "Echo Tanaka", "Sound camp till dawn 🔊", loraNode = THEO_BOARD, loraKey = DEMO_LORA_KEY),
                DemoPeer(Slot.PRIYA, "Sage Moreno", "Camp hydration officer 💧"),
                DemoPeer(Slot.JONAS, "Dex Halloran", "Will trade stickers"),
                DemoPeer(Slot.LENA, "Ravi Okafor", "Built the dome 🛖"),
                // The name collision, festival cast (ADR 058). No avatar asset, as in the hiking theme.
                DemoPeer(Slot.JONAS_TWO, "Dex Halloran", "The other Dex"),
                DemoPeer(Slot.RIVER, "Wren Halvorsen", "First burn"),
                DemoPeer(Slot.NOAH, "Cass Ibarra", "Sunrise bike posse"),
                DemoPeer(Slot.MARLO, "Marlo K.", ""),
            ),
        nearby =
            listOf(
                DemoMsg("fest-nearby-1", Slot.THEO, "Sunrise set at the Mayan temple in 20 🌅🔊", 95),
                DemoMsg("fest-nearby-2", Slot.PRIYA, "Bringing a cooler of electrolytes for anyone fading 💧", 92),
                DemoMsg("fest-nearby-3", Slot.ME, "Bless you Sage — on my way 🙏", 90),
                DemoMsg(
                    "fest-nearby-4",
                    Slot.SAM,
                    "Art car 'Dusty Rhino' rolling to deep playa @Zara Vance 🦏 hop on!",
                    70,
                    mentionsMe = true,
                ),
                DemoMsg(
                    "fest-nearby-5",
                    Slot.DANI,
                    "Saved you a cushion up top, we're at 4:30 & Esplanade 🛋️",
                    66,
                    styles = listOf(DemoStyle("4:30 & Esplanade", BodyStyle.MONOSPACE)),
                ),
                // A correction, struck through the way people fix a number in a chat.
                DemoMsg(
                    "fest-nearby-6",
                    Slot.ME,
                    "Five Two mins out, don't leave without me 🏃",
                    64,
                    styles = listOf(DemoStyle("Five", BodyStyle.STRIKETHROUGH)),
                ),
                DemoMsg(
                    "fest-nearby-7",
                    Slot.LENA,
                    "Dust storm rolling in from the west — goggles up! 🥽",
                    40,
                    styles = listOf(DemoStyle("goggles up!", BodyStyle.BOLD)),
                    reactions =
                        listOf(
                            DemoReaction(Slot.SAM, "👍", 39),
                            DemoReaction(Slot.THEO, "👍", 39),
                            DemoReaction(Slot.ME, "👍", 38),
                            DemoReaction(Slot.PRIYA, "❤️", 39),
                            DemoReaction(Slot.DANI, "❤️", 38),
                        ),
                ),
                DemoMsg("fest-nearby-8", Slot.JONAS, "Whiteout at center camp already, stay safe out there.", 38),
                DemoMsg(
                    "fest-nearby-8b",
                    Slot.JONAS_TWO,
                    "Other Dex — my bike's the one with the blue fur, not the pink.",
                    25,
                    styles = listOf(DemoStyle("blue", BodyStyle.BOLD)),
                ),
                // Deep playa, kilometres from any other phone: this one crossed a LoRa board.
                DemoMsg(
                    "fest-nearby-8c",
                    Slot.THEO,
                    "Out past the trash fence, nothing but the board out here 📻",
                    18,
                    via = DeliveryPlane.LoRa,
                ),
                DemoMsg("fest-nearby-9", Slot.PRIYA, "The glowing dragon out on the playa is unreal tonight ✨🐉", 12, image = "dragon"),
                // The link-preview card, festival side, and last for the same reason the hiking one is.
                DemoMsg(
                    "fest-nearby-10",
                    Slot.SAM,
                    "Full sunrise line-up just went up: https://playa.getknit.app/sunrise-sets",
                    8,
                    link =
                        DemoLink(
                            url = "https://playa.getknit.app/sunrise-sets",
                            title = "Sunrise Sets — the whole week",
                            description = "Every dawn set on the playa, camp by camp, with the walk time from center camp.",
                            image = "sunrise-card",
                        ),
                ),
            ),
        nearbyReadMinsAgo = 20,
        dms =
            listOf(
                DemoThread(
                    Slot.DANI,
                    read = true,
                    messages =
                        listOf(
                            DemoMsg("fest-dm-dani-1", Slot.DANI, "Did you find camp okay last night?", 180),
                            DemoMsg("fest-dm-dani-2", Slot.ME, "Eventually 😅 the playa swallowed me for an hour", 178),
                            DemoMsg(
                                "fest-dm-dani-3",
                                Slot.DANI,
                                "Told you to pin a flag on your bike 🚩",
                                176,
                                styles = listOf(DemoStyle("flag", BodyStyle.ITALIC)),
                            ),
                            DemoMsg(
                                "fest-dm-dani-4",
                                Slot.ME,
                                "Lesson learned. Sunrise set tomorrow?",
                                150,
                                styles = listOf(DemoStyle("Sunrise set tomorrow?", BodyStyle.BOLD)),
                            ),
                            DemoMsg("fest-dm-dani-5", Slot.DANI, "Always. I'll bring the good chai ☕", 148),
                            // Luna's phone acked from town, over an Internet relay — the globe on the ✓✓.
                            DemoMsg("fest-dm-dani-6", Slot.ME, "Deal.", 120, via = DeliveryPlane.Internet),
                        ),
                ),
                DemoThread(
                    Slot.SAM,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg("fest-dm-sam-1", Slot.ME, "Epic set tonight! 🔥", 30),
                            DemoMsg("fest-dm-sam-2", Slot.SAM, "Right?? Same crew at the dome tomorrow?", 9),
                            DemoMsg("fest-dm-sam-3v", Slot.SAM, "Burn barrel's lit back at camp 🔥", 8, video = "barrel"),
                            DemoMsg("fest-dm-sam-3", Slot.SAM, "Oh and I found your goggles 🥽", 7),
                            // Voice-note parity with the hiking theme: the same route (`chat/samr1v00`) that
                            // the a11y audit and the store capture open shows the voice bubble in both themes.
                            DemoMsg(
                                "fest-dm-sam-4",
                                Slot.SAM,
                                "",
                                5,
                                voiceSeconds = 9,
                                transcript = "Your goggles were by the bike rack at the dome. I'll bring them to the temple tonight.",
                            ),
                        ),
                ),
            ),
        group =
            DemoGroup(
                name = "Camp Lost Horizon",
                members = listOf(Slot.ME, Slot.SAM, Slot.PRIYA, Slot.THEO),
                messages =
                    listOf(
                        DemoMsg(
                            "fest-group-1",
                            Slot.SAM,
                            "Camp Lost Horizon meetup — Man burn at 9? 🔥",
                            300,
                            styles = listOf(DemoStyle("Man burn at 9?", BodyStyle.BOLD)),
                        ),
                        DemoMsg("fest-group-2", Slot.PRIYA, "I'm in 🙌", 298),
                        DemoMsg("fest-group-3", Slot.THEO, "Same. Meet at the bikes?", 295),
                        DemoMsg(
                            "fest-group-4",
                            Slot.ME,
                            "Works for me. I'll bring the LED totem 🔆",
                            290,
                            // Mirrors demo-group-4: the partial delivery + several reactors that make
                            // "Message info" worth capturing (and worth auditing) in either theme.
                            deliveredTo = listOf(Slot.SAM, Slot.PRIYA),
                            reactions =
                                listOf(
                                    DemoReaction(Slot.SAM, "👍", 289),
                                    DemoReaction(Slot.PRIYA, "👍", 288),
                                    DemoReaction(Slot.THEO, "❤️", 287),
                                ),
                            replyTo = "fest-group-3",
                        ),
                        DemoMsg("fest-group-5", Slot.PRIYA, "You're a legend 🔆", 288),
                        DemoMsg("fest-group-6", Slot.THEO, "", 240, voiceSeconds = 12),
                        DemoMsg("fest-group-7", Slot.PRIYA, "The light forest is going off tonight ✨", 120, video = "lights"),
                    ),
            ),
        requests =
            listOf(
                DemoThread(
                    Slot.RIVER,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg(
                                "fest-req-wren-1",
                                Slot.RIVER,
                                "Hey! First burn, found you on the mesh — is the dome open tonight?",
                                55,
                            ),
                            DemoMsg("fest-req-wren-2", Slot.RIVER, "Totally fine to ignore me, just saying hi 🙂", 52),
                        ),
                ),
            ),
        requestGroup =
            DemoGroup(
                name = "Sunrise Bike Posse",
                members = listOf(Slot.ME, Slot.RIVER, Slot.NOAH),
                messages =
                    listOf(
                        DemoMsg("fest-req-group-1", Slot.NOAH, "Rolling out at 5:30 from 7:30 & Esplanade — adding everyone nearby!", 48),
                    ),
            ),
        blocked = listOf(Slot.MARLO),
        calls =
            listOf(
                DemoCallRecord("fest-call-luna", Slot.DANI, outgoing = true, minsAgo = 100, seconds = 252),
                DemoCallRecord("fest-call-kai", Slot.SAM, outgoing = false, minsAgo = 14, seconds = 65, walkie = true),
            ),
        // Sage writes in Spanish; the translation captures translate her lines with the phone's own model.
        translated =
            DemoThread(
                Slot.PRIYA,
                read = true,
                messages =
                    listOf(
                        // Earlier in the day, so the thread fills the screen the captures open on.
                        DemoMsg(
                            "fest-dm-sage-0",
                            Slot.PRIYA,
                            "¿Necesitas agua? Tengo una nevera llena en el campamento 💧",
                            140,
                            lang = "es",
                        ),
                        DemoMsg("fest-dm-sage-0b", Slot.ME, "Yes please! I'll swing by on my bike.", 138),
                        DemoMsg("fest-dm-sage-1", Slot.PRIYA, "¡Zara! ¿Sigues en el templo? 🌅", 46, lang = "es"),
                        DemoMsg("fest-dm-sage-2", Slot.ME, "Just left, heading to the dome.", 44),
                        DemoMsg(
                            "fest-dm-sage-3",
                            Slot.PRIYA,
                            "Viene una tormenta de polvo desde el oeste. Lleva tus gafas y mucha agua.",
                            41,
                            lang = "es",
                        ),
                        DemoMsg("fest-dm-sage-4", Slot.ME, "Got them, thanks Sage 🙏", 39),
                        DemoMsg("fest-dm-sage-5", Slot.PRIYA, "Te guardo un sitio en la cúpula a medianoche ✨", 37, lang = "es"),
                    ),
            ),
        // Back in the city, and too far for the radios: the photo and the thanks crossed a direct link, no relay.
        linked =
            listOf(
                DemoMsg("fest-dm-luna-7", Slot.DANI, "Back in the city 😢 send me the dragon?", 62, via = DeliveryPlane.DirectLink),
                DemoMsg("fest-dm-luna-8", Slot.ME, "Here you go 🐉", 60, image = "dragon", via = DeliveryPlane.DirectLink),
                DemoMsg("fest-dm-luna-9", Slot.DANI, "Instant wallpaper. Miss you all already.", 58, via = DeliveryPlane.DirectLink),
            ),
        meshRoom =
            listOf(
                DemoMeshPost(
                    "fest-mesh-1",
                    "Node up at 9 o'clock plaza, solar. Should hold all week.",
                    minsAgo = 84,
                    node = 0x7A41C0E2L,
                    name = "Plaza Solar",
                    hops = 2,
                    snrDeci = -64,
                ),
                DemoMeshPost(
                    "fest-mesh-2",
                    "Testing LongFast from the trash fence — anyone copy?",
                    minsAgo = 61,
                    node = 0x2C90B711L,
                    name = "W7DUST",
                    hops = 4,
                    snrDeci = -110,
                    viaMqtt = true,
                ),
                DemoMeshPost(
                    "fest-mesh-3",
                    "Way out past the fence. No phones out here — the board's carrying this.",
                    minsAgo = 18,
                    node = THEO_BOARD,
                    name = "Knit 4b1e",
                    hops = 1,
                    snrDeci = 58,
                    peer = Slot.THEO,
                    signed = MessageEntity.ORIGIN_SIGNED_BY_CONTACT,
                ),
                DemoMeshPost(
                    "fest-mesh-3b",
                    "Temp at the fence: 41C. Drink something, all of you.",
                    minsAgo = 41,
                    node = 0x5D08E4A1L,
                    hops = 2,
                    snrDeci = -41,
                ),
                DemoMeshPost("fest-mesh-4", "Copy Echo — riding out that way at sunrise 🚲", minsAgo = 14, mine = true),
                DemoMeshPost(
                    "fest-mesh-5",
                    "Bring water for two. It is a long way back in the dark.",
                    minsAgo = 9,
                    node = 0x7A41C0E2L,
                    name = "Plaza Solar",
                    hops = 2,
                    snrDeci = -68,
                ),
            ),
    )

/**
 * The Meshtastic node number the contact-with-a-board carries in both themes (Theo / Echo), and the key their
 * profile advertises for it. Theme-independent for the same reason the relay URLs are: a node number reads the
 * same at a trailhead and on the playa. The key is illustrative — a demo build verifies nothing, it only draws
 * the verdict that was frozen on the row at ingest.
 */
private const val THEO_BOARD = 0x4B1E77A0L

/** A second contact's board, so a Profile Details capture has a claimed-board row of its own to show. */
private const val DANI_BOARD = 0x91C33FD5L
private const val DEMO_LORA_KEY = "ZGVtby1sb3JhLXNpZ25pbmcta2V5LW5vdC1yZWFsLTAwMDA="
