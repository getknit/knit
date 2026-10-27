package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * What the Keystore answered about one device-key alias, with no decision on it: the thin seam under [DeviceKeys]
 * (`data/crypto/AndroidDeviceKeystore` on a phone, a scripted fake in the JVM tests). Nothing here throws, and nothing
 * here is called on the main thread.
 */
interface DeviceKeystore {
    /** One lookup of [alias]. */
    fun look(alias: String): Look

    /** Makes a pair under [alias], which [DeviceKeys] never reuses: a new alias every time, so nothing is overwritten. */
    fun generate(alias: String): Made

    /** Deletes [alias]; false when the Keystore refused. */
    fun delete(alias: String): Boolean

    /** Every alias the Keystore holds that starts with [prefix], or null when it refused to list them. */
    fun aliases(prefix: String): List<String>?

    sealed interface Look {
        class Present(
            val pair: RawPair,
        ) : Look

        /** The Keystore said there is no key under the alias (keystore2's KEY_NOT_FOUND). */
        data object Absent : Look

        /**
         * A private key whose certificate read came back empty: the legacy keystore's half-made pair when the process
         * died inside `generateKeyPair`, or a read that failed without a throw (keystore2 answers null for a busy
         * backend). [DeviceKeys] treats it as a refusal and never deletes it on that word.
         */
        data object Broken : Look

        /** It threw: busy, locked, a dead daemon. Says nothing about the pair. */
        data object Refused : Look
    }

    sealed interface Made {
        class Pair(
            val pair: RawPair,
        ) : Made

        /** This phone cannot hold an EC P-256 key in its Keystore at all. */
        data object Unsupported : Made

        /** It threw for any other reason, which may not happen again. */
        data object Refused : Made
    }

    /** A pair's two operations, as the Keystore answers them. */
    interface RawPair {
        fun publicKey(): ByteArray?

        fun sign(message: ByteArray): Signed
    }

    sealed interface Signed {
        class Signature(
            val der: ByteArray,
        ) : Signed

        /** `KeyPermanentlyInvalidatedException` down the cause chain (ADR 2026-10.47rw). */
        data object Lost : Signed

        data object Refused : Signed
    }
}

/**
 * Which pair the issuer last accepted a proof from, per customer key's slot, in the settings DataStore under
 * `spool_device:`, which never rides a backup (`SettingsKeys.TRANSIENT_PREFIXES`). Implemented by
 * `data/settings/TicketKeyStore`. A lost entry costs nothing but a guess: the oldest pair signs.
 */
interface DeviceKeyMarks {
    /** Slot → the accepted pair's id. */
    suspend fun accepted(): Map<String, String>

    suspend fun putAccepted(
        slot: String,
        id: String,
    )

    suspend fun removeAccepted(slot: String)
}

/**
 * The device keys, one or more pairs per customer key (docs/VOICE_CALLS.md C-7.6-39, ADR 2026-10.wk3m), kept so that no
 * Keystore failure, however long it lasts, can lock the account out. A lockout needs the bound private key destroyed,
 * or an issuer that only ever sees proofs from pairs it did not bind. So:
 *
 * - **Nothing is ever made over a pair.** Each pair has an alias of its own ([slotPrefix] and a fresh suffix, its id),
 *   and a pair is deleted only when its key is no longer held ([retain]) or when the issuer has accepted a proof from
 *   another pair of the same key ([accepted]), which proves this one is not the bound one.
 * - **The pair that signs** is the one the issuer last accepted, else the oldest, which is the bound one even after a
 *   restore drops the marks; [forKey]'s `avoid` steers past pairs a 409 already refused, so a 409 answered to a stray
 *   pair is asked again with the next.
 * - **A refusal sends nothing.** A Keystore that refused to list, look up or sign is [DeviceKey.Refused] or a null
 *   signature, and the ask backs off as no verdict; while the preferred pair is unreadable, no other pair signs in its
 *   place. A pair that has refused for over [SET_ASIDE_AFTER_MS] is set aside
 *   for [SET_ASIDE_MS] (in memory only), and a new pair is made beside it, so a pair that never answers again does not
 *   stall the phone; once the set-aside ends, the old pair is tried first again.
 * - Only a phone that holds no pair for the key and whose Keystore cannot make one ([DeviceKeystore.Made.Unsupported])
 *   sends unsigned ([DeviceKey.Unmade]).
 *
 * [create], [forKey], [accepted] and [retain] take one lock, and every Keystore call runs on [io]. No public call
 * throws. Neither an alias nor anything the Keystore returns is logged.
 */
