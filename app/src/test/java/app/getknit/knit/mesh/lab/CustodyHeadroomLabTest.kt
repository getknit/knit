package app.getknit.knit.mesh.lab

import app.getknit.knit.TextLimits
import app.getknit.knit.badge.BadgeAskState
import app.getknit.knit.badge.BadgeFixtures
import app.getknit.knit.badge.HeldBadge
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.REPLY_SNIPPET_MAX
import app.getknit.knit.data.video.VideoFrames
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.StyleSpans
import app.getknit.knit.mesh.protocol.VideoInfo
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.ui.chat.everyoneMentions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * How far the largest frames an honest composer can send sit below custody's byte cap (#227): each is built by the real
 * send path ([app.getknit.knit.mesh.MeshManager.sendChat], the sealed-profile push) at every clamp the app has, and its
 * `signed` measured where custody holds it. Alice's stores take any size, so a frame over the cap is measured rather
 * than refused out of sight.
 *
 * What each frame holds at its clamps: a body of [TextLimits.MESSAGE] units of CJK (three UTF-8 bytes a unit); a video
 * poster of [VideoFrames.POSTER_MAX_BYTES] beside an attachment name of [NAME_MAX] (the composer sends one or the other;
 * both here is an upper bound); a reply naming a [TextLimits.DISPLAY_NAME] author with a [REPLY_SNIPPET_MAX] snippet;
 * [StyleSpans.MAX_SPANS] styles. A DM mentions its peer; an eight-member group frame mentions everyone and each member
 * by name. The room post binds as many mentions as its body holds: a one-character name per person, each `@name` token
 * in the body as `mentionsStillIn` requires, 1,000 people with real 26-character ids. No DM here carries inline acks:
 * the send path gives a body this long no ack slot (`MeshManager.inlineAcksFor`'s budget), and four acks on a short
 * body add well under a hundred bytes to a frame far smaller than these. The sealed profile carries every field and
 * four badges. The sizes go into the ADR; any over the cap fails here.
 */
@RunWith(RobolectricTestRunner::class)
class CustodyHeadroomLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    @Test
    fun theLargestHonestFramesFitUnderTheByteCap() =
        runBlocking {
            val limits = LabLimits(custodyMaxSignedBytes = Int.MAX_VALUE)
            val alice = lab.node("alice", limits)
            val others = (1..7).map { lab.node("member$it", limits) }
            val bob = others.first()
            val everyone = listOf(alice) + others
            // Written raw, so it goes first: the profile edits after it each await their publish, and the last carries it.
            alice.settings.setLoraBoard(node = 0xdeadbeefL, key = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=")
            everyone.forEachIndexed { n, node -> node.setDisplayName(nameOf(n)) }
            alice.setStatus(cjk(TextLimits.STATUS))
            alice.setOpenToChat(true)
            alice.setAvatar(Random(1).nextBytes(4_096))
            lab.linkAll(*others.map { alice to it }.toTypedArray())
            lab.awaitAcquainted(*everyone.toTypedArray())
            // Carol's session too, before the badges: the sealed profile goes only to a confirmed session, and the oracle
            // below reads her thread, whose profiles must agree.
            val carol = others[1]
            for (peer in listOf(bob, carol)) {
                assertTrue(alice.sendDm(peer, "hi"))
                assertTrue(peer.sendDm(alice, "hi back"))
                lab.assertConverged(listOf(alice, peer), atLeast = 2) { it.dmWith(if (it === alice) peer else alice) }
            }

            val sizes = linkedMapOf<String, Int>()
            val before = alice.custodySignedBytes().keys
            alice.badges.putHeld((1..BadgeEntry.MAX_PER_PROFILE).map { badgeFor(alice, it) }, BadgeAskState())
            alice.badges.setShow(true)
            lab.await(2 * BadgeEntry.MAX_PER_PROFILE) { bob.badgeRowsFor(alice) + carol.badgeRowsFor(alice) }
            // The sealed profile is the largest frame Alice custodied since the badges went up: a tick beside it is tiny.
            sizes["sealed profile, four badges"] =
                alice
                    .custodySignedBytes()
                    .filterKeys { it !in before }
                    .values
                    .max()
            sizes["cleartext profile"] = alice.custodySignedBytes(FrameType.PROFILE).values.max()

            val dmBody = "@${nameOf(1)} ".padEnd(TextLimits.MESSAGE, CJK)
            sizes["sealed DM"] =
                alice.sendMeasured(dmBody, alice.dmWith(bob), mentions = listOf(Mention(bob.nodeId, nameOf(1))), recipientId = bob.nodeId)

            val groupId = alice.createGroup(*others.toTypedArray())
            val group = checkNotNull(alice.groups.find(groupId)).toGroupInfo()
            val named = others.mapIndexed { i, node -> Mention(node.nodeId, nameOf(i + 1)) }
            val groupMentions = checkNotNull(everyoneMentions(group.members, alice.nodeId, emptySet())) + named
            val groupBody =
                (
                    listOf(
                        "@everyone",
                    ) + named.map { "@${it.name}" }
                ).joinToString(" ", postfix = " ").padEnd(TextLimits.MESSAGE, CJK)
            sizes["group frame, 8 members"] = alice.sendMeasured(groupBody, groupId, mentions = groupMentions, group = group)

            val crowd = (0 until TextLimits.MESSAGE / 2).map { Mention(NodeId.derive("crowd $it"), (FIRST_CJK + it).toChar().toString()) }
            val roomBody = crowd.joinToString("") { "@${it.name}" }
            sizes["room post, ${crowd.size} mentions"] = alice.sendMeasured(roomBody, Conversations.NEARBY, mentions = crowd)

            println("custody headroom (signed bytes, cap ${ForwardRepository.DEFAULT_MAX_SIGNED_BYTES}): $sizes")
            assertTrue(
                "an honest frame over the cap: $sizes",
                sizes.values.all { it <= ForwardRepository.DEFAULT_MAX_SIGNED_BYTES },
            )
            // The oracle reads a thread of plain DMs: the frames above declare a large video, which waits on a tap to download.
            assertTrue(alice.sendDm(carol, "measured"))
            lab.assertConverged(listOf(alice, carol), atLeast = 3) { it.dmWith(if (it === alice) carol else alice) }
        }

    /**
     * Sends [body] with every optional part at its clamp, as the composer would, and returns the `signed` length of the
     * frame custody holds for it.
     */
    private suspend fun LabNode.sendMeasured(
        body: String,
        conversationId: String,
        mentions: List<Mention>,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ): Int {
        val video = Random(body.length).nextBytes(8_192)
        val hash = sha256Hex(video)
        blobs.insert(hash, VIDEO_MIME, video)
        val attachment =
            AttachmentStore.Ingested(
                hash = hash,
                mime = VIDEO_MIME,
                name = cjk(NAME_MAX),
                sizeBytes = LARGE_FILE,
                video =
                    VideoInfo(
                        durationMs = 61_000,
                        width = 1_920,
                        height = 1_080,
                        poster = Random(2).nextBytes(VideoFrames.POSTER_MAX_BYTES),
                    ),
            )
        val reply = ReplyRef(FrameId.new(), authorId = nodeId, author = nameOf(0), snippet = cjk(REPLY_SNIPPET_MAX), hasAttachment = true)
        val styles = (0 until StyleSpans.MAX_SPANS).map { StyleSpan(it * STYLE_STRIDE, it * STYLE_STRIDE + 1, BodyStyle.entries[it % 4]) }
        assertTrue(manager.sendChat(body, attachment, mentions, recipientId, group, reply, styles))
        val id = ownMessageId(conversationId, body)
        lab.await(1) { if (id in custodySignedBytes()) 1 else 0 }
        return checkNotNull(custodySignedBytes()[id])
    }

    /** A badge the RFC 8032 TEST 1 key signs for [holder], the [n]th, each under its own commitment. */
    private fun badgeFor(
        holder: LabNode,
        n: Int,
    ): HeldBadge {
        val r = ByteArray(16) { (it * 7 + n).toByte() }
        val nbf = holder.now() / 1_000 - 3_600
        val bc = BadgeFixtures.bc(sub = BadgeFixtures.sub(holder.nodeId, r), nbf = nbf, exp = nbf + 30 * 86_400)
        return HeldBadge(BadgeFixtures.wrapper(bc), r)
    }

    private fun cjk(units: Int): String = CJK.toString().repeat(units)

    // A display name at its clamp, distinct per node by its last character.
    private fun nameOf(n: Int): String = cjk(TextLimits.DISPLAY_NAME - 1) + (FIRST_CJK + n).toChar()

    private companion object {
        const val VIDEO_MIME = "video/mp4"
        const val CJK = '漢'
        const val FIRST_CJK = 0x4E00
        const val NAME_MAX = 128
        const val LARGE_FILE = 50_000_000
        const val STYLE_STRIDE = 30
    }
}
