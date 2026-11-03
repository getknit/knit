package app.getknit.knit.translate

import app.getknit.knit.call.SttFile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

/**
 * A direct transfer's model bundle landing (ADR 2026-11.ejn8): an archive held against the one this phone would write
 * from its own pins, checked before anything is kept, and staged only under a directory this phone named.
 */
class ModelBundleSinkTest {
    private val root = Files.createTempDirectory("bundle").toFile()
    private val incoming = File(root, "incoming").also { it.mkdirs() }
    private val stage = File(incoming, "local-1")
    private val sentinel = File(root, "db.key").also { it.writeText("keep me") }

    private val a0 = Random(1).nextBytes(70_001)
    private val a1 = Random(2).nextBytes(13)
    private val b0 = Random(3).nextBytes(40_000)
    private val plan = listOf("dir-a" to listOf(pin("m.bin", a0), pin("v.spm", a1)), "dir-b" to listOf(pin("m.bin", b0)))
    private val bytes = mapOf(("dir-a" to "m.bin") to a0, ("dir-a" to "v.spm") to a1, ("dir-b" to "m.bin") to b0)
    private val whole = archive(plan)

    private fun archive(plan: List<Pair<String, List<SttFile>>>) =
        ModelArchive.stream(plan) { dir, file -> bytes.getValue(dir to file.name).inputStream() }.readBytes()

    private val adopted = mutableListOf<Map<String, File>>()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val released = mutableListOf<File>()

    private fun sink(adopt: suspend (Map<String, File>) -> Unit = { adopted += it }) =
        ModelBundleSink(stage, plan, adopt = adopt, release = { released += it })

    private fun write(
        sink: ModelBundleSink,
        bytes: ByteArray,
        step: Int = 4_099,
    ) {
        val out = sink.stream()
        var at = 0
        while (at < bytes.size) {
            val n = minOf(step, bytes.size - at)
            out.write(bytes, at, n)
            at += n
        }
    }

    @Test
    fun aBundleIsCutAtEveryFileAndDirectionByThePins() =
        runTest {
            val sink =
                sink { staged ->
                    // Read inside adopt: the stage is removed once it returns.
                    assertArrayEquals(a0, File(staged.getValue("dir-a"), "m.bin").readBytes())
                    assertArrayEquals(a1, File(staged.getValue("dir-a"), "v.spm").readBytes())
                    assertArrayEquals(b0, File(staged.getValue("dir-b"), "m.bin").readBytes())
                    adopted += staged
                }
            write(sink, whole)
            assertTrue(sink.check())
            sink.commit()
            assertEquals(setOf("dir-a", "dir-b"), adopted.single().keys)
            assertFalse(stage.exists())
            assertEquals(listOf(stage), released)
            assertEquals("keep me", sentinel.readText())
        }

    @Test
    fun aByteOnePastTheBundleIsRefused() =
        runTest {
            val sink = sink()
            write(sink, whole)
            val refusal = runCatching { sink.stream().write(byteArrayOf(1), 0, 1) }.exceptionOrNull()
            assertTrue("$refusal", refusal is IOException)
        }

    @Test
    fun aFileThatFailsItsHashOrComesShortFailsTheCheck() =
        runTest {
            // Five bytes into the first file's data, past its 512-byte header.
            val flipped = whole.copyOf().also { it[ModelArchive.BLOCK + 5] = (it[ModelArchive.BLOCK + 5] + 1).toByte() }
            val bad = sink()
            write(bad, flipped)
            assertFalse(bad.check())
            bad.discard()

            val short = sink()
            write(short, whole.copyOf(whole.size - 1))
            assertFalse(short.check())
            short.discard()
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun anyHeaderPaddingOrTrailerByteThePeerWroteDifferentlyFailsTheCheck() =
        runTest {
            // A header's name, the padding after a file, and the trailer: none is read, each is compared.
            val a0Padded = ModelArchive.BLOCK + a0.size + (ModelArchive.BLOCK - a0.size % ModelArchive.BLOCK) % ModelArchive.BLOCK
            for (at in listOf(3, a0Padded - 1, whole.size - 1)) {
                val tampered = whole.copyOf().also { it[at] = (it[at] + 1).toByte() }
                val sink = sink()
                write(sink, tampered)
                assertFalse("byte $at", sink.check())
                sink.discard()
            }
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun aDiscardRemovesOnlyItsOwnStage() =
        runTest {
            val beside = File(incoming, "local-2").also { it.mkdirs() }
            val sink = sink()
            write(sink, whole.copyOf(80_000))
            assertTrue(stage.exists())
            sink.discard()
            assertFalse(stage.exists())
            assertTrue(beside.exists())
            assertEquals("keep me", sentinel.readText())
            assertEquals(listOf(stage), released)
        }

    @Test
    fun anAdoptThatThrowsStillRemovesTheStage() =
        runTest {
            val sink = sink { throw IOException("rename failed") }
            write(sink, whole)
            assertTrue(sink.check())
            assertTrue(runCatching { sink.commit() }.exceptionOrNull() is IOException)
            assertFalse(stage.exists())
            assertEquals(listOf(stage), released)
        }

    @Test
    fun anEmptyPinnedFileClosesWithoutAByte() =
        runTest {
            val empty = ByteArray(0)
            val plan = listOf("dir" to listOf(pin("a", a1), pin("empty", empty), pin("b", a1)))
            val sink = ModelBundleSink(stage, plan, adopt = { adopted += it })
            write(sink, ModelArchive.stream(plan) { _, file -> (if (file.bytes == 0L) empty else a1).inputStream() }.readBytes())
            assertTrue(sink.check())
        }

    private fun pin(
        name: String,
        bytes: ByteArray,
    ) = SttFile(name, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
