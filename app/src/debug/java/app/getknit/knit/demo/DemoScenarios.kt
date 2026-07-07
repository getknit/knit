@file:Suppress("MagicNumber")

package app.getknit.knit.demo

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
 */

/** A conversation participant. [ME] is the local profile; the rest map to the stable demo node ids in
 *  [DemoSeeder] (so [DemoSeeder.ONLINE_NODE_IDS] and the fake transport stay theme-independent). */
enum class Slot { ME, SAM, DANI, THEO, PRIYA, JONAS, LENA }

/** A reaction left on a message: who reacted, the emoji, and how long ago. */
data class DemoReaction(
    val reactor: Slot,
    val emoji: String,
    val minsAgo: Long,
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
    val replyTo: String? = null,
    val image: String? = null,
    // MIME of [image]: "image/jpeg" for the bundled scene photos, "image/webp" for the animated GIF beat
    // (an animated WebP under demo/images/<theme>/<image>.webp, played by Coil's AnimatedImageDecoder).
    val imageMime: String = "image/jpeg",
)

/** A peer contact. [verified] pins a (fake) key + out-of-band confirmation so the DM header shows the
 *  verified badge. The avatar is loaded from `demo/avatars/<theme>/<nodeId>.jpg`. */
data class DemoPeer(
    val slot: Slot,
    val name: String,
    val status: String,
    val verified: Boolean = false,
)

/** A 1:1 DM thread with [peer]. [read] true seeds a read watermark (no unread badge); false leaves the
 *  peer's messages unread so the chat list shows a count. */
data class DemoThread(
    val peer: Slot,
    val read: Boolean,
    val messages: List<DemoMsg>,
)

