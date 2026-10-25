package app.getknit.knit.call

import app.getknit.knit.mesh.spool.TurnHosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One server's measurement (C-7.6-52): three STUN Binding requests to [TurnHosts.Entry], 200 ms apart, each waited on
 * for 1 s, over UDP on the validated default network. The Android probe is `ui/call/UdpTurnProbe`; a fake under test.
 */
fun interface TurnProbe {
    suspend fun measure(entry: TurnHosts.Entry): Result

    sealed interface Result {
        /** The least round trip of the answers accepted. */
        class Answered(
            val ms: Long,
        ) : Result

        /** Nothing acceptable came back, or the name led nowhere a probe may go. */
        data object Silent : Result

        /** No validated network to measure on: nothing was sent, and nothing is learned. */
        data object NoRoute : Result
    }
}

/**
 * Keeps [ranking] measured (C-7.6-52): every entry in [targets] — the union of `turnHosts` over the connected spools
 * that would issue this phone TURN — once per network. It measures when the default network changes ([routeChanges],
 * which also drops the last network's scores) and when a HELLO brings an entry not yet measured here, and never while
 * a call is being set up ([callWaiting]): the round waits for it, and a call uses the ranking as it stands.
 *
 * No timer runs between those events, and nothing is measured while [targets] is empty, so a phone no spool would
 * issue TURN — a fresh install among them — sends nothing. A round a network change overtook is dropped whole: its
 * answers were for the old network, and the change starts a round of its own.
 */
class TurnMeasure(
    private val ranking: TurnRanking,
    private val probe: TurnProbe,
    private val targets: Flow<List<String>>,
    private val routeChanges: Flow<Unit>,
    private val callWaiting: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    // Under [mutex]: the network's generation, the entries measured or in flight on it, and the latest targets.
    private var generation = 0L
    private val measured = HashSet<String>()
    private var current: List<String> = emptyList()

    /** Starts following the targets and the network, for the process's life. */
    fun start() {
        scope.launch {
            targets.collect { hosts ->
                mutex.withLock { current = hosts }
                measureNew()
            }
        }
        scope.launch {
            routeChanges.collect {
                mutex.withLock {
                    generation++
                    measured.clear()
                    ranking.networkChanged()
                }
                measureNew()
            }
        }
    }

    // Launches a round over every current entry not yet measured on this network.
    private suspend fun measureNew() {
        val (round, todo) = mutex.withLock { generation to current.filter { measured.add(it) } }
        if (todo.isEmpty()) return
        scope.launch {
            callWaiting.first { !it }
            val results = coroutineScope { todo.map { entry -> async { entry to measureOne(entry) } }.awaitAll() }
            val kept =
                mutex.withLock {
                    if (round != generation) return@withLock false
                    for ((entry, result) in results) {
                        when (result) {
                            is TurnProbe.Result.Answered -> ranking.record(entry, result.ms)

                            TurnProbe.Result.Silent -> ranking.record(entry, null)

                            // Nothing was learned: the entry is measured again on the next event.
                            TurnProbe.Result.NoRoute -> measured.remove(entry)
                        }
                    }
                    true
                }
            if (kept) {
                val answered = results.count { it.second is TurnProbe.Result.Answered }
                log("turn: measured ${results.size} server(s), $answered answered")
            }
        }
    }

    private suspend fun measureOne(entry: String): TurnProbe.Result {
        val parsed = TurnHosts.parse(entry) ?: return TurnProbe.Result.Silent
        return probe.measure(parsed)
    }
}

/**
 * The TURN servers this phone would measure (C-7.6-52), as the Internet plane last published them: written by the
 * mesh (`MeshManager`'s `onTurnHosts`), read by [TurnMeasure]. A holder of its own so the call graph never has to
 * build the mesh graph to follow it.
 */
class TurnTargets {
    val hosts = MutableStateFlow<List<String>>(emptyList())
}
