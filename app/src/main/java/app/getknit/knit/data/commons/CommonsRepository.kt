package app.getknit.knit.data.commons

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.spool.CommonsMember
import app.getknit.knit.mesh.spool.CommonsRoom
import app.getknit.knit.mesh.spool.CommonsRoots
import app.getknit.knit.mesh.spool.CommonsStore
import app.getknit.knit.mesh.spool.hex
import kotlinx.coroutines.flow.Flow

/**
 * The joined commons, their outbox and their members, over the encrypted database — the app's
 * [CommonsStore]. Every multi-table write is one `db.withWriteTransaction` (`.agents/rules/coding.md`):
 * a room and its history are one thing to the user, so they appear and vanish together.
 */
class CommonsRepository(
    private val dao: CommonsDao,
    private val messages: MessageRepository,
    private val db: KnitDatabase,
) : CommonsStore {
    fun observeAll(): Flow<List<CommonsEntity>> = dao.observeAll()

    fun observe(conversationId: String): Flow<CommonsEntity?> = dao.observe(conversationId)

    fun observeMemberIds(conversationId: String): Flow<List<String>> = dao.observeMemberIds(conversationId)

    override suspend fun find(conversationId: String): CommonsRoom? = dao.find(conversationId)?.toRoom()

    override suspend fun roots(): List<CommonsRoots> = dao.all().map { CommonsRoots(it.conversationId, it.spoolUrl, it.secret) }

    /**
     * Joins the room [secret] unlocks at [spoolUrl]. Idempotent on the secret: the conversation id is the
     * scope id, so pasting the same invite twice — or re-joining after a leave — lands in the one thread.
     * Returns the conversation id.
     */
    suspend fun join(
        spoolUrl: String,
        secret: ByteArray,
        name: String?,
        now: Long,
    ): String {
        val conversationId = conversationIdFor(secret)
        dao.upsert(CommonsEntity(conversationId = conversationId, spoolUrl = spoolUrl, secret = secret, name = name, joinedAt = now))
        return conversationId
    }

    /**
     * Leaves [conversationId]: the room, its outbox, its members and its messages go in one transaction,
     * so the chat list never shows a thread whose key is gone or a key whose thread is. The members stay
     * pinned peers and accepted contacts — being in the room is how they became contacts, and leaving it
     * is not a reason to unmake that.
     */
    suspend fun leave(conversationId: String) {
        db.withWriteTransaction { purge(conversationId) }
    }

    /** Leaves every room bound to [spoolUrl] — the relay row was removed, and a room without its relay is nothing. */
    suspend fun leaveBoundTo(spoolUrl: String) {
        db.withWriteTransaction { dao.idsBoundTo(spoolUrl).forEach { purge(it) } }
    }

    override suspend fun post(
        row: MessageEntity,
        sig: ByteArray,
        signed: ByteArray,
    ) {
        db.withWriteTransaction {
            messages.save(row)
            dao.putOutbox(
                CommonsOutboxEntity(frameId = row.id, conversationId = row.conversationId, sig = sig, signed = signed, sentAt = row.sentAt),
            )
        }
    }

    override suspend fun frames(conversationId: String): List<CarriedFrame> =
        dao.outboxFor(conversationId).mapNotNull { row ->
            WireCodec.decodeEnvelope(row.signed)?.let { CarriedFrame(envelope = it, sig = row.sig, signed = row.signed) }
        }

    override suspend fun recordMember(
        conversationId: String,
        nodeId: String,
        now: Long,
    ) {
        dao.upsertMember(CommonsMemberEntity(conversationId = conversationId, nodeId = nodeId, seenAt = now))
    }

    suspend fun members(conversationId: String): List<String> = dao.membersOf(conversationId).map { it.nodeId }

    override suspend fun allMembers(): List<CommonsMember> = dao.allMembers().map { CommonsMember(it.conversationId, it.nodeId, it.seenAt) }

    override suspend fun isMember(
        conversationId: String,
        nodeId: String,
    ): Boolean = dao.isMember(conversationId, nodeId)

    override suspend fun sweepOutbox(before: Long) = dao.sweepOutbox(before)

    private suspend fun purge(conversationId: String) {
        dao.delete(conversationId)
        dao.deleteOutboxFor(conversationId)
        dao.deleteMembersOf(conversationId)
        messages.deleteByConversation(conversationId)
    }

    private fun CommonsEntity.toRoom() = CommonsRoom(conversationId = conversationId, spoolUrl = spoolUrl, secret = secret, name = name)

    companion object {
        /** `c-` + the room's scope id — derived, so the same invite is always the same thread. */
        fun conversationIdFor(secret: ByteArray): String = Conversations.commonsIdFor(hex(ScopeCrypto.commonsScopeId(secret)))
    }
}
