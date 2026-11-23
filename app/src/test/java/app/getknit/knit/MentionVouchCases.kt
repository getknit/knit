package app.getknit.knit

import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.message.MessagePlace
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.ui.chat.EVERYONE_NAME
import app.getknit.knit.ui.chat.MAX_VOUCHED_IDS
import app.getknit.knit.ui.chat.MAX_VOUCHED_NAME
import app.getknit.knit.ui.chat.MENTION_WINDOW
import app.getknit.knit.ui.chat.WindowRow
import app.getknit.knit.ui.chat.inMentionWindow
import app.getknit.knit.ui.chat.mentionSpans
import app.getknit.knit.ui.chat.mentionWindowEdge
import app.getknit.knit.ui.chat.vouchedMentions
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.peer

/**
 * The directories and cases of `vectors/mention-vouch-v1.json` (ADR 2026-11.p6dk), each with the answer the contract
 * states: which entries a row keeps and which "@" tokens its bubble styles. `MentionTextTest` checks the rule against
 * these answers; `MentionVouchVectorTest` writes the file from them, so knit-ios replays the same cases. Every name
 * uses only code points Unicode 11 assigned (ADR 2026-10.2kfr).
 */
internal object MentionVouchCases {
    /** Us: Walter. */
    val W = NodeId.derive("mention-vouch-w")
    val A = NodeId.derive("mention-vouch-a")
    val B = NodeId.derive("mention-vouch-b")

    /** A blank-named peer. */
    val G = NodeId.derive("mention-vouch-g")

    /** An id outside every directory's index. */
    val U = NodeId.derive("mention-vouch-u")

    /** "Alice" spelled with a Cyrillic small ie (U+0435): a lookalike of A's name. */
    val X = NodeId.derive("mention-vouch-x")

    /** Sixteen more ids outside the index: one row naming all of them fills the ids the rule judges by name. */
    val U16 = (1..MAX_VOUCHED_IDS).map { NodeId.derive("mention-vouch-u$it") }

    const val MY_NAME = "Walter"

    enum class Thread(
        val wire: String,
    ) {
        DIRECT("direct"),
        ROOM("room"),
        GROUP("group"),
    }

    class Directory(
        val name: String,
        val peers: List<Pair<String, String>>,
        val unknown: List<String>,
    ) {
        /** As `PeerRepository.observeDirectory()` emits it, with us as self and Content filtering off. */
        val peerDirectory: PeerDirectory by lazy { directoryOf(peers.map { (id, n) -> peer(id, n) }, me = W, myName = MY_NAME) }

        /** Every id the file gives a label for: ours, the peers', then the unknown ones. */
        val labelled: List<String> get() = listOf(W) + peers.map { it.first } + unknown
    }

    val PLAIN = Directory("plain", listOf(A to "Alice", B to "Bob", G to ""), unknown = listOf(U))
    val CLASH = Directory("clash", PLAIN.peers + (X to "Alic\u0435"), unknown = listOf(U))
    val DIRECTORIES = listOf(PLAIN, CLASH)

    class Case(
        val label: String,
        val directory: Directory,
        val body: String,
        val mentions: List<Mention>,
        /** Indexes into [mentions]. */
        val kept: List<Int>,
        /** The text of each styled span, in order. */
        val styled: List<String>,
        val sender: String = B,
        val thread: Thread = Thread.DIRECT,
        val roster: List<String>? = null,
        /** Whether the row is inside its thread's mention window ([WINDOWS] pins the window itself). */
        val inWindow: Boolean = true,
    ) {
        /** What the rule keeps, run as `ChatViewModel`'s fold runs it. */
        fun vouched(): List<Mention> =
            vouchedMentions(
                mentions = mentions,
                senderId = sender,
                me = W,
                meshcoreRoom = false,
                roster = if (thread == Thread.GROUP) roster else null,
                inWindow = inWindow,
                mentionTexts = directory.peerDirectory::mentionTexts,
            )

        /** The bubble's spans over the kept entries. */
        fun spans() = mentionSpans(body, vouched())
    }

    /** Token [i] of [id]'s alias phrase. */
    private fun t(
        id: String,
        i: Int,
    ): String = Alias.tokens(id)[i]

    private fun m(
        id: String,
        name: String,
    ) = Mention(nodeId = id, name = name)

    private val everyone = listOf(m(A, EVERYONE_NAME), m(B, EVERYONE_NAME))

