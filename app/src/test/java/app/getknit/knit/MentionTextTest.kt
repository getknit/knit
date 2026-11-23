package app.getknit.knit

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import app.getknit.knit.data.message.MessagePlace
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.moderation.NameScreen
import app.getknit.knit.moderation.ShownNames
import app.getknit.knit.ui.chat.EVERYONE_NAME
import app.getknit.knit.ui.chat.MAX_VOUCHED_IDS
import app.getknit.knit.ui.chat.MAX_VOUCHED_NAME
import app.getknit.knit.ui.chat.MENTION_WINDOW
import app.getknit.knit.ui.chat.MentionCandidate
import app.getknit.knit.ui.chat.MentionEdit
import app.getknit.knit.ui.chat.WindowRow
import app.getknit.knit.ui.chat.activeMentionQuery
import app.getknit.knit.ui.chat.annotateMessageBody
import app.getknit.knit.ui.chat.everyoneMentions
import app.getknit.knit.ui.chat.filterCandidates
import app.getknit.knit.ui.chat.findUrls
import app.getknit.knit.ui.chat.highlightMentions
import app.getknit.knit.ui.chat.inMentionWindow
import app.getknit.knit.ui.chat.keepChipsWhole
import app.getknit.knit.ui.chat.matchesEveryone
import app.getknit.knit.ui.chat.mentionSpans
import app.getknit.knit.ui.chat.mentionWindowEdge
import app.getknit.knit.ui.chat.mentionsStillIn
import app.getknit.knit.ui.chat.snapOutOfChips
import app.getknit.knit.ui.chat.vouchedMentions
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionTextTest {
    // --- activeMentionQuery ---

    @Test
    fun findsTokenWhenCursorInsideAtWord() {
        val text = "hey @Joy"
        val q = activeMentionQuery(text, text.length)
        assertEquals(4, q?.start)
        assertEquals("Joy", q?.query)
    }

    @Test
    fun triggersWhenAtIsAtStringStart() {
        val q = activeMentionQuery("@Jo", 3)
        assertEquals(0, q?.start)
        assertEquals("Jo", q?.query)
    }

    @Test
    fun bareAtYieldsEmptyQuery() {
        val q = activeMentionQuery("hi @", 4)
        assertEquals("", q?.query)
    }

    @Test
    fun closedTokenAfterSpaceIsNull() {
        // Cursor after "@Joy " — the trailing space closed the token.
        assertNull(activeMentionQuery("hi @Joy ", 8))
    }

    @Test
    fun atMidWordDoesNotTrigger() {
        assertNull(activeMentionQuery("email foo@bar", 13))
    }

    @Test
    fun nearestAtBeforeCursorWins() {
        val q = activeMentionQuery("@a @b", 5)
        assertEquals(3, q?.start)
        assertEquals("b", q?.query)
    }

    // --- filterCandidates ---

    // Aliases pinned so a query never happens to match a derived one (the picker matches alias text too).
    private val candidates =
        listOf(
            MentionCandidate("n1", "Joyful Ferret", null, alias = "SoftlyMossyLark"),
            MentionCandidate("n2", "Coral", null, alias = "GentlyPolarNewt"),
        )

    @Test
    fun filterMatchesAcrossSpacesCaseInsensitively() {
        assertEquals(listOf("n1"), filterCandidates(candidates, "joy").map { it.nodeId })
        // "ferret" lives after a space in the display name; whitespace is stripped before matching.
        assertEquals(listOf("n1"), filterCandidates(candidates, "ferret").map { it.nodeId })
    }

    @Test
    fun emptyQueryReturnsAll() {
        assertEquals(2, filterCandidates(candidates, "").size)
    }

    // --- highlightMentions ---

    private val style = SpanStyle(fontWeight = FontWeight.SemiBold)

    @Test
    fun highlightsEveryOccurrenceOfMentionedName() {
        val body = "@Coral hi @Coral"
        val out = highlightMentions(body, listOf(Mention("n2", "Coral")), style)
        assertEquals(body, out.text)
        assertEquals(2, out.spanStyles.size)
        assertEquals(0, out.spanStyles[0].start)
        assertEquals(6, out.spanStyles[0].end)
        assertEquals(10, out.spanStyles[1].start)
        assertEquals(16, out.spanStyles[1].end)
    }

    @Test
    fun longestNameWinsOverPrefix() {
        // "@Jay" must not steal the prefix of "@Jaylene".
        val body = "hi @Jaylene"
        val out =
            highlightMentions(
                body,
                listOf(Mention("a", "Jay"), Mention("b", "Jaylene")),
                style,
            )
        assertEquals(1, out.spanStyles.size)
        assertEquals(3, out.spanStyles[0].start)
        assertEquals(11, out.spanStyles[0].end) // "@Jaylene"
    }

    @Test
    fun filterMatchesTheAliasSoATypedAliasNarrowsTwoSameNamedCandidates() {
        val twins =
            listOf(
                MentionCandidate("a1", "Alice (JoyfulFerret)", null, alias = "JoyfulFerret", discriminator = "JoyfulFerret"),
                MentionCandidate("a2", "Alice (CozyJade)", null, alias = "CozyJade", discriminator = "CozyJade"),
                MentionCandidate("b", "Bob", null, alias = "SharpOnyx"),
            )
        assertEquals(listOf("a1", "a2"), filterCandidates(twins, "ali").map { it.nodeId })
        assertEquals(listOf("a2"), filterCandidates(twins, "cozy").map { it.nodeId })
        // An unsuffixed candidate's alias matches too — the picker always shows it.
        assertEquals(listOf("b"), filterCandidates(twins, "sharp").map { it.nodeId })
    }

    @Test
    fun aQueryMatchesAnAliasWithItsSpacesRemoved() {
        // An alias grown past one token carries spaces (PeerLabels), and a query never can.
        val grown =
            listOf(
                MentionCandidate(
                    "a1",
                    "Alice (ReallyJoyfulFerret QuietlyBoldCedar)",
                    null,
                    alias = "ReallyJoyfulFerret QuietlyBoldCedar",
                    discriminator = "ReallyJoyfulFerret QuietlyBoldCedar",
                ),
                MentionCandidate("b", "Bob", null, alias = "SharplyKeenOnyx"),
            )
        assertEquals(listOf("a1"), filterCandidates(grown, "ferretquietly").map { it.nodeId })
        assertEquals(listOf("a1"), filterCandidates(grown, "quietly").map { it.nodeId })
    }

    @Test
    fun aDiscriminatedTokenIsHighlightedWholeAndBeatsThePlainNamePrefix() {
        // The sender knew two Alices: one token carries the alias (ADR 058), the other is the plain name.
        val body = "hey @Alice (JoyfulFerret) and @Alice"
        val out = highlightMentions(body, listOf(Mention("a1", "Alice (JoyfulFerret)"), Mention("a2", "Alice")), style)
        val spans = out.spanStyles.sortedBy { it.start }
        assertEquals(2, spans.size)
        assertEquals(4, spans[0].start)
        assertEquals(4 + "@Alice (JoyfulFerret)".length, spans[0].end)
        assertEquals(body.length - "@Alice".length, spans[1].start)
        assertEquals(body.length, spans[1].end)
    }

    @Test
    fun theCursorAfterAnInsertedDiscriminatedTokenIsOutsideAnyMentionQuery() {
        val text = "hey @Alice (JoyfulFerret) "
        assertNull(activeMentionQuery(text, text.length))
        // A cursor placed back inside the alias part isn't a query either: the space breaks the token.
        assertNull(activeMentionQuery(text, text.length - 3))
    }

    @Test
    fun noMentionsLeavesTextUnstyled() {
        val out = highlightMentions("plain @text", emptyList(), style)
        assertEquals("plain @text", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun nameWithoutAtIsNotStyled() {
        // The body mentions "Coral" but without the literal '@', so nothing is highlighted.
        val out = highlightMentions("Coral is here", listOf(Mention("n2", "Coral")), style)
        assertTrue(out.spanStyles.isEmpty())
    }

    // --- findUrls ---

    @Test
    fun detectsHttpAndHttpsUrls() {
        assertEquals(
            listOf("http://example.com", "https://example.org/path?q=1"),
            findUrls("go http://example.com or https://example.org/path?q=1").map { it.url },
        )
    }

    @Test
    fun reportsDisplayRangeOfMatch() {
        val text = "see https://a.com now"
        val span = findUrls(text).single()
        assertEquals(4, span.start)
        assertEquals(17, span.end)
        assertEquals("https://a.com", text.substring(span.start, span.end))
    }

    @Test
    fun bareWwwLinkResolvesToHttps() {
        val span = findUrls("visit www.example.com").single()
        assertEquals("https://www.example.com", span.url)
        // The displayed range stays as written, without the synthesized scheme.
        assertEquals("www.example.com", "visit www.example.com".substring(span.start, span.end))
    }

    @Test
    fun trimsTrailingSentencePunctuation() {
        val text = "open https://a.com."
        val span = findUrls(text).single()
        assertEquals("https://a.com", span.url)
        assertEquals("https://a.com", text.substring(span.start, span.end))
    }

    @Test
    fun trimsUnbalancedClosingParenButKeepsBalancedOnes() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Mesh_(networking)",
            findUrls("(see https://en.wikipedia.org/wiki/Mesh_(networking))").single().url,
        )
    }

    @Test
    fun ignoresEmailsAndPlainText() {
        assertTrue(findUrls("ping me at foo@bar.com about it").isEmpty())
    }

    @Test
    fun aGeoUriIsNotALinkTheCardIsItsAffordance() {
        assertTrue(findUrls("at geo:37.421998,-122.084000;u=12 now").isEmpty())
        assertTrue(findUrls("geo:37.42,-122.08?q=37.42,-122.08(Home)").isEmpty())
    }

    // --- annotateMessageBody ---

    @Test
    fun annotatesBothMentionsAndLinks() {
        val out =
            annotateMessageBody(
                body = "hey @Coral see https://a.com",
                mentions = listOf(Mention("n2", "Coral")),
                mentionStyle = style,
                linkStyle = style,
            )
        // Mention is still highlighted...
        assertEquals(1, out.spanStyles.size)
        assertEquals("@Coral", out.text.substring(out.spanStyles[0].start, out.spanStyles[0].end))
        // ...and the URL is a clickable link with the right target.
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(1, links.size)
        assertEquals("https://a.com", (links[0].item as LinkAnnotation.Url).url)
        assertEquals("https://a.com", out.text.substring(links[0].start, links[0].end))
    }

    // --- mention chips ---

    private val walter = listOf(Mention("n1", "Walter"))

    @Test
    fun spansAreSortedAndLongestNameWins() {
        val spans = mentionSpans("@Jaylene and @Jay", listOf(Mention("a", "Jay"), Mention("b", "Jaylene")))
        assertEquals(listOf(TextRange(0, 8), TextRange(13, 17)), spans)
    }

    @Test
    fun backspaceAtAChipsEndTakesTheWholeChip() {
        // "hi @Walter|" — the IME deletes the "r".
        val chips = mentionSpans("hi @Walter", walter)
        val out = keepChipsWhole(chips, listOf(MentionEdit(9, 10, "")))
        assertEquals(listOf(MentionEdit(3, 10, "")), out)
    }

    @Test
    fun forwardDeleteAtAChipsStartTakesTheWholeChip() {
        val chips = mentionSpans("@Walter hi", walter)
        assertEquals(listOf(MentionEdit(0, 7, "")), keepChipsWhole(chips, listOf(MentionEdit(0, 1, ""))))
    }

    @Test
    fun aReplacementReachingIntoAChipTakesItWhole() {
        // An autocorrect swapping "Walter hi" for "Walt hey" still leaves no half name.
        val chips = mentionSpans("@Walter hi", walter)
        assertEquals(listOf(MentionEdit(0, 10, "hey")), keepChipsWhole(chips, listOf(MentionEdit(5, 10, "hey"))))
    }

    @Test
    fun typingInsideAChipLandsAfterIt() {
        val chips = mentionSpans("@Walter", walter)
        assertEquals(listOf(MentionEdit(7, 7, "x")), keepChipsWhole(chips, listOf(MentionEdit(3, 3, "x"))))
    }

    @Test
    fun editsThatLeaveChipsWholeAreKept() {
        val chips = mentionSpans("hi @Walter ok", walter)
        // Typing at either edge, deleting the space after, and deleting the whole chip all pass untouched.
        assertNull(keepChipsWhole(chips, listOf(MentionEdit(10, 10, "s"))))
        assertNull(keepChipsWhole(chips, listOf(MentionEdit(3, 3, "x"))))
        assertNull(keepChipsWhole(chips, listOf(MentionEdit(10, 11, ""))))
        assertNull(keepChipsWhole(chips, listOf(MentionEdit(3, 10, ""))))
        assertNull(keepChipsWhole(emptyList(), listOf(MentionEdit(5, 6, ""))))
    }

    @Test
    fun widenedEditsThatMeetAreMerged() {
        // "@Ann@Bob": one edit cuts the end of the first chip, another the start of the second.
        val chips = mentionSpans("@Ann@Bob", listOf(Mention("a", "Ann"), Mention("b", "Bob")))
        val out = keepChipsWhole(chips, listOf(MentionEdit(3, 4, "x"), MentionEdit(4, 5, "y")))
        assertEquals(listOf(MentionEdit(0, 8, "xy")), out)
    }

    @Test
    fun aCursorTappedInsideAChipGoesToTheNearerEdge() {
        val chips = mentionSpans("hi @Walter", walter)
        assertEquals(TextRange(3), snapOutOfChips(chips, TextRange(5), from = null))
        assertEquals(TextRange(10), snapOutOfChips(chips, TextRange(8), from = null))
        assertEquals(TextRange(1), snapOutOfChips(chips, TextRange(1), from = null))
    }

    @Test
    fun arrowKeysStepOverAChip() {
        val chips = mentionSpans("hi @Walter", walter)
        // Left from the end lands at the start, right from the start lands at the end.
        assertEquals(TextRange(3), snapOutOfChips(chips, TextRange(9), from = TextRange(10)))
        assertEquals(TextRange(10), snapOutOfChips(chips, TextRange(4), from = TextRange(3)))
    }

    @Test
    fun aSelectionGrowsToTakeEveryChipItCuts() {
        val chips = mentionSpans("hi @Walter ok", walter)
        assertEquals(TextRange(0, 10), snapOutOfChips(chips, TextRange(0, 6), from = null))
        // A backwards selection keeps its direction.
        assertEquals(TextRange(13, 3), snapOutOfChips(chips, TextRange(13, 6), from = null))
    }

    // --- @everyone (ADR 2026-10.6h2g) ---

    @Test
    fun theEveryoneRowMatchesABlankQueryAndAnyPrefixOfTheWordIgnoringCaseAndPadding() {
        assertTrue(matchesEveryone(""))
        assertTrue(matchesEveryone("  "))
        assertTrue(matchesEveryone("ev"))
        assertTrue(matchesEveryone(" EVERYONE "))
        assertTrue(matchesEveryone("everyone"))
    }

    @Test
    fun theEveryoneRowIgnoresAQueryThatIsNotAPrefixOfTheWord() {
        assertFalse(matchesEveryone("x"))
        assertFalse(matchesEveryone("one"))
        assertFalse(matchesEveryone("everyonee"))
    }

    @Test
    fun everyoneExpandsToTheRosterMinusUsAndTheBlockedInAsciiOrderEachOnce() {
        val mentions = everyoneMentions(listOf("zed", "me", "Bob", "mallory", "amy", "zed"), me = "me", blocked = setOf("mallory"))
        assertEquals(
            listOf("Bob", "amy", "zed").map { Mention(it, EVERYONE_NAME) },
            mentions,
        )
    }

    @Test
    fun everyoneIsNullWhenNobodyIsLeftToMention() {
        assertNull(everyoneMentions(listOf("me", "mallory"), me = "me", blocked = setOf("mallory")))
        assertNull(everyoneMentions(emptyList(), me = "me", blocked = emptySet()))
    }

    @Test
    fun editingTheTokenAwayDropsEveryEntryItBound() {
        val pending = listOf(Mention("a", "everyone"), Mention("b", "everyone"), Mention("c", "Carol"))
        assertEquals(pending, mentionsStillIn("@everyone and @Carol", pending))
        assertEquals(listOf(Mention("c", "Carol")), mentionsStillIn("hi @Carol", pending))
        assertEquals(emptyList<Mention>(), mentionsStillIn("hi all", pending))
    }

    // --- vouchedMentions (ADR 2026-11.p6dk) ---

    @Test
    fun everyVouchCaseKeepsAndStylesWhatTheContractStates() {
        for (case in MentionVouchCases.CASES) {
            val vouched = case.vouched()
            assertEquals(case.label, case.kept.map { case.mentions[it] }, vouched)
            assertEquals(case.label, case.styled, case.spans().map { case.body.substring(it.start, it.end) })
        }
    }

    @Test
    fun theVouchDirectoriesLabelAsTheContractStates() {
        val c = MentionVouchCases
        val plain = c.PLAIN.peerDirectory
        assertEquals(
            listOf("Walter", "Alice", "Bob", Alias.aliasFor(c.G), Alias.aliasFor(c.U)),
            c.PLAIN.labelled.map { plain.label(it).text },
        )
        val clash = c.CLASH.peerDirectory
        assertEquals("Alice (${Alias.aliasFor(c.A)})", clash.label(c.A).text)
        assertEquals("Alic\u0435 (${Alias.aliasFor(c.X)})", clash.label(c.X).text)
        assertEquals("Walter", clash.label(c.W).text)
    }

    @Test
    fun aKeptTokenInsideADroppedOneIsStyledAtItsOwnLength() {
        val case = MentionVouchCases.CASES.single { it.body == "@Alice (zz) hi" }
        assertEquals(listOf(TextRange(0, 6)), case.spans())
    }

    @Test
    fun aNameTheContentFilterHidesDrawsPlainAndItsAliasIsKept() {
        val screen = ShownNames(NameScreen(emptyList(), listOf("fuck"), emptyList(), emptyList(), emptyList()))
        val flagged = NodeId.derive("mention-vouch-flagged")
        val dir = directoryOf(listOf(peer(flagged, "Fuck")), me = MentionVouchCases.W, myName = "Walter", screen = screen)
        val alias = Alias.aliasFor(flagged)
        val mentions = listOf(Mention(flagged, "Fuck"), Mention(flagged, alias))
        val kept = vouchedMentions(mentions, MentionVouchCases.B, MentionVouchCases.W, false, null, inWindow = true, dir::mentionTexts)
        assertEquals(listOf(Mention(flagged, alias)), kept)
        // Filtering off, the name is the label again and the bare alias of a named peer is not.
        val off = directoryOf(listOf(peer(flagged, "Fuck")), me = MentionVouchCases.W, myName = "Walter")
        assertEquals(
            listOf(Mention(flagged, "Fuck")),
            vouchedMentions(mentions, MentionVouchCases.B, MentionVouchCases.W, false, null, inWindow = true, off::mentionTexts),
        )
    }

    @Test
    fun theMeshcoreRoomKeepsTheBindingOurReceiveCodeMade() {
        val me = MentionVouchCases.W
        val dir = MentionVouchCases.PLAIN.peerDirectory
        val bound = listOf(Mention(me, "[Knit 1a2b]"))
        assertEquals(
            bound,
            vouchedMentions(bound, "a-heard-speaker", me, meshcoreRoom = true, roster = null, inWindow = true, dir::mentionTexts),
        )
        // The same entry anywhere else names us under a name we do not hold.
        assertEquals(
            emptyList<Mention>(),
            vouchedMentions(bound, MentionVouchCases.B, me, meshcoreRoom = false, roster = null, inWindow = true, dir::mentionTexts),
        )
    }

    @Test
    fun everyoneIsJudgedOnlyAgainstARosterWhenOneIsGiven() {
        val c = MentionVouchCases
        val dir = c.PLAIN.peerDirectory
        val entries = listOf(Mention(c.W, EVERYONE_NAME), Mention(c.U, EVERYONE_NAME))
        // Our own id is kept even when the roster no longer lists us; an id outside it is not.
        assertEquals(
            listOf(entries[0]),
            vouchedMentions(entries, c.B, c.W, false, roster = listOf(c.A), inWindow = true, dir::mentionTexts),
        )
        // A peer whose label is "everyone" is mentioned by name outside a group, as any name is.
        val named = directoryOf(listOf(peer(c.A, EVERYONE_NAME)), me = c.W, myName = "Walter")
        val byName = listOf(Mention(c.A, EVERYONE_NAME))
        assertEquals(byName, vouchedMentions(byName, c.B, c.W, false, roster = null, inWindow = true, named::mentionTexts))
        // Not ASCII-exact, so it is a name: "EVERYONE" under nobody's label draws plain even in a group.
        val shouted = listOf(Mention(c.A, "EVERYONE"))
        assertEquals(
            emptyList<Mention>(),
            vouchedMentions(shouted, c.B, c.W, false, roster = listOf(c.W, c.A), inWindow = true, dir::mentionTexts),
        )
    }

    @Test
    fun inAGroupANonMemberNamedEveryoneDrawsPlainThoughItsLabelMatches() {
        val c = MentionVouchCases
        val named = directoryOf(listOf(peer(c.A, EVERYONE_NAME), peer(c.B, "Bob")), me = c.W, myName = "Walter")
        val self = listOf(Mention(c.A, EVERYONE_NAME))
        assertEquals(EVERYONE_NAME, named.label(c.A).text)
        assertEquals(
            emptyList<Mention>(),
            vouchedMentions(self, c.A, c.W, false, roster = listOf(c.W, c.B), inWindow = true, named::mentionTexts),
        )
        // A member is kept by rule 3, the same entry in a direct thread by its name.
        assertEquals(self, vouchedMentions(self, c.A, c.W, false, roster = listOf(c.W, c.A), inWindow = true, named::mentionTexts))
        assertEquals(self, vouchedMentions(self, c.A, c.W, false, roster = null, inWindow = true, named::mentionTexts))
    }

    @Test
    fun onlySixteenDistinctIdsAreLookedUpAndSettledEntriesDoNotCount() {
        val c = MentionVouchCases
        val asked = mutableListOf<String>()
        val lookup = { id: String ->
            asked += id
            c.PLAIN.peerDirectory.mentionTexts(id)
        }
        val fresh = (1..40).map { Mention(NodeId.derive("crafted-$it"), "a") }
        // Our own entries settled by @everyone in the group and a too-long name never take one of the sixteen.
        val settled = List(20) { Mention(c.W, EVERYONE_NAME) } + Mention(NodeId.derive("long"), "a".repeat(MAX_VOUCHED_NAME + 1))
        val mentions = settled + fresh.take(MAX_VOUCHED_IDS - 1) + Mention(c.A, "Alice") + fresh.drop(MAX_VOUCHED_IDS - 1)
        val kept = vouchedMentions(mentions, c.B, c.W, false, roster = listOf(c.W, c.A), inWindow = true, lookup)
        assertEquals(List(20) { Mention(c.W, EVERYONE_NAME) } + Mention(c.A, "Alice"), kept)
        assertEquals(MAX_VOUCHED_IDS, asked.size)
        assertEquals(MAX_VOUCHED_IDS, asked.toSet().size)
    }

    @Test
    fun anIdNamedManyTimesIsLookedUpOnce() {
        val c = MentionVouchCases
        val asked = mutableListOf<String>()
        val lookup = { id: String ->
            asked += id
            c.PLAIN.peerDirectory.mentionTexts(id)
        }
        val mentions = List(1_000) { Mention(c.A, if (it % 2 == 0) "Alice" else "Mallory") }
        val kept = vouchedMentions(mentions, c.B, c.W, false, null, inWindow = true, lookup)
        assertEquals(500, kept.size)
        assertEquals(listOf(c.A), asked)
    }

    @Test
    fun everyWindowCaseLeavesOutsideWhatTheContractStates() {
        for (case in MentionVouchCases.WINDOWS) assertEquals(case.label, case.outside, case.computedOutside())
    }

    @Test
    fun aThreadWithFewerCountedRowsThanTheWindowHasNoEdge() {
        val c = MentionVouchCases
        val rows = (1..MENTION_WINDOW - 1).map { WindowRow("r$it", c.A, it.toLong(), mentions = 1) }
        assertNull(mentionWindowEdge(rows, c.W))
        assertTrue(inMentionWindow(MessagePlace(0L, "older-than-all"), null))
        // Our own rows and rows without mentions never fill it.
        val padded = rows + (1..50).map { WindowRow("o$it", c.W, 9_000L + it, mentions = 3) } + WindowRow("p", c.A, 9_999L, 0)
        assertNull(mentionWindowEdge(padded, c.W))
    }

    @Test
    fun aRowOutsideTheWindowAsksNoLabelAndKeepsOnlyWhatRulesOneToThreeSettle() {
        val c = MentionVouchCases
        val mentions = listOf(Mention(c.A, "Alice"), Mention(c.B, EVERYONE_NAME))
        val kept =
            vouchedMentions(mentions, c.W.reversed(), c.W, false, roster = listOf(c.W, c.B), inWindow = false) {
                error("a row outside the window asks no label")
            }
        assertEquals(listOf(Mention(c.B, EVERYONE_NAME)), kept)
        // Our own row is still kept whole outside it.
        assertEquals(mentions, vouchedMentions(mentions, c.W, c.W, false, roster = null, inWindow = false) { error("never") })
    }

    @Test
    fun noMentionsKeepNothing() {
        assertEquals(
            emptyList<Mention>(),
            vouchedMentions(emptyList(), "b", "me", false, null, inWindow = true) { error("never asked") },
        )
    }
}
