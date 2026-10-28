package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.mesh.spool.FakeSpool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Receive-side screening of pictures that reach a member over an Internet relay alone (#109): a group photo or an
 * avatar the spool fetched is screened exactly as a radio pull is (`MeshBlobStore.ingest`), and one this node's
 * screening refused, with content filtering on (the default), is neither shown nor fetched back. The nodes mind
 * every picture through `LabNode.picturesExplicit`; the oracle allows the one difference that leaves — a refused
 * avatar the viewer does not show. Slow like `InternetPlaneLabTest`, for the same reasons ([MeshLab.SPOOL_AWAIT_MS]).
 */
@RunWith(RobolectricTestRunner::class)
class RelayScreeningLabTest {
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

    /**
     * #109: a group photo that reaches a member over the relay alone is screened as a radio pull is. Carol,
     * relay-only and minding the picture, decides on it — the group goes on advertising what it decided — but
     * never shows it, and the bytes she dropped are not fetched back on a fresh session (no custody row pins a
     * group photo, so every new session would fetch it again). Before the fix the spool stored the photo with a
     * bare insert, no verdict existed, and Carol showed it.
     */
    @Test
    fun aGroupPhotoOnlyTheRelayDeliversIsScreenedAndARefusedOneIsNeitherShownNorFetchedAgain() =
        runBlocking {
            val spool = FakeSpool()
            // Attachment presence is only asked (`ahave`), never pushed: a first look before alice's upload lands waits
            // out the re-look, which no poke reaches. Only that timer is cut; the timed round stays at the field value.
            val relooks = LabLimits().copy(spoolAttachmentRetryMs = MeshLab.CALENDAR_ROUND_MS)
            val (alice, bob, carol) = lab.threeOnOneRelay(spool, relooks)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded together"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            lab.awaitGroupScope(alice, groupId, bob, carol)
            lab.awaitGroupScope(carol, groupId, alice, bob)
            carol.picturesExplicit = true

            val photo = Random(109).nextBytes(4_096)
            val hash = sha256Hex(photo)
            alice.setGroupPhoto(groupId, photo)
            // Decided on the frame, fetched over the relay, screened, stored, refused and its bytes dropped. The
            // screen runs before the store, so "flagged and not held" is also true in the gap between the two;
            // the pull counter moves after the store, which makes "not held" mean dropped.
            assertTrue(
                "carol never refused the photo the relay brought: ${carol.group(groupId)}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    val fetched = carol.metrics.spool().spoolAttachPulled > 0
                    val refused = carol.group(groupId)?.photoHash == hash && carol.imageFlagged(hash)
                    if (fetched && refused && !carol.blobs.exists(hash)) 1 else 0
                },
            )
            assertNotEquals("a refused photo is never shown", hash, carol.group(groupId)?.photoShownHash)
            val pulled = carol.metrics.spool().spoolAttachPulled

            // A fresh session forgets what was settled, so it looks at the photo again — and finds it refused. That
            // skip is the event to wait on: rounds walk custody newest first, so a marker picture posted now could
            // be fetched ahead of the photo's second look in the same round.
            val refusedBefore = carol.metrics.spool().spoolAttachRefused
            spool.dropSockets()
            assertTrue(
                "carol's fresh session never skipped the refused photo",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (carol.metrics.spool().spoolAttachRefused >
                        refusedBefore
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            // A picture posted after the skip: fetched over the same session, it shows the attachment pass still
            // fetches what is not refused.
            val picture = Random(110).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "after the drop", groupId = groupId))
            val id = alice.ownMessageId(groupId, "after the drop")
            assertTrue(
                "carol never fetched the picture posted after the drop",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    // The pull is counted after the store write, so the counter is what to wait on: the bytes
                    // alone can be read a moment before it moves.
                    val fetched = carol.metrics.spool().spoolAttachPulled > pulled
                    if (fetched && carol.attachmentPlain(groupId, id) != null) 1 else 0
                },
            )
            // Carol's custody parity with the near island is the re-link's (see `InternetPlaneLabTest`'s two-island case).
            lab.assertConverged(
                listOf(alice, bob, carol),
                atLeast = 2,
                timeoutMs = MeshLab.SPOOL_AWAIT_MS,
                custodyAcross = listOf(alice, bob),
            ) { groupId }
            assertEquals("only the new picture was fetched", pulled + 1, carol.metrics.spool().spoolAttachPulled)
            assertFalse("the refused photo's bytes stay dropped", carol.blobs.exists(hash))
            assertNotEquals(hash, carol.group(groupId)?.photoShownHash)
        }

    /**
     * #109, the avatar half: an avatar that reaches a contact over the relay alone is screened as a radio pull is,
     * and one screening refused stays off the row even though its bytes stay held — the sealed profile whose
     * cleartext names them pins them in custody — including when the contact publishes again with the same
     * avatar. Before the fix the next profile found the bytes held and adopted them without asking the screen.
     */
    @Test
    fun anAvatarOnlyTheRelayDeliversIsScreenedAndARefusedOneIsNeverAdopted() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            bob.picturesExplicit = true

            val avatar = Random(109).nextBytes(4_096)
            val hash = sha256Hex(avatar)
            alice.setAvatar(avatar)
            // Screened, then held: the screen runs before the store, so the verdict alone can come a moment early.
            // The bytes stay held after the refusal — the sealed profile whose cleartext names them pins them in
            // custody — and held-but-refused is exactly what the next profile must not adopt.
            assertTrue(
                "bob never screened and held the avatar the relay brought",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.imageFlagged(hash) && bob.blobs.exists(hash)) 1 else 0 },
            )

            // A later profile with the same avatar: once Bob shows the new name, the first writer of that version
            // has had its look at the held, refused bytes (name and avatar move in one upsert).
            alice.setDisplayName("Alice, again")
            assertTrue(
                "bob never saw the rename",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.peer(alice)?.name == "Alice, again") 1 else 0 },
            )
            assertNotEquals("a refused avatar is never adopted", hash, bob.peer(alice)?.avatarHash)
            lab.assertConverged(listOf(alice, bob), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        bob
                    } else {
                        alice
                    },
                )
            }
            assertNotEquals(hash, bob.peer(alice)?.avatarHash)
        }
}
