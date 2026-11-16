package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.bluetooth.LinkSealPolicy
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.SealFramingException
import app.getknit.knit.mesh.link.SealedLinkSocket
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Which link door a lab node's build opens (#313): the three forms a radio link's handshake has taken. A node's door is
 * [LabLimits.linkDoor]; [MeshLab.doorLink] runs it. A node linked by [MeshLab.link] has no door at all, as before.
 *
 * Only the door is the older build's. Everything above it (the manager, custody, the ratchet) is this build's, so a mixed
 * mesh here is this build's mesh behind three doors: what it proves is that each pair takes the form its builds allow and
 * the mesh converges over the mix, not that an older build's mesh logic does.
 */
enum class LinkDoor(
    /** The `LOCAL_CAPABILITIES` bits that build's HELLO and profile lack. */
    val withheld: Long,
) {
    /**
     * v2.10.x (release/2.10.2's `LinkHandshake`): the dialer writes its advert as a HELLO, the responder reads it and
     * writes its own back. No nonce, no proof, no seal: an unproven link.
     */
    PLAIN(Protocol.CAP_LINK_PROOF or Protocol.CAP_BLIND_ADVERT or Protocol.CAP_LINK_SEAL),

    /**
     * v2.11.x (release/2.11.1's `dial` / `answer`, ADR 2026-11.264w): both HELLOs carry a nonce and the proof bit, then
     * a PROOF each over the v1 transcript. No seal.
     */
    PROOF(Protocol.CAP_BLIND_ADVERT or Protocol.CAP_LINK_SEAL),

    /**
     * This build (ADR 2026-11.8mhs): the sealed form toward a build whose advert carries the seal flag, the plain form
     * with §7's refusal otherwise. Runs `LinkHandshake` itself.
     */
    SEALED(0L),
    ;

    /** This build's advert carries the seal flag (`BleAdvertPayload.FLAG_LINK_SEAL`, the SSI's `CAP_LINK_SEAL`). */
    val seals: Boolean get() = this == SEALED

    /** The caps an older build's HELLO carries. [SEALED]'s HELLO is `Protocol.hello`'s own. */
    val helloCaps: Long get() = Protocol.LOCAL_CAPABILITIES and withheld.inv()
}

/**
 * The link door under a lab link (#313): the real handshake run over an in-memory socket pair, as each plane runs it, and
 * — once both halves linked — the socket each half registered, which every frame on that link then crosses
 * ([Crossing]). Before it, a lab link registered directly, so no scenario reached the seal, its downgrade refusal or a
 * tampered frame (ADR 2026-11.8mhs's closing paragraph, ADR 2026-11.264w).
 *
 * **The halves.** [dial] and [answer] follow `BluetoothMeshTransport`'s dial and `superviseAccepted` step for step for
 * the [LinkDoor.SEALED] build: the form from the target's advert, each stage's outcome folded and counted through
 * [LinkSealPolicy] into the node's own [MeshMetrics], §7 refused on the responder before anything else, a refused half
 * closing its socket with no reply. They do not run `LinkClaims` (the claim admission, which `LinkClaimsTest` and the
 * planes' tests cover) or the planes' backoffs: a refusal here is a link that does not register, nothing more. The
 * older doors are the release branches' code, re-stated here because `LinkHandshake` writes this build's caps only.
 *
 * **Reads are bounded** per call by [READ_BOUND_MS], as Wi-Fi Aware's `soTimeout` bounds them, and every flush stalls
 * under the lab's chaos ([LabChaos.stall]), so the sweep moves the handshake's interleaving too.
 */
object LabDoor {
    /** How long one handshake read waits before it reads as a timeout (`ACCEPT_HELLO_TIMEOUT_MS`'s role). */
    const val READ_BOUND_MS = 5_000L

    /** One half's end: the socket it registers, or why it registered nothing. */
    sealed interface Half {
        class Linked(
            val socket: LinkSocket,
            val proven: Boolean,
        ) : Half {
            val sealed: Boolean get() = socket is SealedLinkSocket
        }

