package app.getknit.knit.fieldlog

import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A peer's name in one field log: `p` + the first eight hex digits of HMAC-SHA256(salt, node id). The salt is
 * random, kept beside the journal and replaced by Clear, so a peer reads the same across restarts inside one
 * log (its links, drops and relinks line up) but cannot be matched across two people's logs, or across a
 * Clear on one phone. Someone holding this phone *and* a node id could still test it — the residual the ADR
 * (2026-10.a239) records. A relay is named the same way under its own prefix, `r` ([ofRelay]), from its redacted
 * URL in a domain of its own, so a relay's name can never equal a peer's.
 */
class Pseudonyms(
    salt: ByteArray,
) {
    private val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(salt, ALGORITHM)) }

    fun of(nodeId: String): String = name(PREFIX, nodeId)

    /** A relay, by its redacted URL (`SpoolUrl.redact`): `r` + eight hex digits, never the host. */
    fun ofRelay(url: String): String = name(RELAY_PREFIX, RELAY_DOMAIN + url)

    private fun name(
        prefix: String,
        key: String,
    ): String =
        synchronized(mac) {
            val digest = mac.doFinal(key.toByteArray(Charsets.UTF_8))
            buildString(prefix.length + HEX_CHARS) {
                append(prefix)
                for (i in 0 until HEX_CHARS / 2) append("%02x".format(Locale.ROOT, digest[i].toInt() and BYTE_MASK))
            }
        }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val PREFIX = "p"
        private const val RELAY_PREFIX = "r"

        // No node id begins with it, so a relay's input can never collide with a peer's.
        private const val RELAY_DOMAIN = "relay\u0000"
        private const val HEX_CHARS = 8
        private const val BYTE_MASK = 0xff
        const val SALT_BYTES = 32

        fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    }
}

/**
 * Renders one [FieldEvent] as one JSON object on one line: `{"t":<ms>,"e":"<kind>",<fields>}`. Hand-rolled
 * rather than `org.json` (stubbed on the plain JVM) because the [FieldWriter] contract admits only numbers,
 * booleans, enum constant names and pseudonyms — nothing that needs escaping beyond a guard.
 */
object FieldLine {
    fun render(
        at: Long,
        event: FieldEvent,
        pseudonyms: Pseudonyms,
    ): String {
        val out = StringBuilder(LINE_HINT)
        out
            .append("{\"t\":")
            .append(at)
            .append(",\"e\":\"")
            .append(safe(event.kind))
            .append('"')
        event.write(
            object : FieldWriter {
                override fun num(
                    name: String,
                    value: Long,
                ) {
                    key(name).append(value)
                }

                override fun flag(
                    name: String,
                    value: Boolean,
                ) {
                    key(name).append(value)
                }

                override fun enum(
                    name: String,
                    value: Enum<*>,
                ) {
                    key(name).append('"').append(safe(value.name)).append('"')
                }

                override fun peer(
                    name: String,
                    nodeId: String,
                ) {
                    key(name).append('"').append(pseudonyms.of(nodeId)).append('"')
                }

                override fun relay(
                    name: String,
                    url: String,
                ) {
                    key(name).append('"').append(pseudonyms.ofRelay(url)).append('"')
                }

                private fun key(name: String): StringBuilder = out.append(",\"").append(safe(name)).append("\":")
            },
        )
        return out.append('}').toString()
    }

    /** The `t` of a journal line, or null for a line that is not one of ours (a torn tail, say). */
    fun timeOf(line: String): Long? {
        if (!line.startsWith(T_PREFIX) || !line.endsWith("}")) return null
        val end = line.indexOf(',', T_PREFIX.length)
        if (end < 0) return null
        return line.substring(T_PREFIX.length, end).toLongOrNull()
    }

    /** [line] with its `t` replaced by [at] — how an export rebases the journal's wall clock. */
    fun withTime(
        line: String,
        at: Long,
    ): String = T_PREFIX + at + line.substring(line.indexOf(',', T_PREFIX.length))

    /**
     * Names and enum constants are code, but a future caller could hand in anything: keep only `[A-Za-z0-9_]`, so no
     * value can break out of its quotes or carry text.
     */
    private fun safe(token: String): String = token.filter { (it.isLetterOrDigit() && it.code < ASCII_END) || it == '_' }

    private const val T_PREFIX = "{\"t\":"
    private const val LINE_HINT = 96
    private const val ASCII_END = 128
}