    val CASES: List<Case> by lazy {
        val a0 = t(A, 0)
        val a1 = t(A, 1)
        val b0 = t(B, 0)
        val g0 = t(G, 0)
        val g1 = t(G, 1)
        val u0 = t(U, 0)
        val w0 = t(W, 0)
        val x0 = t(X, 0)
        listOf(
            Case(
                "the reader's label for the id, in any case",
                PLAIN,
                "@Alice and @ALICE",
                listOf(m(A, "Alice"), m(A, "ALICE")),
                kept = listOf(0, 1),
                styled = listOf("@Alice", "@ALICE"),
            ),
            Case(
                "the label grown by the id's own tokens, as a sender that saw a clash writes it",
                PLAIN,
                "@Alice ($a0) and @Alice ($a0 $a1)",
                listOf(m(A, "Alice ($a0)"), m(A, "Alice ($a0 $a1)")),
                kept = listOf(0, 1),
                styled = listOf("@Alice ($a0)", "@Alice ($a0 $a1)"),
            ),
            Case(
                "another id's token, another id's name and a named peer's bare alias",
                PLAIN,
                "@Alice ($b0) @Alice @$a0",
                listOf(m(A, "Alice ($b0)"), m(B, "Alice"), m(A, a0)),
                kept = emptyList(),
                styled = emptyList(),
            ),
            Case(
                "an id outside the index: its alias, not a name",
                PLAIN,
                "@Alice @$u0",
                listOf(m(U, "Alice"), m(U, u0)),
                kept = listOf(1),
                styled = listOf("@$u0"),
            ),
            Case(
                "a blank-named peer: its alias and continuations, not a name",
                PLAIN,
                "@$g0 hi @$g0 ($g1) @Greg",
                listOf(m(G, g0), m(G, "$g0 ($g1)"), m(G, "Greg")),
                kept = listOf(0, 1),
                styled = listOf("@$g0", "@$g0 ($g1)"),
            ),
            Case(
                "us, under our own label",
                PLAIN,
                "@Walter @Walter ($w0) @Wally",
                listOf(m(W, "Walter"), m(W, "Walter ($w0)"), m(W, "Wally")),
                kept = listOf(0, 1),
                styled = listOf("@Walter", "@Walter ($w0)"),
                sender = A,
            ),
            Case(
                "our own message keeps every entry",
                PLAIN,
                "@Robert",
                listOf(m(B, "Robert")),
                kept = listOf(0),
                styled = listOf("@Robert"),
                sender = W,
            ),
            Case(
                "@everyone in a group, for members",
                PLAIN,
                "@everyone hi",
                everyone,
                kept = listOf(0, 1),
                styled = listOf("@everyone"),
                thread = Thread.GROUP,
                roster = listOf(W, A, B),
            ),
            Case(
                "@everyone in a group, for ids not in its roster",
                PLAIN,
                "@everyone hi",
                everyone,
                kept = emptyList(),
                styled = emptyList(),
                thread = Thread.GROUP,
                roster = listOf(W, G),
            ),
            Case(
                "@everyone in the Nearby room",
                PLAIN,
                "@everyone hi",
                everyone,
                kept = emptyList(),
                styled = emptyList(),
                thread = Thread.ROOM,
            ),
            Case(
                "@everyone in a direct thread",
                PLAIN,
                "@everyone hi",
                everyone,
                kept = emptyList(),
                styled = emptyList(),
            ),
            Case(
                "one token, a dropped entry and a kept one",
                PLAIN,
                "@Alice hi",
                listOf(m(U, "Alice"), m(A, "Alice")),
                kept = listOf(1),
                styled = listOf("@Alice"),
            ),
            Case(
                "a kept token inside a dropped one",
                PLAIN,
                "@Alice (zz) hi",
                listOf(m(U, "Alice (zz)"), m(A, "Alice")),
                kept = listOf(1),
                styled = listOf("@Alice"),
            ),
            Case(
                "a lookalike under its own name, the name it imitates, or that name with an invisible mark",
                CLASH,
                "@Alic\u0435 @Alice @Alice\uFE0F",
                listOf(m(X, "Alic\u0435"), m(X, "Alice"), m(X, "Alice\uFE0F")),
                kept = emptyList(),
                styled = emptyList(),
            ),
            Case(
                "a lookalike under its label, in either spelling: the keys match and the token is its own",
                CLASH,
                "@Alic\u0435 ($x0) @Alice ($x0)",
                listOf(m(X, "Alic\u0435 ($x0)"), m(X, "Alice ($x0)")),
                kept = listOf(0, 1),
                styled = listOf("@Alic\u0435 ($x0)", "@Alice ($x0)"),
            ),
            Case(
                "a clash this phone sees: the bare name goes plain, the label is kept",
                CLASH,
                "@Alice @Alice ($a0)",
                listOf(m(A, "Alice"), m(A, "Alice ($a0)")),
                kept = listOf(1),
                styled = listOf("@Alice ($a0)"),
            ),
            Case(
                "a lookalike under the label of the name it imitates",
                CLASH,
                "@Alice ($a0)",
                listOf(m(X, "Alice ($a0)")),
                kept = emptyList(),
                styled = emptyList(),
            ),
            Case(
                "the seventeenth distinct id is not judged by name",
                PLAIN,
                "@x @Alice",
                U16.map { m(it, "x") } + m(A, "Alice"),
                kept = emptyList(),
                styled = emptyList(),
            ),
            Case(
                "the first distinct id is judged by name, whatever follows it",
                PLAIN,
                "@x @Alice",
                listOf(m(A, "Alice")) + U16.map { m(it, "x") },
                kept = listOf(0),
                styled = listOf("@Alice"),
            ),
            Case(
                "a name of exactly the longest length read is judged",
                PLAIN,
                "@$atBound hi",
                listOf(m(A, atBound)),
                kept = listOf(0),
                styled = listOf("@$atBound"),
            ),
            Case(
                "a name one unit longer is dropped, though it keys as the label does",
                PLAIN,
                "@$pastBound hi",
                listOf(m(A, pastBound)),
                kept = emptyList(),
                styled = emptyList(),
            ),
            Case(
                "a row outside its thread's window: the name rule does not run",
                PLAIN,
                "@Alice hi",
                listOf(m(A, "Alice")),
                kept = emptyList(),
                styled = emptyList(),
                inWindow = false,
            ),
            Case(
                "@everyone in a group, in a row outside the window: rule 3 still applies",
                PLAIN,
                "@everyone hi",
                listOf(m(A, EVERYONE_NAME)),
                kept = listOf(0),
                styled = listOf("@everyone"),
                thread = Thread.GROUP,
                roster = listOf(W, A, B),
                inWindow = false,
            ),
        )
    }

