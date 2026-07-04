package app.getknit.knit.identity

/**
 * Turns a stable [Identity] node id into a friendly "AdjectiveNoun" alias (PascalCase, no digits) —
 * e.g. `EnlightenedZebra`, `ZealousBook` — so a peer who hasn't set a profile name shows something
 * human-readable instead of a raw 8-char id.
 *
 * The mapping is a pure, deterministic function of the node id, so **every device derives the same
 * alias for the same node id** with no extra exchange: there is nothing to broadcast or persist, and
 * the wire format / database stay untouched (see `docs/ARCHITECTURE.md` / the alias plan).
 *
 * Avoiding offensive output is a *pairwise* problem ("FatCow" is offensive even though neither word
 * is), so it is handled in three layers:
 *  1. [ADJECTIVES] are positive/neutral only — no body, appearance, identity, or political terms —
 *     which removes the entire "insult + animal" class at the source.
 *  2. [NOUNS] are neutral (animals, nature, gems, objects), avoiding crude slang.
 *  3. [BLOCKED] is a tiny pairwise safety net; if a candidate lands in it we deterministically
 *     re-roll the hash and try again.
 *
 * Pure Kotlin with no Android dependencies so it is unit-tested on the JVM (see `AliasTest`).
 */
object Alias {
    /** The friendly alias for [nodeId], stable across calls and across devices. */
    fun aliasFor(nodeId: String): String = aliasForExcluding(nodeId, BLOCKED)

    /**
     * Core generator with an injectable [blocked] set so the re-roll path is unit-testable.
     * Splits one 64-bit FNV-1a hash into two independent indices (low/high 32 bits) to pick an
     * adjective and a noun; if the pair is [blocked], advances the hash and retries.
     */
    internal fun aliasForExcluding(
        nodeId: String,
        blocked: Set<String>,
    ): String {
        val bytes = nodeId.encodeToByteArray()
        var h = fnv1a(FNV_OFFSET, bytes)
        repeat(MAX_REROLLS) {
            val candidate = combine(h)
            if (candidate !in blocked) return candidate
            // Re-roll deterministically: re-hash the id using the current hash as the seed.
            h = fnv1a(h, bytes)
        }
        // Effectively unreachable with curated lists; return a stable last resort.
        return combine(h)
    }

    @Suppress("MagicNumber") // shr 32 splits the 64-bit hash into independent high/low 32-bit halves
    private fun combine(h: ULong): String {
        val adjIdx = ((h and 0xFFFFFFFFuL) % ADJECTIVES.size.toULong()).toInt()
        val nounIdx = ((h shr 32) % NOUNS.size.toULong()).toInt()
        return ADJECTIVES[adjIdx] + NOUNS[nounIdx]
    }

    // FNV-1a 64-bit — fully specified, so the result is identical on every device and the JVM test
    // host (unlike String.hashCode()). The `and 0xFFuL` mask avoids byte sign-extension.
    private const val FNV_OFFSET = 0xcbf29ce484222325uL
    private const val FNV_PRIME = 0x00000100000001b3uL
    private const val MAX_REROLLS = 8

    private fun fnv1a(
        seed: ULong,
        bytes: ByteArray,
    ): ULong {
        var h = seed
        for (b in bytes) {
            h = (h xor (b.toULong() and 0xFFuL)) * FNV_PRIME
        }
        return h
    }

