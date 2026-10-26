package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAttachmentsTest {
    private fun env(
        type: String = FrameType.CHAT,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ) = RelayEnvelope(
        type = type,
        id = "f1",
        senderId = "alice",
        sentAt = 1L,
        recipientId = recipientId,
        group = group,
        payload = ByteArray(0),
    )

    private val note = ChatContent(body = "listen", attachmentHash = "aa".repeat(32), attachmentMime = "audio/aac")

    @Test
    fun theRoomTakesWhatItScreens() {
        assertTrue(RoomAttachments.admits(null))
        assertTrue(RoomAttachments.admits("image/jpeg"))
        assertTrue(RoomAttachments.admits("image/gif"))
        assertTrue(RoomAttachments.admits(LinkPreviewBlob.MIME))
        assertTrue(RoomAttachments.admits("video/mp4"))
    }

    @Test
    fun theRoomRefusesWhatNothingScreens() {
        assertFalse(RoomAttachments.admits("audio/aac"))
        assertFalse(RoomAttachments.admits("audio/mp4"))
        assertFalse(RoomAttachments.admits("application/pdf"))
        assertFalse(RoomAttachments.admits("application/octet-stream"))
        assertFalse(RoomAttachments.admits("text/plain"))
    }

    @Test
    fun onlyARoomShapedChatIsRefused() {
        assertTrue(RoomAttachments.refuses(env(), note))
        assertFalse("a DM's attachment is screened after decryption", RoomAttachments.refuses(env(recipientId = "bob"), note))
        assertFalse(RoomAttachments.refuses(env(group = GroupInfo(id = "g1", members = listOf("alice"), createdBy = "alice")), note))
        assertFalse(
            RoomAttachments.refuses(env(), note.copy(enc = EncEnvelope(nonce = ByteArray(0), ct = ByteArray(0), keys = emptyList()))),
        )
        assertFalse(RoomAttachments.refuses(env(type = FrameType.REACTION), note))
        assertFalse(
            "no attachment, nothing to refuse",
            RoomAttachments.refuses(env(), ChatContent(body = "hi", attachmentMime = "audio/aac")),
        )
    }

    @Test
    fun aPostThatWasOnlyARefusedAttachmentIsDroppedWhole() {
        assertTrue(RoomAttachments.dropsWhole(env(), note.copy(body = "")))
        assertTrue(RoomAttachments.dropsWhole(env(), note.copy(body = "  ")))
        assertFalse("its words stay", RoomAttachments.dropsWhole(env(), note))
        assertFalse(RoomAttachments.dropsWhole(env(), ChatContent(attachmentHash = "bb".repeat(32), attachmentMime = "image/jpeg")))
        assertFalse(RoomAttachments.dropsWhole(env(recipientId = "bob"), note.copy(body = "")))
    }

    @Test
    fun aRefusedAttachmentIsDroppedWholeAndTheRestKept() {
        val post =
            note.copy(
                attachmentSize = 4_000L,
                video = VideoInfo(1_000L, 2, 2, byteArrayOf(1)),
                attachmentName = "x.aac",
                attachmentKey = "a2V5",
            )

        assertEquals(ChatContent(body = "listen"), RoomAttachments.admitted(env(), post))
    }

    @Test
    fun anAdmittedAttachmentIsLeftAsItCame() {
        val post = ChatContent(body = "pic", attachmentHash = "bb".repeat(32), attachmentMime = "image/jpeg", attachmentSize = 9L)

        assertEquals(post, RoomAttachments.admitted(env(), post))
        assertEquals(note, RoomAttachments.admitted(env(recipientId = "bob"), note))
    }
}