        data class Refused(
            val why: String,
        ) : Half
    }

    /** One end of the door: the node's identity half, its build, and where its counts go. */
    class End(
        val nodeId: String,
        val door: LinkDoor,
        val prover: LinkProver,
        val metrics: MeshMetrics,
    )

    /** An in-memory socket pair: [dialer] and [responder] read what the other writes. */
    class Sockets(
        chaos: LabChaos?,
        readBoundMs: Long = READ_BOUND_MS,
    ) {
        /** Dialer → responder. */
        val forward = Bytes(chaos, readBoundMs)

        /** Responder → dialer. */
        val back = Bytes(chaos, readBoundMs)
        val dialer: LinkSocket = Socket(back, forward)
        val responder: LinkSocket = Socket(forward, back)
    }

    private class Socket(
        private val inBytes: Bytes,
        private val outBytes: Bytes,
    ) : LinkSocket {
        override val input: InputStream = inBytes.input
        override val output: OutputStream = outBytes.output

        override fun close() {
            inBytes.close()
            outBytes.close()
        }
    }

    /**
     * One direction of a link's bytes. A read waits for bytes up to its bound and then throws [SocketTimeoutException];
     * once closed, what was written still reads and then -1, as a socket's peer reads up to the close. [tamperNext]
     * rewrites the next write once: the hostile radio between two phones.
     */
    class Bytes(
        chaos: LabChaos?,
        readBoundMs: Long,
    ) {
        private val lock = ReentrantLock()
        private val arrived = lock.newCondition()
        private val chunks = ArrayDeque<ByteArray>()
        private var at = 0
        private var closed = false

        @Volatile
        var tamperNext: ((ByteArray) -> ByteArray)? = null

        fun close() =
            lock.withLock {
                closed = true
                arrived.signalAll()
            }

        val input: InputStream =
            object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (len == 0) return 0
                    lock.withLock {
                        var left = readBoundMs * NANOS_PER_MS
                        while (chunks.isEmpty()) {
                            if (closed) return -1
                            if (left <= 0) throw SocketTimeoutException("lab door read timed out")
                            left = arrived.awaitNanos(left)
                        }
                        val head = chunks.first()
                        val n = minOf(len, head.size - at)
                        System.arraycopy(head, at, b, off, n)
                        at += n
                        if (at == head.size) {
                            chunks.removeFirst()
                            at = 0
                        }
                        return n
                    }
                }

