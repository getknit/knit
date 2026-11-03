package app.getknit.knit.translate

import app.getknit.knit.call.SttFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The archive a bundle of translation models travels as (ADR 2026-11.ejn8): one byte string per plan, sized from the
 * pins alone, and an ordinary tar a phone that takes it as a file can open.
 */
class ModelArchiveTest {
    private val m = Random(1).nextBytes(70_001)
    private val v = Random(2).nextBytes(512)
    private val plan =
        listOf(
            "mozilla-en-es-3b1c3995" to listOf(pin("model.enes.bin", m)),
            "mozilla-es-en-4aed7734" to listOf(pin("vocab.esen.spm", v)),
        )
    private val bytes = mapOf("model.enes.bin" to m, "vocab.esen.spm" to v)

    private fun archive() = ModelArchive.stream(plan) { _, file -> bytes.getValue(file.name).inputStream() }.readBytes()

    @Test
    fun theSizeIsTheArchivesFromThePinsAlone() {
        // A 512-byte header per file, each file padded to a block (the vocabulary is exactly one), and two zero blocks.
        val expected = (512 + 70_144) + (512 + 512) + 1_024L
        assertEquals(expected, ModelArchive.size(plan))
        assertEquals(expected, archive().size.toLong())
        // Deterministic: the same plan is the same bytes every time.
        assertArrayEquals(archive(), archive())
    }

    @Test
    fun aHeaderIsAPlainUstarHeaderWithAValidChecksum() {
        val h = ModelArchive.header("mozilla-en-es-3b1c3995/model.enes.bin", 70_001)
        assertEquals(512, h.size)
        assertEquals("mozilla-en-es-3b1c3995/model.enes.bin", String(h, 0, 37, Charsets.US_ASCII))
        assertEquals("00000210561", String(h, 124, 11, Charsets.US_ASCII)) // 70001 in octal
        assertEquals("ustar\u000000", String(h, 257, 8, Charsets.US_ASCII))
        val stored = String(h, 148, 6, Charsets.US_ASCII).toInt(8)
        val sum = h.copyOf().also { for (i in 148 until 156) it[i] = ' '.code.toByte() }.sumOf { it.toInt() and 0xFF }
        assertEquals(sum, stored)
    }

    @Test
    fun theSystemsTarReadsItBack() {
        val tar = File("/usr/bin/tar").takeIf { it.canExecute() } ?: File("/bin/tar")
        assumeTrue("no tar on this host", tar.canExecute())
        val dir = Files.createTempDirectory("tar").toFile()
        try {
            val file = File(dir, ModelArchive.FILE_NAME).also { it.writeBytes(archive()) }
            val out = File(dir, "out").also { it.mkdirs() }
            val proc = ProcessBuilder(tar.path, "-xf", file.path, "-C", out.path).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor(30, TimeUnit.SECONDS)) { "tar hung" }
            assertEquals(log, 0, proc.exitValue())
            assertArrayEquals(m, File(out, "mozilla-en-es-3b1c3995/model.enes.bin").readBytes())
            assertArrayEquals(v, File(out, "mozilla-es-en-4aed7734/vocab.esen.spm").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun pin(
        name: String,
        bytes: ByteArray,
    ) = SttFile(name, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
