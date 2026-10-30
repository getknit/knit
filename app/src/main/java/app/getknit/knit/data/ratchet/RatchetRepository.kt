package app.getknit.knit.data.ratchet

import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetStore

/**
 * Room-backed [RatchetStore]. Pure row mapping plus the retention sweep; **no transactions here** — the
 * callers (via `RatchetSessions`) wrap `applyOpen`/`commitSend` in the same `db.withWriteTransaction` as the
 * message row (the interface contract).
 *
 * The sweep is the forward-secrecy enforcement point (docs/FORWARD_SECRECY_RATCHET.md §"retention"):
 * what it deletes is what a later device compromise can no longer decrypt.
 */
class RatchetRepository(
    private val dao: RatchetDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : RatchetStore {
    override suspend fun session(peerId: String): RatchetEngine.SessionState? = dao.session(peerId)?.toState()

    override suspend fun sessionPeerIds(): List<String> = dao.sessionPeerIds()

    override suspend fun upsertSession(state: RatchetEngine.SessionState) {
        dao.upsertSession(state.toEntity(clock()))
    }

    override suspend fun localEpochPriv(
        peerId: String,
        epoch: Int,
    ): ByteArray? = dao.localEpochPriv(peerId, epoch)

    override suspend fun lowestLocalEpoch(peerId: String): Int? = dao.lowestLocalEpoch(peerId)

    override suspend fun recvEpochs(
        peerId: String,
        epoch: Int,
    ): List<RatchetEngine.RecvEpoch> =
        dao.recvEpochs(peerId, epoch).map {
            RatchetEngine.RecvEpoch(
                epoch = it.epoch,
                rootTag = it.rootTag,
                chainKey = it.chainKey,
                next = it.next,
                lastUsedAt = it.lastUsedAt,
            )
        }

    override suspend fun skippedKeys(
        peerId: String,
        epoch: Int,
        idx: Int,
    ): List<RatchetEngine.SkippedKey> =
        dao.skippedKeys(peerId, epoch, idx).map {
            RatchetEngine.SkippedKey(epoch = it.epoch, rootTag = it.rootTag, idx = it.idx, msgKey = it.msgKey, createdAt = it.createdAt)
        }

    override suspend fun applyOpen(
        peerId: String,
        delta: RatchetEngine.OpenDelta,
        headerSe: Int,
        headerN: Int,
    ) {
        if (delta.purgePeerRecvState) {
            val keep = delta.keepRecvTag
            if (keep == null) {
                dao.deleteRecvEpochsFor(peerId)
                dao.deleteSkippedKeysFor(peerId)
            } else {
                dao.deleteRecvEpochsForExcept(peerId, keep)
                dao.deleteSkippedKeysForExcept(peerId, keep)
            }
        }
        dao.upsertSession(delta.session.toEntity(clock()))
        delta.recvEpoch?.let {
            dao.upsertRecvEpoch(
                RatchetRecvEpochEntity(
                    peerId = peerId,
                    epoch = it.epoch,
                    rootTag = it.rootTag,
                    chainKey = it.chainKey,
                    next = it.next,
                    lastUsedAt = it.lastUsedAt,
                ),
            )
        }
        if (delta.skippedInserts.isNotEmpty()) {
            dao.insertSkippedKeys(
                delta.skippedInserts.map {
                    RatchetSkippedKeyEntity(
                        peerId = peerId,
                        epoch = it.epoch,
                        idx = it.idx,
                        rootTag = it.rootTag,
                        msgKey = it.msgKey,
                        createdAt = it.createdAt,
                    )
                },
            )
        }
        delta.consumedSkippedTag?.let { dao.deleteSkippedKey(peerId, headerSe, headerN, rootTag = it) }
    }

    override suspend fun commitSend(
        state: RatchetEngine.SessionState,
        newLocalEpoch: RatchetEngine.LocalEpoch?,
    ) {
        dao.upsertSession(state.toEntity(clock()))
        newLocalEpoch?.let {
            dao.insertLocalEpoch(
                RatchetLocalEpochEntity(peerId = state.peerId, epoch = it.epoch, priv = it.priv, pub = it.pub, createdAt = it.createdAt),
            )
        }
    }

    override suspend fun purgePeerRecvState(peerId: String) {
        dao.deleteRecvEpochsFor(peerId)
        dao.deleteSkippedKeysFor(peerId)
    }

    override suspend fun debugLocalEpochs(peerId: String): List<Pair<Int, Long>> =
        dao.localEpochsNewestFirst(peerId).map { it.epoch to it.createdAt } // most recently minted first

    override suspend fun deletePeer(peerId: String) {
        dao.deleteSession(peerId)
        dao.deleteLocalEpochsFor(peerId)
        dao.deleteRecvEpochsFor(peerId)
        dao.deleteSkippedKeysFor(peerId)
    }

    override suspend fun sweep(now: Long) {
        dao.deleteSkippedKeysBefore(now - SKIPPED_TTL_MS)
        val excess = dao.countSkippedKeys() - MAX_SKIPPED_KEYS
        if (excess > 0) dao.evictOldestSkippedKeys(excess)
        dao.deleteRecvEpochsBefore(now - RECV_EPOCH_TTL_MS)
        dao.sessionPeerIds().forEach { peerId -> sweepLocalEpochs(peerId, now) }
    }

    /**
     * Retires our own epoch privs for [peerId] — **the PFS window**. An epoch is deletable once it is
     * superseded and the peer has sealed against a newer one ([RatchetEngine.SessionState.highestPeAcked])
     * and 48 h have passed (late custody re-serves based on it are done), or unconditionally past the
     * 30-day hard TTL / the 16-per-peer cap. The newest [KEEP_NEWEST_LOCAL_EPOCHS] always survive so an
     * in-flight peer epoch based on a recent one keeps decrypting.
     *
     * "Newest" is by **mint time**, not epoch number (the DAO orders by `createdAt`): a session reset
     * restarts numbering at 1, and ranking numerically let a long-lived session's dead-era rows outrank
     * every live-era row — the cap then deleted each fresh epoch within one sweep cycle and the pair
     * relapsed into undecryptability on the reset floor's cadence, forever (ADR 027).
     */
    private suspend fun sweepLocalEpochs(
        peerId: String,
        now: Long,
    ) {
        val acked = dao.session(peerId)?.highestPeAcked ?: 0
        val epochs = dao.localEpochsNewestFirst(peerId)
        val retire =
            epochs
                .drop(KEEP_NEWEST_LOCAL_EPOCHS)
                .filterIndexed { droppedIndex, row ->
                    val overCap = KEEP_NEWEST_LOCAL_EPOCHS + droppedIndex >= MAX_LOCAL_EPOCHS
                    val hardExpired = now - row.createdAt >= LOCAL_EPOCH_HARD_TTL_MS
                    val retired = row.epoch < acked && now - row.createdAt >= LOCAL_EPOCH_RETIRE_MS
                    overCap || hardExpired || retired
                }.map { it.epoch }
        if (retire.isNotEmpty()) dao.deleteLocalEpochs(peerId, retire)
    }

    private fun RatchetSessionEntity.toState(): RatchetEngine.SessionState =
        RatchetEngine.SessionState(
            peerId = peerId,
            confirmed = confirmed,
            weAreInitiator = weAreInitiator,
            root = root,
            prevRoot = prevRoot,
            prevRootWeAreInitiator = prevRootWeAreInitiator,
            prevRootExpiresAt = prevRootExpiresAt,
            prevRootOwnInitAt = prevRootOwnInitAt,
            establishedAt = establishedAt,
            initEphPub = initEphPub,
            initPkid = initPkid,
            peerInitEphPub = peerInitEphPub,
            peerBasePub = peerBasePub,
            peerBaseEpoch = peerBaseEpoch,
            sendEpoch = sendEpoch,
            sendEpochPub = sendEpochPub,
            sendChainKey = sendChainKey,
            sendCount = sendCount,
            sendEpochStartedAt = sendEpochStartedAt,
            sendEpochBaseEpoch = sendEpochBaseEpoch,
            sendEpochExport = sendEpochExport,
            highestPeAcked = highestPeAcked,
            lastResetSentAt = lastResetSentAt,
        )

    private fun RatchetEngine.SessionState.toEntity(now: Long): RatchetSessionEntity =
        RatchetSessionEntity(
            peerId = peerId,
            confirmed = confirmed,
            weAreInitiator = weAreInitiator,
            root = root,
            prevRoot = prevRoot,
            prevRootWeAreInitiator = prevRootWeAreInitiator,
            prevRootExpiresAt = prevRootExpiresAt,
            prevRootOwnInitAt = prevRootOwnInitAt,
            establishedAt = establishedAt,
            initEphPub = initEphPub,
            initPkid = initPkid,
            peerInitEphPub = peerInitEphPub,
            peerBasePub = peerBasePub,
            peerBaseEpoch = peerBaseEpoch,
            sendEpoch = sendEpoch,
            sendEpochPub = sendEpochPub,
            sendChainKey = sendChainKey,
            sendCount = sendCount,
            sendEpochStartedAt = sendEpochStartedAt,
            sendEpochBaseEpoch = sendEpochBaseEpoch,
            sendEpochExport = sendEpochExport,
            highestPeAcked = highestPeAcked,
            lastResetSentAt = lastResetSentAt,
            updatedAt = now,
        )

    companion object {
        /** Skipped-key + recv-epoch retention: 2x the 24 h custody TTL (late re-serves + skew margin). */
        const val SKIPPED_TTL_MS = 48 * 60 * 60_000L
        const val RECV_EPOCH_TTL_MS = 48 * 60 * 60_000L

        /** Global skipped-key cap — a DoS bound, not a correctness one (an evicted key = one lost late frame). */
        const val MAX_SKIPPED_KEYS = 2_000

        /** Local-epoch retention (docs/FORWARD_SECRECY_RATCHET.md §10). */
        const val KEEP_NEWEST_LOCAL_EPOCHS = 3
        const val MAX_LOCAL_EPOCHS = 16
        const val LOCAL_EPOCH_RETIRE_MS = 48 * 60 * 60_000L
        const val LOCAL_EPOCH_HARD_TTL_MS = 30L * 24 * 60 * 60_000L
    }
}
