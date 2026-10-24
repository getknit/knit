package app.getknit.knit.ui.translate

import android.os.SystemClock
import app.getknit.knit.moderation.ModelLease
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.translate.Detection
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateEngine
import app.getknit.knit.translate.TranslateLang
import app.getknit.translatenative.TranslateJni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The spike's engine on a phone (ADR 2026-10.t2h6): slimt for each direction's model, CLD2 for the language, through
 * [TranslateJni]. Each direction's model sits on its own [ModelLease], built under [ModelLoadGuard] as
 * [ModelLoadGuard.TRANSLATE] — slimt aborts the process on a file it cannot read, and the poison-pill is what stops that
 * repeating on every tap — and let go after [IDLE_UNLOAD_MS] unused. A lease is made afresh when the store's files for
 * its direction change.
 *
 * Each loaded model lives on a thread of its own, which ends when the model is freed. slimt keeps ruy's packed copies
 * of a model's weights in a per-thread cache keyed by the weights' address and never clears it for model weights, so a
 * model freed and loaded again at the same address on the same thread reads the old model's packs: the emulator wrote
 * "sssssssSSSS" for "¿Vienes al parque más tarde?" after a reload. A thread that ends takes its cache with it, and one
 * thread per model also keeps one copy of the packs rather than one per pool thread that ever ran it.
 */
class SlimtEngine(
    private val store: TranslateModelStore,
    private val guard: ModelLoadGuard,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : TranslateEngine {
    /** A model slimt loaded, and the one thread every call on it runs on. */
    class Loaded(
        val handle: Long,
        private val thread: ExecutorService,
    ) {
        fun <T> on(block: () -> T): T = thread.submit(Callable(block)).get()

        fun free() {
            runCatching { on { TranslateJni.nativeFree(handle) } }
            thread.shutdown()
        }
    }

    // CLD2 keeps no state between calls: any one thread will do.
    private val detector = Dispatchers.Default.limitedParallelism(1)
    private val lock = Any()
    private val leases = mutableMapOf<TranslateDirection, Pair<Int, ModelLease<Loaded>>>()

    override fun installed(direction: TranslateDirection): Boolean = store.paths(direction) != null

    override suspend fun detect(
        text: String,
        hint: TranslateLang?,
    ): Detection? =
        withContext(detector) {
            if (!TranslateJni.available()) return@withContext null
            Detection.parse(TranslateJni.nativeDetect(text.toByteArray(Charsets.UTF_8), hint?.code.orEmpty()))
        }

    override suspend fun translate(
        direction: TranslateDirection,
        text: String,
    ): String? =
        withContext(Dispatchers.Default) {
            val lease = lease(direction) ?: return@withContext null
            lease.use { model ->
                model?.on { TranslateJni.nativeTranslate(model.handle, text.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8) }
            }
        }

    /** Which directions hold a model in memory now (the debug bridge). */
    fun resident(): Map<TranslateDirection, Boolean> = synchronized(lock) { leases.mapValues { it.value.second.isResident } }

    /** Lets every model go (the debug bridge). */
    suspend fun unload() {
        val held = synchronized(lock) { leases.values.map { it.second } }
        held.forEach { it.unload() }
    }

    /** Loads [direction]'s model from cold and says how long it took; null when it would not load (the debug bridge). */
    suspend fun timeLoad(direction: TranslateDirection): Long? =
        withContext(Dispatchers.Default) {
            val lease = lease(direction) ?: return@withContext null
            lease.unload()
            val started = SystemClock.elapsedRealtime()
            val model = lease.use { it }
            model?.let { SystemClock.elapsedRealtime() - started }
        }

    // The lease over [direction]'s files on disk now, made afresh when they changed; null with no model there.
    private fun lease(direction: TranslateDirection): ModelLease<Loaded>? {
        val files = store.paths(direction) ?: return null
        val generation = store.generation(direction)
        val (fresh, stale) =
            synchronized(lock) {
                val held = leases[direction]
                if (held != null && held.first == generation) return held.second
                val made =
                    ModelLease(
                        scope = scope,
                        idleMs = IDLE_UNLOAD_MS,
                        load = { guard.guard(ModelLoadGuard.TRANSLATE) { build(direction, files) } },
                        close = Loaded::free,
                    )
                leases[direction] = generation to made
                made to held?.second
            }
        stale?.let { scope.launch { it.unload() } }
        return fresh
    }

    // Under the guard: slimt over the three files, on the thread the model will keep.
    private fun build(
        direction: TranslateDirection,
        files: Map<String, File>,
    ): Loaded? {
        if (!TranslateJni.available()) {
            log("translate $direction: no native library")
            return null
        }
        val thread = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "translate-$direction").apply { isDaemon = true } }
        val started = SystemClock.elapsedRealtime()
        val handle =
            runCatching {
                thread
                    .submit(
                        Callable {
                            // A Java method: no named arguments. Model, vocabulary, shortlist, then the layer counts.
                            TranslateJni.nativeLoad(
                                files.getValue(direction.model).path,
                                files.getValue(direction.vocabulary).path,
                                files.getValue(direction.shortlist).path,
                                TranslateDirection.ENCODER_LAYERS,
                                direction.decoderLayers,
                            )
                        },
                    ).get()
            }.getOrDefault(0L)
        log("translate $direction: load ${if (handle == 0L) "refused" else "ok"} in ${SystemClock.elapsedRealtime() - started} ms")
        if (handle == 0L) {
            thread.shutdown()
            return null
        }
        return Loaded(handle, thread)
    }

    companion object {
        /** Two quiet minutes, as speech: a model is 32 MB of weights, and a chat's translations come in a burst. */
        const val IDLE_UNLOAD_MS = 2 * 60_000L
    }
}