                override fun available(): Int = lock.withLock { chunks.sumOf { it.size } - at }
            }

        val output: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (len == 0) return
                    val bytes = b.copyOfRange(off, off + len).let { tamperNext?.also { tamperNext = null }?.invoke(it) ?: it }
                    lock.withLock {
                        if (closed) throw IOException("lab door closed")
                        chunks.addLast(bytes)
                        arrived.signalAll()
                    }
                }

                override fun flush() {
                    chaos?.stall()
                }
            }
    }

    /**
     * The dialer's half toward [expectedId], whose advert carries the seal flag when [advertSeals]: the form a dial opens
     * with is the dialer's build and the target's advert (`LinkSealPolicy.dialsSealed`), never a memory of the peer.
     */
    fun dial(
        end: End,
        socket: LinkSocket,
        expectedId: String,
        advertSeals: Boolean,
    ): Half =
        when (end.door) {
            LinkDoor.SEALED -> sealedBuildDial(end, socket, expectedId, advertSeals)
            LinkDoor.PROOF -> proofBuildDial(end, socket, expectedId)
            LinkDoor.PLAIN -> plainBuildDial(end, socket, expectedId)
        }.also { if (it is Half.Refused) socket.close() }

    /** The responder's half for whatever dialed in. */
    fun answer(
        end: End,
        socket: LinkSocket,
    ): Half =
        when (end.door) {
            LinkDoor.SEALED -> sealedBuildAnswer(end, socket)
            LinkDoor.PROOF -> proofBuildAnswer(end, socket)
            LinkDoor.PLAIN -> plainBuildAnswer(end, socket)
        }.also { if (it is Half.Refused) socket.close() }

    // --- this build: `BluetoothMeshTransport`'s dial and `superviseAccepted` ---

    private fun sealedBuildDial(
        end: End,
        socket: LinkSocket,
        expectedId: String,
        advertSeals: Boolean,
    ): Half {
        val dialed =
            if (LinkSealPolicy.dialsSealed(expectedId to false, { advertSeals }, seals = true)) {
                val opened = runCatching { LinkHandshake.openSeal(socket) }.getOrNull()
                LinkSealPolicy.unopened(opened, inTime = true)
                    ?: LinkHandshake.dialSealed((opened as LinkHandshake.SealOpened.Sealed).link, end.prover, expectedId)
            } else {
                LinkHandshake.dialPlain(socket, end.prover, expectedId)
            }
        count(end, LinkSealPolicy.dialCount(dialed))
        return when (dialed) {
            is LinkHandshake.DialOutcome.Linked -> Half.Linked(dialed.link, dialed.proven)
            else -> Half.Refused(dialed.toString())
        }
    }

    /** What a sealing responder's first records left it with: the dialer's HELLO to admit, or a refusal. */
    private sealed interface Opened {
        class Admit(
            val hello: LinkHandshake.ReceivedHello,
            val seal: SealedLinkSocket?,
        ) : Opened

        class Refuse(
            val why: String,
        ) : Opened
    }

    private fun sealedBuildAnswer(
        end: End,
        socket: LinkSocket,
    ): Half {
        val admit =
            when (val opened = sealedBuildOpening(end, socket)) {
                is Opened.Refuse -> return Half.Refused(opened.why)
                is Opened.Admit -> opened
            }
        if (admit.hello.malformedOffer) return Half.Refused("proof=no-nonce")
        val answered =
            runCatching {
                if (admit.seal != null) {
                    LinkHandshake.answerSealed(admit.seal, end.prover, admit.hello)
                } else {
                    LinkHandshake.answerPlain(socket, end.prover, admit.hello)
                }
            }.getOrElse { LinkHandshake.AnswerOutcome.ProofFailed(LinkHandshake.WHY_UNREADABLE) }
        count(end, LinkSealPolicy.answerCount(answered))
        return when (answered) {
            is LinkHandshake.AnswerOutcome.Linked -> Half.Linked(answered.link, answered.proven)
            else -> Half.Refused(answered.toString())
        }
    }

    /** The responder's first record, and on a sealed opening the dialer's sealed HELLO: §7 refused before anything else. */
    private fun sealedBuildOpening(
        end: End,
        socket: LinkSocket,
    ): Opened {
        val opening = runCatching { LinkHandshake.receiveOpening(socket) }.getOrNull() ?: LinkHandshake.Opening.None
        count(end, LinkSealPolicy.openingCount(opening))
        return when (opening) {
            LinkHandshake.Opening.None -> {
                Opened.Refuse("no opening")
            }

            is LinkHandshake.Opening.SealFailed -> {
                Opened.Refuse("seal failed (key share: ${opening.why})")
            }

            is LinkHandshake.Opening.Plain -> {
                if (LinkHandshake.refusesPlain(opening.hello)) {
                    end.metrics.onLinkSealRefused()
                    Opened.Refuse("seal=plain")
                } else {
                    Opened.Admit(opening.hello, seal = null)
                }
            }

            is LinkHandshake.Opening.Sealed -> {
                val sealed =
                    runCatching { LinkHandshake.receiveSealedHello(opening.link) }.getOrNull() ?: LinkHandshake.SealedHello.None
                count(end, LinkSealPolicy.sealedHelloCount(sealed))
                when (sealed) {
                    LinkHandshake.SealedHello.None -> Opened.Refuse("no sealed hello")
                    is LinkHandshake.SealedHello.SealFailed -> Opened.Refuse("seal failed (hello: ${sealed.why})")
                    is LinkHandshake.SealedHello.Hello -> Opened.Admit(sealed.hello, opening.link)
                }
            }
        }
    }

    private fun count(
        end: End,
        count: LinkSealPolicy.Count,
    ) {
        when (count) {
            LinkSealPolicy.Count.NONE -> Unit
            LinkSealPolicy.Count.FAILED -> end.metrics.onLinkSealFailed()
            LinkSealPolicy.Count.REFUSED -> end.metrics.onLinkSealRefused()
        }
    }

    // --- v2.11.x: release/2.11.1's `dial` / `answer`, over that build's caps ---

    @Suppress("ReturnCount") // one early return per outcome, in the order the records arrive
    private fun proofBuildDial(
        end: End,
        socket: LinkSocket,
        expectedId: String,
    ): Half {
        val helloD = oldHello(end, LinkHandshake.freshNonce())
        if (!writeRecords(socket, LinkFraming.encode(LinkFraming.Type.HELLO, helloD))) return Half.Refused("no reply")
        val reply = LinkHandshake.receiveHello(socket.input) ?: return Half.Refused("no reply")
        if (reply.wire.nodeId != expectedId) return Half.Refused("wrong peer")
        if (!reply.offersProof) return Half.Linked(socket, proven = false)
        if (reply.malformedOffer) return Half.Refused("proof=no-nonce")
        val why = readProof(socket, reply.wire.nodeId, LinkProof.Role.RESPONDER, helloD, reply.payload)
        if (why != null) return Half.Refused("proof=$why")
        val ours = end.prover.proof(LinkProof.Role.DIALER, helloD, reply.payload)
        if (!writeRecords(socket, LinkFraming.encode(LinkFraming.Type.PROOF, ours))) return Half.Refused("no reply")
        return Half.Linked(socket, proven = true)
    }

    @Suppress("ReturnCount") // one early return per outcome, in the order the records arrive
    private fun proofBuildAnswer(
        end: End,
        socket: LinkSocket,
    ): Half {
        // A KEY_SHARE was an unknown record type to that build's reader: it throws and the accept closes, no reply.
        val dialer = LinkHandshake.receiveHello(socket.input) ?: return Half.Refused("no hello")
        if (dialer.malformedOffer) return Half.Refused("proof=no-nonce")
        val helloR = oldHello(end, LinkHandshake.freshNonce())
        val reply = LinkFraming.encode(LinkFraming.Type.HELLO, helloR)
        if (!dialer.offersProof) {
            return if (writeRecords(socket, reply)) Half.Linked(socket, proven = false) else Half.Refused("write failed")
        }
        val proof = LinkFraming.encode(LinkFraming.Type.PROOF, end.prover.proof(LinkProof.Role.RESPONDER, dialer.payload, helloR))
        if (!writeRecords(socket, reply + proof)) return Half.Refused("write failed")
        val why = readProof(socket, dialer.wire.nodeId, LinkProof.Role.DIALER, dialer.payload, helloR)
        return if (why == null) Half.Linked(socket, proven = true) else Half.Refused("proof=$why")
    }

    // --- v2.10.x: release/2.10.2's `writeHello` / `readHello` ---

    private fun plainBuildDial(
        end: End,
        socket: LinkSocket,
        expectedId: String,
    ): Half {
        if (!writeRecords(socket, LinkFraming.encode(LinkFraming.Type.HELLO, oldHello(end, nonce = null)))) return Half.Refused("no reply")
        val reply = LinkHandshake.receiveHello(socket.input) ?: return Half.Refused("no reply")
        return if (reply.wire.nodeId == expectedId) Half.Linked(socket, proven = false) else Half.Refused("wrong peer")
    }

    private fun plainBuildAnswer(
        end: End,
        socket: LinkSocket,
    ): Half {
        LinkHandshake.receiveHello(socket.input) ?: return Half.Refused("no hello")
        val reply = LinkFraming.encode(LinkFraming.Type.HELLO, oldHello(end, nonce = null))
        return if (writeRecords(socket, reply)) Half.Linked(socket, proven = false) else Half.Refused("write failed")
    }

    /** An older build's HELLO: `Protocol.advertise`'s three fields over that build's caps, and its nonce when it had one. */
    private fun oldHello(
        end: End,
        nonce: ByteArray?,
    ): ByteArray {
        val base = "${end.nodeId}|${Protocol.VERSION}|${end.door.helloCaps.toString(HEX)}"
        val n = nonce?.let { "|n" + it.joinToString("") { b -> "%02x".format(b) } }.orEmpty()
        return (base + n).encodeToByteArray()
    }

    private fun writeRecords(
        socket: LinkSocket,
        bytes: ByteArray,
    ): Boolean =
        try {
            socket.output.write(bytes)
            socket.output.flush()
            true
        } catch (_: IOException) {
            false
        }

    /** The v1 PROOF that must be the next record: null when it verifies, else why not (`LinkHandshake.readProof`). */
    private fun readProof(
        socket: LinkSocket,
        signerId: String,
        role: LinkProof.Role,
        helloD: ByteArray,
        helloR: ByteArray,
    ): String? {
        val record =
            runCatching { LinkFraming.read(socket.input) }.getOrElse { return LinkHandshake.WHY_UNREADABLE }
                ?: return LinkHandshake.WHY_EOF
        if (record.type != LinkFraming.Type.PROOF) return "${record.type.name.lowercase()} record before the proof"
        return LinkProof.verify(record.payload, signerId, role, helloD, helloR)?.name?.lowercase()
    }

    /**
     * One direction of a registered door link: every frame the lab's pipe delivers is first written to the sender's
     * registered socket as a `FRAME` record ([WireCodec.encodeWire], as `FramedLink.send` is handed it) and read back off
     * the receiver's, as `FramedLink`'s reader reads it — through the seal on a sealed link. Synchronous, under one lock per
     * direction, so a pipe still delivers in order and a send still returns once the far end holds the frame. A record a
     * seal never carries, or a frame that won't open, is the failed seal `FramedLink.readRecord` reports
     * ([SealedLinkSocket.sealViolated], the count armed at registration): [cross] answers null and the link is down.
     * Files and custody digests still cross the lab's pipe as objects, not through the door.
     */
    class Crossing(
        private val out: LinkSocket,
        private val into: LinkSocket,
        /** The raw bytes of this direction, for a scenario that flips one ([LabTransport.tamperNext]). */
        val raw: Bytes,
    ) {
        private val lock = Any()

        @Volatile
        var broken = false
            private set

        /** [wire] as the receiver read it, or null when the link failed under it (it is down from here). */
        fun cross(wire: WireEnvelope): WireEnvelope? =
            synchronized(lock) {
                if (broken) return null
                val read =
                    try {
                        LinkFraming.write(out.output, LinkFraming.Type.FRAME, WireCodec.encodeWire(wire))
                        out.output.flush()
                        readRecord()
                    } catch (_: IOException) {
                        null
                    }
                if (read == null) broken = true
                read?.let { WireCodec.decodeWire(it) }
            }

        private fun readRecord(): ByteArray? {
            val msg =
                try {
                    LinkFraming.read(into.input)
                } catch (e: SealFramingException) {
                    (into as? SealedLinkSocket)?.sealViolated()
                    throw e
                } ?: return null
            return when (msg.type) {
                LinkFraming.Type.FRAME -> {
                    msg.payload
                }

                LinkFraming.Type.KEY_SHARE, LinkFraming.Type.SEALED -> {
                    (into as? SealedLinkSocket)?.sealViolated()
                    null
                }

                else -> {
                    null
                }
            }
        }

        fun close() {
            broken = true
            out.close()
            into.close()
        }
    }

    private const val HEX = 16
    private const val BYTE_MASK = 0xff
    private const val NANOS_PER_MS = 1_000_000L
}

