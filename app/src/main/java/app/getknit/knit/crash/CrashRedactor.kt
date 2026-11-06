package app.getknit.knit.crash

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.identity.NodeId

/**
 * Strips identifying material out of a crash report before it can be read, shared, or pasted anywhere.
 *
 * Stack **frames** pass through byte-identical. A frame's class, method, file and line are constants
 * compiled into the APK — identical on every install — so they cannot carry a node id, a contact name,
 * or message text, and they are the entire diagnostic payload. The one frame that is not a constant
 * is [TraceText]'s, whose callers answer for what it carries. Everything else is treated as an
 * exception *message*, which is where identifiers do leak in practice: `BluetoothMeshTransport` puts
 * two node ids into one `IllegalStateException`, and `GallerySaver` puts a `content://` uri into an
 * `error()`.
 *
 * Redaction runs in **two phases**, both through this one function:
 *
 *  1. **Write time** ([redact] with [KnownSecrets.NONE]) — structural rules only. This runs inside the
 *     uncaught-exception handler, where the process is dying, Koin never started, and the SQLCipher
 *     database is unreachable.
 *  2. **Read/share time** ([redact] with a populated [KnownSecrets]) — the same rules plus an
 *     exact-match pass over the contact names this device actually knows. Names have no structure to
 *     key on, so nothing but an exact match can catch them, and the name list only exists once the
 *     database is readable again.
 *
 * Placeholders (`[node]`, `[path]`, …) match no rule, so redaction is **idempotent**: phase 2 can run
 * over a whole stored report, header included, without disturbing phase 1's output.
 *
 * What this guarantees: no structured identifier (node/group/blob/device id, safety number, key
 * material), no url token, no on-device path, no non-ASCII user content, and no message longer than
 * [MAX_MESSAGE_CHARS]. Phase 2 adds: no name known to this device. What it does **not** catch: a short,
 * ASCII string we have never stored — a group title typed by a peer, say. The report's own `redaction:`
 * line records which passes ran so a reader is never misled about which guarantee they hold.
 *
 * Pure Kotlin, no Android dependencies, so the whole capture path stays unit-testable on the JVM.
 */
object CrashRedactor {
    /** Longest message kept, per exception. Bulk defence against the one thing structure cannot catch. */
    const val MAX_MESSAGE_CHARS = 300

    /**
     * Returns [text] with every exception message scrubbed and every stack frame untouched. Supply
     * [secrets] to additionally remove known contact names (phase 2); the default runs structure only.
     */
    fun redact(
        text: String,
        secrets: KnownSecrets = KnownSecrets.NONE,
    ): String =
        text.lineSequence().joinToString("\n") { line ->
            when {
                isFrame(line) -> line
                THREAD_HEADER.matches(line) -> redactThreadHeader(line, secrets)
                else -> redactHeaderLine(line, secrets)
            }
        }

    /**
     * An ANR dump's thread header, `"DefaultDispatcher-worker-3" daemon prio=5 tid=24 WaitingForGcToComplete`:
     * the name is the app's to choose and is scrubbed like a message; the rest is ART's own vocabulary, kept
     * verbatim, since a long state name would otherwise read as base64 to [RULES].
     */
    private fun redactThreadHeader(
        line: String,
        secrets: KnownSecrets,
    ): String {
        val close = line.indexOf('"', startIndex = 1)
        return "\"" + scrub(line.substring(1, close), secrets) + line.substring(close)
    }

    /**
     * The last-resort rendering: every frame verbatim, every exception message discarded outright,
     * leaving only the exception class names. Used when [redact] itself throws — losing the messages is
     * always preferable to writing a raw trace to disk, and class names plus line numbers are still most
     * of a diagnosis.
     */
    fun framesOnly(trace: String): String =
        trace.lineSequence().joinToString("\n") { line ->
            if (isFrame(line)) line else classNameOnly(line)
        }

    /**
     * A stack frame — `\tat com.example.Foo.bar(Foo.kt:12)`, a `\t... 12 more` elision, or an ANR dump's
     * `native: #00 pc 0004f2c8  /apex/…/libc.so (__epoll_pwait+8)`, whose on-device paths [AnrTrace] has
     * already collapsed before anything is written.
     */
    private fun isFrame(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("at ") ||
            trimmed.startsWith("native: #") ||
            (trimmed.startsWith("... ") && trimmed.endsWith(" more"))
    }