/** A full marketing theme: the local profile, the contacts, the Nearby room, the DMs, and one group. */
data class DemoScenario(
    val theme: String,
    val meName: String,
    val meStatus: String,
    val peers: List<DemoPeer>,
    val nearby: List<DemoMsg>,
    val nearbyReadMinsAgo: Long,
    val dms: List<DemoThread>,
    val groupName: String,
    val groupMembers: List<Slot>,
    val groupMessages: List<DemoMsg>,
)

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
                DemoPeer(Slot.SAM, "Sam Rivera", "Trail mix enthusiast"),
                DemoPeer(Slot.DANI, "Dani Cho", "Summit or bust", verified = true),
                DemoPeer(Slot.THEO, "Theo Blake", "Mostly lost"),
                DemoPeer(Slot.PRIYA, "Priya N.", "Golden hour chaser 🌅"),
                DemoPeer(Slot.JONAS, "Jonas W.", "Will hike for coffee"),
                DemoPeer(Slot.LENA, "Lena F.", "Map nerd"),
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
                DemoMsg("demo-nearby-5", Slot.DANI, "Saved you a spot by the fire 🔥", 66),
                DemoMsg("demo-nearby-6", Slot.ME, "On my way — give me 20.", 64),
                DemoMsg(
                    "demo-nearby-7",
                    Slot.LENA,
                    "Heads up: bridge near the falls is out, take the upper loop.",
                    40,
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
                DemoMsg("demo-nearby-9", Slot.PRIYA, "Sunset from the summit is unreal tonight 🌄", 12, image = "summit"),
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
                            DemoMsg("demo-dm-dani-3", Slot.DANI, "Told you the trekking poles were worth it 😏", 176),
                            DemoMsg("demo-dm-dani-4", Slot.ME, "Fine, you were right. Same time next weekend?", 150),
                            DemoMsg("demo-dm-dani-5", Slot.DANI, "Absolutely. I'll bring the good coffee ☕", 148),
                            DemoMsg("demo-dm-dani-6", Slot.ME, "Deal.", 120),
                        ),
                ),
                DemoThread(
                    Slot.SAM,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg("demo-dm-sam-1", Slot.ME, "Great hiking with you today!", 30),
                            DemoMsg("demo-dm-sam-2", Slot.SAM, "Likewise! Same crew next time?", 9),
                            DemoMsg("demo-dm-sam-3", Slot.SAM, "Oh and I found your water bottle 💧", 7),
                        ),
                ),
            ),
        groupName = "Trailhead Crew",
        groupMembers = listOf(Slot.ME, Slot.SAM, Slot.PRIYA, Slot.THEO),
        groupMessages =
            listOf(
                DemoMsg("demo-group-1", Slot.SAM, "Trailhead Crew assemble! Saturday 7am?", 300),
                DemoMsg("demo-group-2", Slot.PRIYA, "I'm in 🙌", 298),
                DemoMsg("demo-group-3", Slot.THEO, "Same. Carpool from the usual spot?", 295),
                DemoMsg(
                    "demo-group-4",
                    Slot.ME,
                    "Works for me. I'll grab snacks.",
                    290,
                    reactions = listOf(DemoReaction(Slot.SAM, "👍", 289)),
                    replyTo = "demo-group-3",
                ),
                DemoMsg("demo-group-5", Slot.PRIYA, "You're the best 🥟", 288),
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
                DemoPeer(Slot.SAM, "Kai Brooks", "Art car captain 🚐"),
                DemoPeer(Slot.DANI, "Luna Reyes", "Find me at sunrise 🌅", verified = true),
                DemoPeer(Slot.THEO, "Echo Tanaka", "Sound camp till dawn 🔊"),
                DemoPeer(Slot.PRIYA, "Sage Moreno", "Camp hydration officer 💧"),
                DemoPeer(Slot.JONAS, "Dex Halloran", "Will trade stickers"),
                DemoPeer(Slot.LENA, "Ravi Okafor", "Built the dome 🛖"),
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
                DemoMsg("fest-nearby-5", Slot.DANI, "Saved you a cushion up top 🛋️", 66),
                DemoMsg("fest-nearby-6", Slot.ME, "Two mins out, don't leave without me 🏃", 64),
                DemoMsg(
                    "fest-nearby-7",
                    Slot.LENA,
                    "Dust storm rolling in from the west — goggles up! 🥽",
                    40,
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
                DemoMsg("fest-nearby-9", Slot.PRIYA, "The glowing dragon out on the playa is unreal tonight ✨🐉", 12, image = "dragon"),
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
                            DemoMsg("fest-dm-dani-3", Slot.DANI, "Told you to pin a flag on your bike 🚩", 176),
                            DemoMsg("fest-dm-dani-4", Slot.ME, "Lesson learned. Sunrise set tomorrow?", 150),
                            DemoMsg("fest-dm-dani-5", Slot.DANI, "Always. I'll bring the good chai ☕", 148),
                            DemoMsg("fest-dm-dani-6", Slot.ME, "Deal.", 120),
                        ),
                ),
                DemoThread(
                    Slot.SAM,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg("fest-dm-sam-1", Slot.ME, "Epic set tonight! 🔥", 30),
                            DemoMsg("fest-dm-sam-2", Slot.SAM, "Right?? Same crew at the dome tomorrow?", 9),
                            DemoMsg("fest-dm-sam-3", Slot.SAM, "Oh and I found your goggles 🥽", 7),
                        ),
                ),
            ),
        groupName = "Camp Lost Horizon",
        groupMembers = listOf(Slot.ME, Slot.SAM, Slot.PRIYA, Slot.THEO),
        groupMessages =
            listOf(
                DemoMsg("fest-group-1", Slot.SAM, "Camp Lost Horizon meetup — Man burn at 9? 🔥", 300),
                DemoMsg("fest-group-2", Slot.PRIYA, "I'm in 🙌", 298),
                DemoMsg("fest-group-3", Slot.THEO, "Same. Meet at the bikes?", 295),
                DemoMsg(
                    "fest-group-4",
                    Slot.ME,
                    "Works for me. I'll bring the LED totem 🔆",
                    290,
                    reactions = listOf(DemoReaction(Slot.SAM, "👍", 289)),
                    replyTo = "fest-group-3",
                ),
                DemoMsg("fest-group-5", Slot.PRIYA, "You're a legend 🔆", 288),
            ),
    )