/** What each half of a link door ([MeshLab.doorLink]) ended in. */
class DoorResult(
    val dialer: LabDoor.Half,
    val responder: LabDoor.Half,
) {
    /** Both halves linked, so the link registered. */
    val linked: Boolean get() = dialer is LabDoor.Half.Linked && responder is LabDoor.Half.Linked

    /** It registered, and both ends ride `SEALED` frames. */
    val sealed: Boolean
        get() = linked && (dialer as LabDoor.Half.Linked).sealed && (responder as LabDoor.Half.Linked).sealed

    /** It registered, and each end holds the other's proof. */
    val proven: Boolean
        get() = linked && (dialer as LabDoor.Half.Linked).proven && (responder as LabDoor.Half.Linked).proven

    override fun toString(): String = "dialer=${describe(dialer)} responder=${describe(responder)}"

    private fun describe(half: LabDoor.Half): String =
        when (half) {
            is LabDoor.Half.Linked -> "linked(sealed=${half.sealed}, proven=${half.proven})"
            is LabDoor.Half.Refused -> "refused(${half.why})"
        }
}

/**
 * Links two started nodes through the link door (#313, [LabDoor]): [a] dials and [b] answers, each running its build's
 * handshake ([LabLimits.linkDoor]) over an in-memory socket pair, and the link registers only when both halves linked,
 * as [MeshLab.link] then does it. From there every frame either way crosses the sockets each half registered — through the
 * seal on a sealed link — with the live link's failure count armed on each sealed end and `linksSealed` counted, as the
 * planes register a link. [advertSeals] is the seal flag of the advert [a] dials from: [b]'s build by default, false
 * for a relay that strips it. Counts each refusal where the planes do ([LinkSealPolicy]); returns both halves' ends.
 */
