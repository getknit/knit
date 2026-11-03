package app.getknit.knit.call

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/** The walkie transcript's model download (C-3.11-9): every file pinned, nothing half-written ever counts as installed. */
class SttModelInstallerTest {
    private val encoder = bytes(300_000, seed = 1)
    private val tokens = bytes(1_000, seed = 2)
    private val model =
        SttModel(
            id = "test-model",
            baseUrl = "https://example.org/m/",
            files = listOf(pin("encoder.ort", encoder), pin("tokens.txt", tokens)),
            arch = SttArch.TINY_STREAMING,
        )
    private val dir = File(Files.createTempDirectory("stt").toFile(), "models/test-model")
    private val installer = SttModelInstaller(model, dir)
    private val served = mutableMapOf("encoder.ort" to encoder, "tokens.txt" to tokens)
    private val opened = mutableListOf<String>()

    private fun source(file: SttFile): InputStream {
        opened += file.name
        return ByteArrayInputStream(served[file.name] ?: throw IOException("404 ${file.name}"))
    }

    @Test
    fun aModelIsInstalledFileByFileAndThenMarked() =
        runTest {
            assertFalse(installer.installed())
            val progress = mutableListOf<Long>()
            installer.install(::source) { done, total ->
                assertEquals(model.bytes, total)
                progress += done
            }
            assertTrue(installer.installed())
            assertArrayEquals(encoder, installer.pathOf(model.files[0]).readBytes())
            assertEquals(0L, progress.first())
            assertEquals(model.bytes, progress.last())
            assertEquals(progress.sorted(), progress)
            assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".part") })
        }

    @Test
    fun anInstalledModelIsNotFetchedAgain() =
        runTest {
            installer.install(::source)
            opened.clear()
            installer.install(::source)
            assertTrue(opened.isEmpty())
        }

    @Test
    fun aFileThatFailsItsHashIsRefusedAndNothingCounts() =
        runTest {
            served["tokens.txt"] = tokens.copyOf().also { it[10] = (it[10] + 1).toByte() }
            val refusal = runCatching { installer.install(::source) }.exceptionOrNull()
            assertTrue("$refusal", refusal is SttModelInstaller.Refused)
            assertTrue(refusal!!.message!!.contains("checksum"))
            assertFalse(installer.installed())
            assertFalse(installer.pathOf(model.files[1]).exists())
            assertFalse(File(dir, "tokens.txt.part").exists())
        }

    @Test
    fun aFileLargerThanItsPinIsCutOffThere() =
        runTest {
            served["tokens.txt"] = tokens + ByteArray(10_000_000)
            val refusal = runCatching { installer.install(::source) }.exceptionOrNull()
            assertTrue(refusal!!.message!!.contains("larger"))
            assertFalse(installer.installed())
        }

    @Test
    fun aFileThatComesShortIsRefused() =
        runTest {
            served["encoder.ort"] = encoder.copyOf(encoder.size - 1)
            val refusal = runCatching { installer.install(::source) }.exceptionOrNull()
            assertTrue(refusal!!.message!!.contains("short"))
            assertFalse(installer.installed())
        }

    @Test
    fun anInstallCutShortResumesWithTheFilesAlreadyIn() =
        runTest {
            served.remove("tokens.txt")
            assertTrue(runCatching { installer.install(::source) }.exceptionOrNull() is IOException)
            assertFalse(installer.installed())
            assertTrue(installer.pathOf(model.files[0]).exists())
            served["tokens.txt"] = tokens
            opened.clear()
            installer.install(::source)
            assertTrue(installer.installed())
            // The encoder was in and checked: only the missing file is fetched.
            assertEquals(listOf("tokens.txt"), opened)
        }

    @Test
    fun aCancelledInstallLeavesNoPartBehind() =
        runTest {
            lateinit var job: Job
            val slow =
                object : InputStream() {
                    var n = 0

                    override fun read(): Int = if (++n > 100_000) -1 else 7

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (n > 70_000) job.cancel()
                        return super.read(b, off, minOf(len, 1_000))
                    }
                }
            job = launch { installer.install({ slow }) }
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(File(dir, "encoder.ort.part").exists())
            assertFalse(installer.installed())
        }

    @Test
    fun aDeleteTakesTheModelAway() =
        runTest {
            installer.install(::source)
            installer.delete()
            assertFalse(installer.installed())
            assertFalse(dir.exists())
        }

    // ---- adopt: a direct transfer's staged files (ADR 2026-11.ejn8) ----

    private val stage = Files.createTempDirectory("stage").toFile()

    @After
    fun cleanUp() {
        stage.deleteRecursively()
    }

    private fun staged(
        name: String,
        bytes: ByteArray,
    ) = File(stage, name).also { it.writeBytes(bytes) }

    @Test
    fun stagedFilesAreRenamedIntoPlaceAndMarked() =
        runTest {
            staged("encoder.ort", encoder)
            staged("tokens.txt", tokens)
            assertTrue(installer.adopt { File(stage, it.name) })
            assertTrue(installer.installed())
            assertArrayEquals(tokens, installer.pathOf(model.files[1]).readBytes())
            // Renamed, not copied: the stage is empty.
            assertTrue(stage.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun aStagedFileThatFailsItsPinsIsRefusedAndNothingCounts() =
        runTest {
            staged("encoder.ort", encoder)
            staged("tokens.txt", tokens.copyOf().also { it[3] = (it[3] + 1).toByte() })
            val refusal = runCatching { installer.adopt { File(stage, it.name) } }.exceptionOrNull()
            assertTrue("$refusal", refusal is SttModelInstaller.Refused)
            assertTrue(refusal!!.message!!.contains("checksum"))
            assertFalse(installer.installed())
            assertFalse(installer.pathOf(model.files[1]).exists())
        }

    @Test
    fun aMissingOrShortStagedFileIsRefused() =
        runTest {
            staged("encoder.ort", encoder.copyOf(encoder.size - 1))
            val short = runCatching { installer.adopt { File(stage, it.name) } }.exceptionOrNull()
            assertTrue(short!!.message!!.contains("short"))
            staged("encoder.ort", encoder)
            val missing = runCatching { installer.adopt { File(stage, it.name) } }.exceptionOrNull()
            assertTrue("$missing", missing is SttModelInstaller.Refused)
            assertFalse(installer.installed())
        }

    @Test
    fun anInstalledModelAdoptsNothingAndLeavesTheStage() =
        runTest {
            installer.install(::source)
            staged("encoder.ort", encoder)
            assertFalse(installer.adopt { File(stage, it.name) })
            assertTrue(File(stage, "encoder.ort").exists())
            assertTrue(installer.installed())
        }

    @Test
    fun aModelIsFetchedOverHttpsByPlainNames() {
        assertThrows(IllegalArgumentException::class.java) { SttModel("x", "http://example.org/m/", model.files, SttArch.TINY_STREAMING) }
        assertThrows(IllegalArgumentException::class.java) {
            SttModel("x", "https://example.org/m/", listOf(SttFile("../x", 1, "00")), SttArch.TINY_STREAMING)
        }
        assertEquals("https://example.org/m/tokens.txt", model.urlOf(model.files[1]))
    }

    @Test
    fun theShippedPinsAreWellFormed() {
        val shipped = SttModel.MOONSHINE_SMALL_STREAMING_EN
        assertTrue(shipped.baseUrl.startsWith("https://getknit.app/"))
        assertTrue(shipped.baseUrl.contains(shipped.id))
        assertTrue(shipped.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.bytes > 0 })
        // Every file a streaming model loads from (the runtime's catalog), and the licence.
        assertEquals(shipped.id, STREAMING_FILES + "LICENSE", shipped.files.map { it.name })
        assertEquals(SttArch.SMALL_STREAMING, shipped.arch)
    }

    private fun pin(
        name: String,
        bytes: ByteArray,
    ) = SttFile(name, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private companion object {
        val STREAMING_FILES =
            listOf(
                "adapter.ort",
                "cross_kv.ort",
                "decoder_kv.ort",
                "encoder.ort",
                "frontend.model.ort",
                "frontend.weights.ort",
                "streaming_config.json",
                "tokenizer.bin",
            )
    }
}