    /**
     * Positive/neutral adjectives, already capitalized so concatenation yields PascalCase directly.
     * Curated to be inoffensive in front of any [NOUNS] entry. Disjoint from [NOUNS] to avoid
     * accidental "WordWord" repeats. List size is not load-bearing (the modulo bias over a 32-bit
     * range is negligible); add words freely, just keep them clean.
     */
    private val ADJECTIVES =
        listOf(
            "Able",
            "Agile",
            "Ample",
            "Arctic",
            "Ardent",
            "Azure",
            "Balmy",
            "Blissful",
            "Bold",
            "Brave",
            "Breezy",
            "Bright",
            "Brisk",
            "Bubbly",
            "Calm",
            "Caring",
            "Cheerful",
            "Chipper",
            "Civic",
            "Classy",
            "Clever",
            "Coastal",
            "Cosmic",
            "Cozy",
            "Crafty",
            "Crimson",
            "Crisp",
            "Crystal",
            "Curious",
            "Dapper",
            "Daring",
            "Dazzling",
            "Deft",
            "Dreamy",
            "Driven",
            "Dynamic",
            "Eager",
            "Earnest",
            "Elated",
            "Electric",
            "Elegant",
            "Emerald",
            "Enlightened",
            "Epic",
            "Fabled",
            "Fair",
            "Fancy",
            "Fearless",
            "Festive",
            "Fleet",
            "Fluffy",
            "Fond",
            "Frosty",
            "Gallant",
            "Gentle",
            "Gilded",
            "Gleaming",
            "Glowing",
            "Golden",
            "Graceful",
            "Grand",
            "Grassy",
            "Hardy",
            "Hearty",
            "Helpful",
            "Heroic",
            "Honest",
            "Hopeful",
            "Humble",
            "Ideal",
            "Indigo",
            "Inky",
            "Intrepid",
            "Ivory",
            "Jaunty",
            "Jolly",
            "Jovial",
            "Joyful",
            "Keen",
            "Kind",
            "Kindly",
            "Lively",
            "Loyal",
            "Lucid",
            "Lucky",
            "Lunar",
            "Lush",
            "Magic",
            "Mellow",
            "Merry",
            "Mighty",
            "Mild",
            "Minty",
            "Misty",
            "Modern",
            "Modest",
            "Mossy",
            "Neat",
            "Nifty",
            "Nimble",
            "Noble",
            "Pastel",
            "Patient",
            "Peaceful",
            "Pearly",
            "Perky",
            "Placid",
            "Plucky",
            "Plush",
            "Polar",
            "Polished",
            "Posh",
            "Prime",
            "Pristine",
            "Proud",
            "Quaint",
            "Quick",
            "Quiet",
            "Quirky",
            "Radiant",
            "Rapid",
            "Regal",
            "Robust",
            "Rosy",
            "Royal",
            "Rugged",
            "Rustic",
            "Sage",
            "Sandy",
            "Scarlet",
            "Serene",
            "Sharp",
            "Shining",
            "Shiny",
            "Silent",
            "Silken",
            "Silver",
            "Sleek",
            "Smart",
            "Smiling",
            "Smooth",
            "Snappy",
            "Snowy",
            "Solar",
            "Sparkly",
            "Spirited",
            "Spry",
            "Stalwart",
            "Starry",
            "Steady",
            "Stellar",
            "Sterling",
            "Stoic",
            "Sturdy",
            "Suave",
            "Sunny",
            "Sunlit",
            "Swift",
            "Tidy",
            "Tranquil",
            "Trusty",
            "Upbeat",
            "Valiant",
            "Velvet",
            "Verdant",
            "Vibrant",
            "Vivid",
            "Warm",
            "Whimsical",
            "Wily",
            "Winsome",
            "Wise",
            "Witty",
            "Woolly",
            "Zany",
            "Zealous",
            "Zesty",
            "Zippy",
        )