    /**
     * Scrubs one non-frame line, preserving its indent, any `Caused by: ` / `Suppressed: ` marker, and
     * the exception class name. The marker is removed *before* the `": "` split — otherwise the split
     * lands inside `Caused by` and the class name gets scrubbed along with the message.
     */
    private fun redactHeaderLine(
        line: String,
        secrets: KnownSecrets,
    ): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val marker = MARKERS.firstOrNull { rest.startsWith(it) }.orEmpty()
        val tail = rest.removePrefix(marker)
        val cut = tail.indexOf(": ")
        if (cut > 0 && CLASS_NAME.matches(tail.substring(0, cut))) {
            return indent + marker + tail.take(cut + 2) + scrub(tail.substring(cut + 2), secrets)
        }
        return indent + marker + scrub(tail, secrets)
    }

    /** Keeps the indent, marker and class name of a header line and drops its message entirely. */
    private fun classNameOnly(line: String): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val marker = MARKERS.firstOrNull { rest.startsWith(it) }.orEmpty()
        val tail = rest.removePrefix(marker)
        val cut = tail.indexOf(": ")
        if (cut > 0 && CLASS_NAME.matches(tail.substring(0, cut))) {
            return indent + marker + tail.substring(0, cut)
        }
        return indent + marker + tail
    }

    /** Applies every structural rule, the length cap, then the known-name pass. */
    private fun scrub(
        message: String,
        secrets: KnownSecrets,
    ): String {
        var out = message
        for ((rule, replacement) in RULES) {
            out = rule.replace(out, replacement)
        }
        if (out.length > MAX_MESSAGE_CHARS) {
            out = out.take(MAX_MESSAGE_CHARS) + "...(+${out.length - MAX_MESSAGE_CHARS})"
        }
        val names = secrets.pattern ?: return out
        return names.replace(out, "[name]")
    }

    private val MARKERS = listOf("Caused by: ", "Suppressed: ")

    /** `"name" [daemon ]prio=N tid=N State` — the rest after the name is ART's, never the app's. */
    private val THREAD_HEADER = Regex("""^"[^"]*"( daemon)? prio=\d+ tid=\d+ \w+$""")

    /** Matches a dotted identifier — an R8-mangled `a.b.c` as readily as a real class name. */
    private val CLASS_NAME = Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""")

    /**
     * Ordered structural rules. Order is load-bearing: the node-id rule runs before the generic hex rule
     * so an id gets the more specific label, and the url rule runs before the base64 rule so a spool
     * bearer token is caught as a url rather than as an opaque blob.
     *
     * The id patterns are built from the real constants ([NodeId.ALPHABET], [NodeId.LENGTH],
     * [Conversations.GROUP_ID_PREFIX]) so this can never drift from the format it is meant to catch.
     */
    private val RULES: List<Pair<Regex, (MatchResult) -> String>> =
        listOf(
            // A locally-built AOSP/LineageOS tree sets Build.VERSION.INCREMENTAL to eng.<user>.<ts>, so
            // the otherwise-harmless fingerprint can carry the ROM builder's username.
            Regex("""\beng\.[A-Za-z0-9_.-]+\.\d+\b""") to { _: MatchResult -> "eng.[user].[ts]" },
            Regex("""\b(content|file)://\S*""") to { m: MatchResult -> "${m.groupValues[1]}://[uri]" },
            // Keep scheme and host (SpoolUrl.host semantics), drop the path and the ?k= bearer with it.
            // The scheme is matched case-insensitively because SpoolUrl accepts one: a `WSS://` relay that
            // read as "no scheme here" would carry its bearer token into a crash report intact.
            Regex("""\b((?i:wss?|https?))://([A-Za-z0-9.\-:\[\]]+)(\S*)""") to { m: MatchResult ->
                val authority = "${m.groupValues[1]}://${m.groupValues[2]}"
                if (m.groupValues[3].isEmpty()) authority else "$authority/[path]"
            },
            Regex("""(?<![\w/])/(?:data|storage|sdcard|mnt)/\S*""") to { _: MatchResult -> "[path]" },
            // SafetyNumber.compute's shape: eight space-separated five-digit groups. Four already
            // uniquely identify a pair, so match at four rather than insisting on all eight.
            Regex("""\b\d{5}(?:\s+\d{5}){3,}\b""") to { _: MatchResult -> "[safety-number]" },
            Regex("""\b${Conversations.GROUP_ID_PREFIX}[0-9a-f]{24}\b""") to { _: MatchResult -> "[group]" },
            Regex(
                "(?<![${NodeId.ALPHABET}])[${NodeId.ALPHABET}]{${NodeId.LENGTH}}(?![${NodeId.ALPHABET}])",
            ) to { _: MatchResult -> "[node]" },
            // Blob/attachment sha256 (64), DeviceTag (16), raw key material.
            Regex("""\b[0-9a-fA-F]{16,}\b""") to { _: MatchResult -> "[hex]" },
            // PublicKeyBundle.encoded, prekey pub/sig, sealed ciphertext in a wire-decode message.
            Regex("""\b[A-Za-z0-9+/]{24,}={0,2}""") to { _: MatchResult -> "[b64]" },
            // The only structural handle on typed message text: JVM exception messages are ASCII, while
            // emoji, CJK, Cyrillic and accented names are not. Take the whole whitespace-delimited token
            // so "café" goes whole rather than leaving "caf".
            Regex("""\S*[^\p{ASCII}]\S*""") to { _: MatchResult -> "[text]" },
        )
}

/**
 * The contact names this device knows, for [CrashRedactor]'s phase-2 pass. Empty ([NONE]) at crash
 * time, because the names live in the SQLCipher database and DataStore, neither of which a dying
 * process can reach.
 */
data class KnownSecrets(
    val names: Set<String>,
) {
    /**
     * One pre-compiled alternation rather than a regex per name — a few hundred peers against a few
     * hundred lines would otherwise mean tens of thousands of compiles.
     *
     * Longest-first so `Anna` wins over `Ann`; word-boundary guarded because a peer named `Bob` must not
     * mangle `Bobcat`, which is a real entry in `ALIAS_NOUNS`; and names shorter than [MIN_NAME_CHARS]
     * are dropped because a two-letter name would shred ordinary machine text.
     */
    internal val pattern: Regex? =
        names
            .filter { it.length >= MIN_NAME_CHARS }
            .sortedByDescending { it.length }
            .take(MAX_NAMES)
            .takeIf { it.isNotEmpty() }
            ?.let { kept ->
                Regex(
                    """(?<!\w)(?:${kept.joinToString("|") { Regex.escape(it) }})(?!\w)""",
                    RegexOption.IGNORE_CASE,
                )
            }

    companion object {
        /** Nothing known — the crash-time default. */
        val NONE = KnownSecrets(emptySet())

        private const val MIN_NAME_CHARS = 3
        private const val MAX_NAMES = 500
    }
}
