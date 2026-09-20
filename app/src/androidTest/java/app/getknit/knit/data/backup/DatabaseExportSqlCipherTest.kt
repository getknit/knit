package app.getknit.knit.data.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * The keyed half of the export, which the JVM suite cannot run: a real SQLCipher live file, attached to
 * the scratch connection under a *bound* passphrase (`ATTACH … KEY ?`), copied, and reopened through the
 * driver; plus the staged-wrap check — a passphrase written by [KeystoreSecret] under [DatabaseKey]'s
 * alias and file name reads back as [DatabaseKey]'s own. Throwaway file names and a literal passphrase:
 * nothing here touches `knit.db`, `db.key` or the live aliases' files.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseExportSqlCipherTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase = ByteArray(DatabaseKey.PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
    private lateinit var dir: File

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        dir = File(context.noBackupFilesDir, "export-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun exportsAnEncryptedLiveDatabaseAndTheCopyOpensUnderTheSamePassphrase() =
        runBlocking {
            val liveFile = File(dir, "live.db")
            val live = KnitDatabase.build(context, passphrase, liveFile.absolutePath)
            try {
                live.blobDao().insert(BlobEntity("h1", "image/jpeg", byteArrayOf(9, 8, 7)))
                live.messageDao().upsert(
                    MessageEntity(
                        id = "m1",
                        senderId = "bob",
                        conversationId = "bob",
                        body = "sealed at rest",
                        sentAt = 1L,
                        attachmentHash = "h1",
                    ),
                )
                live.forwardDao().insert(
                    ForwardEntity(
                        id = "f1",
                        recipientId = "bob",
                        groupId = null,
                        senderId = "bob",
                        type = "chat",
                        origin = 0,
                        signed = ByteArray(0),
                        sig = ByteArray(0),
                        sentAt = 1L,
                        receivedAt = 1L,
                        expiresAt = 1_000L,
                    ),
                )
                val dest = File(dir, "export.db")
                DatabaseExport(
                    buildSchema = { DatabaseExport.createSchema(KnitDatabase.build(context, passphrase, it.absolutePath)) },
                    openRaw = { SQLCipherDriver(passphrase, null, null).open(it.absolutePath) },
                ).export(liveFile, passphrase, dest)

                val copy = KnitDatabase.build(context, passphrase, dest.absolutePath)
                try {
                    assertArrayEquals(byteArrayOf(9, 8, 7), copy.blobDao().bytes("h1"))
                    assertEquals(
                        listOf("m1"),
                        copy.messageDao().searchBodies("sealed*", listOf("bob"), emptyList(), false, 10).map { it.id },
                    )
                    assertEquals(0, copy.forwardDao().count(0L))
                } finally {
                    copy.close()
                }
                assertFalse(File(dest.path + "-wal").exists())
                // Encrypted at rest: a plain SQLite file opens with its 16-byte magic; a SQLCipher file's first
                // 16 bytes are the salt, so the magic must be absent.
                val head = dest.inputStream().use { it.readNBytes(SQLITE_MAGIC.size) }
                assertFalse(head.contentEquals(SQLITE_MAGIC))
                // The live side kept its custody row: the export never wrote to it.
                assertEquals(1, live.forwardDao().count(0L))
            } finally {
                live.close()
            }
        }

    @Test
    fun aPassphraseStagedThroughKeystoreSecretReadsBackAsDatabaseKeysOwn() {
        val staging = File(dir, "staging").apply { mkdirs() }
        val staged = KeystoreSecret(context, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, staging)
        staged.store(passphrase)
        assertArrayEquals(passphrase, staged.load())
        // The same alias, the same layout, a different directory: exactly what RestoreApplier moves into filesDir.
        val asRead = KeystoreSecret(context, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, staging).load()
        assertArrayEquals(passphrase, asRead)
    }

    private companion object {
        val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()
    }
}