suspend fun MeshLab.doorLink(
    a: LabNode,
    b: LabNode,
    advertSeals: Boolean = b.linkDoor.seals,
    arm: () -> Unit = {},
): DoorResult {
    listOf(a, b).forEach { check(!it.meshPaused) { "${it.name}'s mesh is paused: its radios are down until resumeMesh()" } }
    // An honest handshake never stalls on its peer, so its reads wait out the lab's own bound, not the plane's 5 s: a
    // throttled runner's pause past 5 s would read as a refusal no scenario asked for. `dialRaw` keeps the plane's.
    val sockets = LabDoor.Sockets(chaos, readBoundMs = MeshLab.AWAIT_MS)
    val result =
        withContext(Dispatchers.IO) {
            val answered = async { LabDoor.answer(b.doorEnd(), sockets.responder) }
            val dialed = async { LabDoor.dial(a.doorEnd(), sockets.dialer, b.nodeId, advertSeals) }
            DoorResult(dialed.await(), answered.await())
        }
    if (!result.linked) {
        sockets.dialer.close()
        sockets.responder.close()
        return result
    }
    val dialer = result.dialer as LabDoor.Half.Linked
    val responder = result.responder as LabDoor.Half.Linked
    for ((node, half) in listOf(a to dialer, b to responder)) {
        (half.socket as? SealedLinkSocket)?.let { seal ->
            seal.armFailureCount { node.metrics.onLinkSealFailed() }
            node.metrics.onLinkSealed()
        }
    }
    val forward = LabDoor.Crossing(dialer.socket, responder.socket, sockets.forward)
    val back = LabDoor.Crossing(responder.socket, dialer.socket, sockets.back)
    a.transport.connect(b.transport, arm = arm, doors = forward to back)
    return result
}

/**
 * A hostile dialer at [target]'s door (#313): [script] writes raw bytes on the dialer's socket (and may read what the
 * target answers), then the socket closes. Returns the target's half, which ran its build's accept and counted what
 * it met; nothing registers whatever it ended in.
 */
suspend fun MeshLab.dialRaw(
    target: LabNode,
    script: (LinkSocket) -> Unit,
): LabDoor.Half {
    val sockets = LabDoor.Sockets(chaos)
    return withContext(Dispatchers.IO) {
        val answered = async { LabDoor.answer(target.doorEnd(), sockets.responder) }
        runCatching { script(sockets.dialer) }
        sockets.dialer.close()
        answered.await().also { sockets.responder.close() }
    }
}