    /** One thread's stored rows and the ids of its counted rows outside the window, as the contract states them. */
    class WindowCase(
        val label: String,
        val rows: List<WindowRow>,
        val outside: List<String>,
        /** The senders on our block list (revision 5): their rows never count. */
        val blocked: List<String> = emptyList(),
    ) {
        /** The counted rows outside the window, as [mentionWindowEdge] and [inMentionWindow] place them. */
        fun computedOutside(): List<String> {
            val edge = mentionWindowEdge(rows, W, blocked.toSet())
            return rows
                .filter { it.senderId != W && it.senderId !in blocked && it.mentions > 0 }
                .filter { !inMentionWindow(MessagePlace(it.sentAt, it.id), edge) }
                .map { it.id }
        }
    }

    /** [n] counted rows "c001"… from A and B, at `sentAt` 1_000 + i, unless [sentAt] says otherwise. */
    private fun counted(
        n: Int,
        sentAt: (Int) -> Long = { 1_000L + it },
    ) = (1..n).map { WindowRow("c%03d".format(it), if (it % 2 == 0) A else B, sentAt(it), mentions = 1 + it % 3) }

    val WINDOWS: List<WindowCase> by lazy {
        val over = counted(MENTION_WINDOW + 1)
        listOf(
            WindowCase("one counted row past the window: the oldest is outside", over, outside = listOf("c001")),
            WindowCase(
                "the 200th and 201st newest share a sentAt: the smaller id is outside",
                // c001 and c002 both at 1_001: c002 is newer by its id, so c001 is the 201st.
                counted(MENTION_WINDOW + 1) { if (it <= 2) 1_001L else 1_000L + it },
                outside = listOf("c001"),
            ),
            WindowCase(
                "newer rows of ours or with no mentions never count",
                over +
                    (1..50).map {
                        if (it % 2 == 0) {
                            WindowRow("o%03d".format(it), W, 5_000L + it, mentions = 1)
                        } else {
                            WindowRow("p%03d".format(it), A, 5_000L + it, mentions = 0)
                        }
                    },
                outside = listOf("c001"),
            ),
            WindowCase("exactly the window's size: nothing is outside", counted(MENTION_WINDOW), outside = emptyList()),
            WindowCase(
                "the newest of 201 rows is from a blocked sender: it does not count, and nothing is outside",
                counted(MENTION_WINDOW) + WindowRow("x201", X, 9_000L, mentions = 1),
                outside = emptyList(),
                blocked = listOf(X),
            ),
        )
    }

    /** "Alice" padded with zero-width spaces, which [NameKey] drops, to [MAX_VOUCHED_NAME] units, and one more. */
    private val atBound = "Alice" + "\u200B".repeat(MAX_VOUCHED_NAME - "Alice".length)
    private val pastBound = atBound + "\u200B"
}
