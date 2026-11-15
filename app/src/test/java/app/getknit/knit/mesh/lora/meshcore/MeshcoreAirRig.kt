package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.FastPathDrop
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshcoreBoardHolder
import app.getknit.knit.mesh.MeshcoreRank
import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.lora.BoardPlane
import app.getknit.knit.mesh.lora.DirectDm
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LoraAuthorCheck
import app.getknit.knit.mesh.lora.LoraAuthorIds
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraDirectCrypto
import app.getknit.knit.mesh.lora.LoraDirectInbox
import app.getknit.knit.mesh.lora.LoraDirectOpen
import app.getknit.knit.mesh.lora.LoraDirectSource
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.OpenedDirect
import app.getknit.knit.mesh.lora.PacketIdSource
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.nio.ByteBuffer
import java.util.Base64

/**
 * The real MeshCore stack on a [FakeMeshcoreAir] (ADR 2026-11.gsqu's Stage 5c-2a amendment): per node, a
 * [MeshcoreSession] dialling its fake board, the plane [meshcorePlane] builds over it, and a [LoraMeshTransport] — the
 * production wiring of `di/MeshModule`, under virtual time. The pace is the transport's own default (MeshCore's traits),
 * the board's key reaches no claim store, and a node consents to its board as a phone that set it up would.
 *
 * Only the seal is a stand-in ([Seal]: readable, opened by its tag), and the author check reads a signature's first byte
 * ([HONEST]); everything between the plane and the air is the code that ships.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class MeshcoreAirRig(
    val scope: TestScope,
    val air: FakeMeshcoreAir,
) {
    class Node(
        val name: String,
        val id: String,
        val board: FakeMeshcoreBoard,
        val dialer: FakeMeshcoreDialer,
        val session: MeshcoreSession,
        val transport: LoraMeshTransport,
        val metrics: MeshMetrics,
        val logs: MutableList<String>,
        val delivered: MutableList<OpenedDirect>,
        val unacked: MutableMap<String, DirectDm>,
        private val made: () -> MeshcoreHooks,
    ) {
        /** The plane's MeshCore hooks, as `meshcorePlane` built them. */
        val hooks: MeshcoreHooks get() = made()

        val ch: FakeNusChannel get() = dialer.channel

        fun drops(reason: FastPathDrop): Long =
            metrics
                .snapshot()
                .fast.fastDropsByReason[reason] ?: 0L

        /** The posts by [author] that reached the router. */
        fun decodedBy(author: String): Int = logs.count { it.startsWith("lora rx ") && it.contains("id=post-$author-") }

        /** The `ADD_UPDATE_CONTACT` writes this node's session made for [other], as (`out_path_len`) bytes. */
        fun pinsOf(other: FakeMeshcoreBoard): List<Int> =
            ch.writes
                .filter { it[0].toInt() == MeshcoreProto.CMD_ADD_UPDATE_CONTACT && it.copyOfRange(1, 33).contentEquals(other.key) }
                .map { it[35].toInt() and 0xFF }

        /** The `GET_CONTACT_BY_KEY` reads this node's session made of [other]'s record. */
        fun readsOf(other: FakeMeshcoreBoard): Int =
            ch.writes.count { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY && it.copyOfRange(1, 33).contentEquals(other.key) }
    }

    val nodes = LinkedHashMap<String, Node>()

    /** Every node's id, pinned for every other: what the author check and the author ids read. */
    private val ids = LinkedHashMap<String, String>()

    fun idOf(name: String): String =
        ids.getOrPut(name) { NodeId.fromBytes(ByteArray(NodeId.BYTES) { (name.hashCode() * 31 + it).toByte() }) }

    /** A board for [name] set up for Knit on the air: the Knit slot, the `Knit abcd` name; no node yet. */
    fun board(name: String): FakeMeshcoreBoard =
        air.board(name).also {
            it.setChannel(KNIT_SLOT, KnitChannel.NAME, KnitChannel.PSK)
            it.advertName = MeshcoreNames.forKey(it.key).encodeToByteArray()
            idOf(name)
        }

    /**
     * The phone driving [board]: its session, plane and transport, started and brought to Ready, every other named node a
     * contact whose board key it holds ([peers]).
     */
    @Suppress("LongMethod") // one node's production wiring, as `di/MeshModule` writes it, in one place
    fun node(
        board: FakeMeshcoreBoard,
        peers: List<String>,
        lingerSweepMs: Long = LoraMeshTransport.LINGER_SWEEP_MS,
    ): Node {
        val name = board.name
        val self = idOf(name)
        val logs = mutableListOf<String>()
        val records = MemoryMeshcoreSetupJournal()
        val address = "AA:$name"
        runBlocking { records.put(address, MeshcoreHooks.digest(board.key), SETUP_RECORD, pending = false) }
        val dialer = FakeMeshcoreDialer(board)
        val session =
            MeshcoreSession(
                dialer = dialer,
                scope = scope.backgroundScope,
                backoff = BackoffConfig(baseMs = 5_000, maxMs = 180_000, jitterFraction = 0.0),
                now = { scope.testScheduler.currentTime },
                wallClock = { wall() },
                rand = { 0.5 },
                ids = PacketIdSource(1_000L + name.hashCode()),
                log = { logs += "session: $it" },
                records = records,
                nonce = { ByteArray(32) { 7 } },
            )
        val metrics = MeshMetrics()
        val delivered = mutableListOf<OpenedDirect>()
        val unacked = LinkedHashMap<String, DirectDm>()
        var hooks: MeshcoreHooks? = null
        val holders =
            peers.associate { peer ->
                idOf(peer) to
                    MeshcoreBoardHolder(
                        Base64.getEncoder().encodeToString(FakeMeshcoreAir.keyOf(peer)),
                        MeshcoreRank.ACCEPTED,
                        1,
                        false,
                    )
            }
        val pinned = ids.values.toList() + peers.map(::idOf)
        val transport =
            LoraMeshTransport(
                selfId = { self },
                // `meshcorePlane`'s own body, keeping the hooks it builds for the oracles.
                plane =
                    BoardPlane(MeshcoreTraits, session) { core ->
                        MeshcoreHooks(
                            session,
                            core,
                            scope.backgroundScope,
                            { scope.testScheduler.currentTime },
                            { logs += it },
                            { wall() },
                        ) { _, _ -> }
                            .also { hooks = it }
                    },
                config = MutableStateFlow(LoraConfig(address, MeshcoreHooks.NO_CONFIGURED_SLOT, dms = true)),
                crypto = Seal(self, ids),
                inbox = LoraDirectInbox { delivered += it },
                source =
                    object : LoraDirectSource {
                        override suspend fun unackedTo(
                            peerId: String,
                            limit: Int,
                            untakenOnly: Boolean,
                            maxBytes: Int,
                        ): List<DirectDm> = unacked.values.filter { it.frameId.startsWith("$peerId/") }.take(limit)

                        override suspend fun isUnacked(frameId: String): Boolean = frameId in unacked
                    },
                boardMap = flowOf(MeshcoreContacts.holdersOf(holders)),
                scope = scope.backgroundScope,
                metrics = metrics,
                clock = { scope.testScheduler.currentTime },
                wallClock = { wall() },
                processStartWall = WALL,
                log = { logs += it },
                lingerSweepMs = lingerSweepMs,
                authorCheck = LoraAuthorCheck { wire, env -> wire.sig[0] == HONEST && env.senderId in pinned },
                authorIds = flowOf(LoraAuthorIds(accepted = peers.map(::idOf), pinned = pinned)),
                // Seeded per node, so a failing run replays byte for byte (production's is SecureRandom).
                fragIds = kotlin.random.Random(MeshcoreChannel.mix("frag", name)).let { r -> { r.nextInt(0x10000) } },
            )
        transport.start()
        val node = Node(name, self, board, dialer, session, transport, metrics, logs, delivered, unacked) { checkNotNull(hooks) }
        nodes[name] = node
        settle(10_000)
        return node
    }

    fun wall(): Long = WALL + scope.testScheduler.currentTime

    /** Runs the virtual clock [ms] on, running everything due. */
    fun settle(ms: Long) {
        scope.runCurrent()
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    /** [from]'s phone sends [to] a compact DM, as `MeshManager` would offer it, and keeps it unacked until a ✓✓. */
    fun dm(
        from: Node,
        to: Node,
        frameId: String,
        body: ByteArray = ByteArray(24) { (it + 1).toByte() },
    ) {
        val dm = DirectDm("${to.id}/$frameId", wall(), body)
        from.unacked[dm.frameId] = dm
        from.transport.offer(to.id, dm.frameId, dm.sentAtMs, body, app.getknit.knit.mesh.lora.DirectKind.MESSAGE)
    }

    /** A signed room post by [author], [bytes] of text that does not deflate: two bound packets at 150 B. */
    fun postBy(
        author: String,
        sentAt: Long = wall(),
        bytes: Int = LONG_POST,
        sig0: Byte = HONEST,
        seed: Int = 1,
    ): WireEnvelope {
        val random = kotlin.random.Random(seed)
        val body = String(CharArray(bytes) { (0x21 + random.nextInt(0x5E)).toChar() })
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "post-$author-$sentAt-$seed",
                senderId = author,
                sentAt = sentAt,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        return WireEnvelope(
            relay = true,
            sig = ByteArray(64) { (it * seed + sentAt).toByte() }.also { it[0] = sig0 },
            signed = WireCodec.encodeEnvelope(env),
        )
    }

    /**
     * A seal anyone can read: the tag, the sealer's index, the sent time and the frame id before the plaintext. It opens
     * any seal by a known id as from that id — what these tests need of the crypto, whose own tests are elsewhere.
     */
    private class Seal(
        private val self: String,
        private val ids: Map<String, String>,
    ) : LoraDirectCrypto {
        override fun seal(
            peerId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
        ): ByteArray {
            val id = frameId.encodeToByteArray()
            return byteArrayOf(LORA_DIRECT_TAG, ids.values.indexOf(self).toByte()) + ByteBuffer.allocate(8).putLong(sentAtMs).array() +
                byteArrayOf(id.size.toByte()) + id + plaintext
        }

        override fun open(packet: ByteArray): LoraDirectOpen {
            if (packet.size < 11 || packet[0] != LORA_DIRECT_TAG) return LoraDirectOpen.Stranger
            val from = ids.values.elementAtOrNull(packet[1].toInt()) ?: return LoraDirectOpen.Stranger
            val sentAt = ByteBuffer.wrap(packet, 2, 8).long
            val n = packet[10].toInt()
            val frameId = packet.copyOfRange(11, 11 + n).decodeToString()
            return LoraDirectOpen.Opened(OpenedDirect(from, frameId, sentAt, packet.copyOfRange(11 + n, packet.size)))
        }
    }

    companion object {
        const val KNIT_SLOT = 1
        const val HONEST: Byte = 7
        const val WALL = 1_800_000_000_000L
        const val LONG_POST = 150

        val SETUP_RECORD =
            MeshcoreSetupRecord(1, slotWritten = false, priorName = "x".encodeToByteArray(), priorManualAdd = 0, priorAutoadd = null)
    }
}