class DeviceKeys(
    private val keystore: DeviceKeystore,
    private val marks: DeviceKeyMarks,
    // The customer keys held, by relay host name, or null when the store could not read every one of them: then nothing
    // is swept, since a key read as missing would cost its bound pair. Free keys carry no device key (C-7.6-37).
    private val held: suspend () -> Map<String, String>?,
    // A monotonic clock (millis) for the in-memory set-asides; a wall clock jump moves nothing here.
    private val elapsed: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: () -> Int = { SecureRandom().nextInt() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) : DeviceKeyring {
    private val lock = Mutex()

    // Alias → its run of refusals: when the first came, and until when it is set aside.
    private val refusals = ConcurrentHashMap<String, Refusals>()

    // A phone whose Keystore cannot make the key at all says so on every try; asked once per process.
    @Volatile
    private var unsupported = false

    override suspend fun create(
        host: String,
        key: String,
    ) {
        forKey(host, key)
    }

    override suspend fun forKey(
        host: String,
        key: String,
        avoid: Set<String>,
    ): DeviceKey =
        guarded(DeviceKey.Refused) {
            lock.withLock { withContext(io) { choose(slotOf(host, key), avoid) } }
        }

    override suspend fun accepted(
        host: String,
        key: String,
        id: String,
    ) {
        guarded(Unit) {
            lock.withLock {
                val slot = slotOf(host, key)
                runCatching { marks.putAccepted(slot, id) }
                withContext(io) {
                    // The issuer bound this pair: every other pair of the key is proven unbound.
                    keystore.aliases(slotPrefix(slot))?.filter { idOf(it) != id }?.forEach { keystore.delete(it) }
                }
            }
        }
    }

    override suspend fun retain() {
        guarded(Unit) {
            lock.withLock {
                val wanted = held()?.map { (host, key) -> slotOf(host, key) }?.toSet() ?: return@withLock
                withContext(io) {
                    keystore.aliases(ALIAS_PREFIX)?.filter { slotOfAlias(it) !in wanted }?.forEach { keystore.delete(it) }
                }
                runCatching {
                    marks
                        .accepted()
                        .keys
                        .filter { it !in wanted }
                        .forEach { marks.removeAccepted(it) }
                }
            }
        }
    }

    private suspend fun choose(
        slot: String,
        avoid: Set<String>,
    ): DeviceKey {
        val listed = keystore.aliases(slotPrefix(slot)) ?: return DeviceKey.Refused
        val accepted = runCatching { marks.accepted()[slot] }.getOrNull()
        // An empty listing for a key whose pair the issuer accepted is the Keystore failing to list (both keystores
        // answer an empty set on error), not the pair gone: wait, until it has said so for over an hour.
        if (listed.isEmpty() && accepted != null && !emptyForLong(slot)) return DeviceKey.Refused
        if (listed.isNotEmpty()) refusals.remove(EMPTY_RUN + slot)
        // The accepted pair first, then by age: the oldest is the first this phone ever signed with.
        val ordered = listed.sortedWith(compareBy({ idOf(it) != accepted }, { ageOf(it) }))
        return when (val pick = pick(ordered, avoid)) {
            is Pick.Sign -> DeviceKey.Ready(Signer(pick.alias, pick.pair), idOf(pick.alias))
            Pick.Wait -> DeviceKey.Refused
            Pick.None -> make(slot, listed)
        }
    }

    private sealed interface Pick {
        class Sign(
            val alias: String,
            val pair: DeviceKeystore.RawPair,
        ) : Pick

        /** A pair that may be intact is unreadable right now: sign with nothing, make nothing. */
        data object Wait : Pick

        /** No pair to sign with, and none that might be: make one. */
        data object None : Pick
    }

    // The first pair in [ordered] that is not set aside and no 409 has refused; else the first a 409 refused (the caller
    // then sees nothing new to try).
    private fun pick(
        ordered: List<String>,
        avoid: Set<String>,
    ): Pick {
        var refusedBefore: Pick.Sign? = null
        var unreadable = false
        for (alias in ordered) {
            if (setAside(alias)) continue
            when (val look = keystore.look(alias)) {
                is DeviceKeystore.Look.Present -> {
                    if (idOf(alias) !in avoid) return Pick.Sign(alias, look.pair)
                    if (refusedBefore == null) refusedBefore = Pick.Sign(alias, look.pair)
                }

                // A pair that may be intact and only unreadable right now (a missing certificate included: keystore2
                // answers null for a busy backend): wait for it, rather than sign with the next or make another. A pair
                // that stays unreadable is set aside after an hour, and only then does the next one sign.
                DeviceKeystore.Look.Refused, DeviceKeystore.Look.Broken -> {
                    noteRefused(alias)
                    if (idOf(alias) !in avoid) return Pick.Wait
                    unreadable = true
                }

                DeviceKeystore.Look.Absent -> {}
            }
        }
        return refusedBefore ?: if (unreadable) Pick.Wait else Pick.None
    }

    // Whether a slot's listing has come back empty, with a pair accepted, for over SET_ASIDE_AFTER_MS.
    private fun emptyForLong(slot: String): Boolean {
        val run = EMPTY_RUN + slot
        if (setAside(run)) return true
        noteRefused(run)
        return setAside(run)
    }

    private fun make(
        slot: String,
        listed: List<String>,
    ): DeviceKey {
        val holdsAny = listed.isNotEmpty()
        // Pairs set aside stay; a few is enough, and past that the phone waits for one of them to answer.
        if (listed.size >= MAX_PAIRS) return DeviceKey.Refused
        if (unsupported) return if (holdsAny) DeviceKey.Refused else DeviceKey.Unmade
        val alias = slotPrefix(slot) + newSuffix()
        return when (val made = keystore.generate(alias)) {
            is DeviceKeystore.Made.Pair -> {
                DeviceKey.Ready(Signer(alias, made.pair), idOf(alias))
            }

            DeviceKeystore.Made.Unsupported -> {
                unsupported = true
                // Never unsigned while this phone holds a pair for the key: the issuer may have bound it.
                if (holdsAny) DeviceKey.Refused else DeviceKey.Unmade
            }

            DeviceKeystore.Made.Refused -> {
                DeviceKey.Refused
            }
        }
    }

    private fun setAside(alias: String): Boolean = (refusals[alias]?.asideUntil ?: 0L) > elapsed()

    // A run of refusals that has lasted SET_ASIDE_AFTER_MS sets the pair aside for SET_ASIDE_MS. In memory only.
    private fun noteRefused(alias: String) {
        val now = elapsed()
        // A run still going carries on; one that ended in a set-aside starts afresh.
        val prior = refusals[alias]?.takeIf { it.asideUntil == 0L }
        val first = prior?.firstAt ?: now
        refusals[alias] = if (now - first >= SET_ASIDE_AFTER_MS) Refusals(now, now + SET_ASIDE_MS) else Refusals(first, 0L)
    }

    private fun newSuffix(): String = wallClock().toString(RADIX) + SUFFIX_MARK + (random() and SUFFIX_MASK).toString(RADIX)

    /** Signs through [pair]; a refusal or a loss is noted against the pair, a signature clears its run of refusals. */
    private inner class Signer(
        private val alias: String,
        private val pair: DeviceKeystore.RawPair,
    ) : DeviceSigner {
        override suspend fun publicKey(): ByteArray? = guarded(null) { withContext(io) { pair.publicKey() } }

        override suspend fun sign(message: ByteArray): ByteArray? =
            guarded(null) {
                when (val signed = withContext(io) { pair.sign(message) }) {
                    is DeviceKeystore.Signed.Signature -> {
                        refusals.remove(alias)
                        signed.der
                    }

                    DeviceKeystore.Signed.Lost -> {
                        // Invalidated: set aside at once, never deleted — a pair is not destroyed on a Keystore's word.
                        val now = elapsed()
                        refusals[alias] = Refusals(now, now + SET_ASIDE_MS)
                        null
                    }

                    DeviceKeystore.Signed.Refused -> {
                        noteRefused(alias)
                        null
                    }
                }
            }

        override fun toString(): String = "DeviceSigner"
    }

    private class Refusals(
        val firstAt: Long,
        val asideUntil: Long,
    )

    @Suppress("TooGenericExceptionCaught") // a DataStore or Keystore surprise is a refusal, never a crash
    private suspend fun <T> guarded(
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    companion object {
        const val ALIAS_PREFIX = "knit_device_"

        // The refusal run of a slot whose listing comes back empty, kept beside the pairs' own runs.
        private const val EMPTY_RUN = "empty:"

        /** How long a pair must have refused before it is set aside and a new pair made beside it. */
        const val SET_ASIDE_AFTER_MS = 60 * 60 * 1_000L

        /** How long a pair is set aside before it is tried first again. */
        const val SET_ASIDE_MS = 6 * 60 * 60 * 1_000L

        /** The most pairs one customer key holds at once: past it, the phone waits for one of them to answer. */
        const val MAX_PAIRS = 3

        private const val ALIAS_LABEL = "knit/device-alias/v1"
        private const val SLOT_HEX_CHARS = 32
        private const val NANOS_PER_MS = 1_000_000L
        private const val RADIX = 36

        // Outside the base-36 alphabet, so the wall-clock part reads back whole.
        private const val SUFFIX_MARK = '-'
        private const val SUFFIX_MASK = 0xffff

        /**
         * One customer key's slot: 16 bytes of `SHA-256(label ‖ 0 ‖ host ‖ 0 ‖ key)` in hex. A key is for one relay's
         * `aud`, so one host and one slot; the host is in it so that a key replaced at one host moves to a new slot.
         */
        fun slotOf(
            host: String,
            key: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ALIAS_LABEL.toByteArray(Charsets.US_ASCII))
            digest.update(0)
            digest.update(host.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(key.toByteArray(Charsets.US_ASCII))
            return digest.digest().joinToString("") { "%02x".format(it) }.take(SLOT_HEX_CHARS)
        }

        /** Every pair of [slot] has an alias starting with this. */
        fun slotPrefix(slot: String): String = "$ALIAS_PREFIX${slot}_"

        /** The slot a device-key alias belongs to, or null for one this build did not name. */
        fun slotOfAlias(alias: String): String? =
            alias
                .removePrefix(ALIAS_PREFIX)
                .takeIf { it != alias }
                ?.substringBefore('_')
                ?.takeIf { it.length == SLOT_HEX_CHARS }

        /** A pair's id within its slot: its alias's suffix. */
        fun idOf(alias: String): String = alias.substringAfterLast('_')

        // When the pair was made, for "oldest first": the suffix's wall-clock part, or the far future if unreadable.
        private fun ageOf(alias: String): Long = idOf(alias).substringBefore(SUFFIX_MARK).toLongOrNull(RADIX) ?: Long.MAX_VALUE
    }
}