    /** Neutral nouns (animals, nature, gems, objects), capitalized. Disjoint from [ADJECTIVES]. */
    private val NOUNS =
        listOf(
            "Otter",
            "Falcon",
            "Fox",
            "Owl",
            "Wolf",
            "Bear",
            "Hawk",
            "Heron",
            "Sparrow",
            "Finch",
            "Robin",
            "Wren",
            "Lark",
            "Crane",
            "Swan",
            "Dove",
            "Raven",
            "Badger",
            "Bison",
            "Lynx",
            "Marten",
            "Mole",
            "Moose",
            "Newt",
            "Panda",
            "Puffin",
            "Quail",
            "Stoat",
            "Tapir",
            "Vole",
            "Yak",
            "Zebra",
            "Gazelle",
            "Ibex",
            "Koala",
            "Lemur",
            "Llama",
            "Manatee",
            "Meerkat",
            "Mongoose",
            "Narwhal",
            "Ocelot",
            "Pangolin",
            "Walrus",
            "Wombat",
            "Antelope",
            "Bobcat",
            "Caribou",
            "Cheetah",
            "Cobra",
            "Dolphin",
            "Gecko",
            "Iguana",
            "Jackal",
            "Jaguar",
            "Kestrel",
            "Kingfisher",
            "Kiwi",
            "Magpie",
            "Mallard",
            "Mantis",
            "Mink",
            "Octopus",
            "Osprey",
            "Pelican",
            "Penguin",
            "Pheasant",
            "Plover",
            "Python",
            "Salmon",
            "Seal",
            "Dormouse",
            "Skink",
            "Starling",
            "Stingray",
            "Tortoise",
            "Toucan",
            "Turtle",
            "Urchin",
            "Viper",
            "Marmot",
            "Whale",
            "Wolverine",
            "Ferret",
            "Hedgehog",
            "Hare",
            "Rabbit",
            "Squirrel",
            "Chipmunk",
            "Raccoon",
            "Armadillo",
            "Sloth",
            "Bluejay",
            "Cardinal",
            "Chickadee",
            "Nightingale",
            "Oriole",
            "Swallow",
            "Maple",
            "Willow",
            "Cedar",
            "Birch",
            "Aspen",
            "Alder",
            "Spruce",
            "Pine",
            "Fern",
            "Ivy",
            "Moss",
            "Clover",
            "Daisy",
            "Lily",
            "Lotus",
            "Poppy",
            "Tulip",
            "Aster",
            "River",
            "Brook",
            "Creek",
            "Lake",
            "Bay",
            "Cove",
            "Reef",
            "Dune",
            "Mesa",
            "Canyon",
            "Summit",
            "Ridge",
            "Glade",
            "Meadow",
            "Harbor",
            "Lagoon",
            "Comet",
            "Nova",
            "Nebula",
            "Quasar",
            "Pulsar",
            "Meteor",
            "Aurora",
            "Eclipse",
            "Galaxy",
            "Cosmos",
            "Zenith",
            "Ember",
            "Cinder",
            "Spark",
            "Flame",
            "Frost",
            "Mist",
            "Cloud",
            "Storm",
            "Thunder",
            "Breeze",
            "Zephyr",
            "Lantern",
            "Beacon",
            "Anchor",
            "Compass",
            "Sail",
            "Pixel",
            "Cipher",
            "Vector",
            "Quartz",
            "Onyx",
            "Opal",
            "Jade",
            "Topaz",
            "Amber",
            "Garnet",
            "Coral",
            "Pearl",
            "Agate",
            "Beryl",
            "Flint",
            "Slate",
            "Marble",
            "Granite",
            "Copper",
            "Cobalt",
            "Pewter",
            "Bronze",
            "Book",
        )

    /**
     * Pairwise safety net for combinations that read poorly despite curated lists. Kept tiny; add
     * exact "AdjectiveNoun" strings here if any slip through review. A hit triggers a deterministic
     * re-roll in [aliasForExcluding].
     */
    private val BLOCKED: Set<String> = emptySet()
}

/**
 * The name to show for a person: their stored profile [storedName] if they set one, otherwise the
 * friendly alias derived from [nodeId]. Replaces the old `.ifBlank { nodeId }` fallbacks scattered
 * across the UI and notifications.
 */
fun displayNameFor(
    storedName: String?,
    nodeId: String,
): String = storedName?.takeIf { it.isNotBlank() } ?: Alias.aliasFor(nodeId)
