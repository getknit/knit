package app.getknit.knit.data.crypto

import android.content.Context
import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.KnitMigrations
import app.getknit.knit.data.crypto.SqlCipherKey.Form
import app.getknit.knit.data.peer.PeerEntity
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * [SqlCipherKey] against real SQLCipher (ADR 2026-09.uzkm): a database an older build keyed with the
 * passphrase through the KDF is moved onto the raw key by [KnitDatabase.build] with its rows intact, after
 * which only the raw key opens it; a new database is born on the raw key; a file neither key opens is never
 * touched. The JVM half — which door [SqlCipherKey.upgrade] takes — is `SqlCipherKeyTest`.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherRawKeyTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase = ByteArray(DatabaseKey.PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
    private val file: File get() = context.getDatabasePath(DB_NAME)

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() = deleteDatabaseFiles()

    @Test
    fun aDatabaseOnThePassphraseKeyIsRekeyedWithItsRowsAndThenOpensOnlyUnderTheRawKey() =
        runBlocking {
            // What every build before the ADR wrote: Room over the driver, keyed with the passphrase itself.
            val legacy =
                Room
                    .databaseBuilder(context, KnitDatabase::class.java, DB_NAME)
                    .setDriver(SQLCipherDriver(passphrase.copyOf(), null, null))
                    .addMigrations(*KnitMigrations.ALL)
                    .build()
            try {
                legacy.peerDao().upsert(PEER)
            } finally {
                legacy.close()
            }
            assertTrue(opens(passphrase))
            assertFalse(opens(SqlCipherKey.raw(passphrase)))

            val upgraded = KnitDatabase.build(context, passphrase, DB_NAME)
            try {
                assertEquals(PEER.name, upgraded.peerDao().findByNodeId(PEER.nodeId)?.name)
            } finally {
                upgraded.close()
            }
            assertTrue(opens(SqlCipherKey.raw(passphrase)))
            assertFalse(opens(passphrase))
            // The second start finds it already moved.
            assertEquals(Form.RAW, SqlCipherKey.upgrade(file, passphrase))
        }

    @Test
    fun aNewDatabaseIsBornOnTheRawKey() =
        runBlocking {
            val db = KnitDatabase.build(context, passphrase, DB_NAME)
            try {
                db.peerDao().upsert(PEER)
            } finally {
                db.close()
            }
            assertTrue(opens(SqlCipherKey.raw(passphrase)))
            assertFalse(opens(passphrase))
        }

    @Test
    fun aFileNeitherKeyOpensIsLeftExactlyAsFound() =
        runBlocking {
            val other = ByteArray(DatabaseKey.PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            // Room opens lazily, so the file exists only once something has used it.
            val db = KnitDatabase.build(context, other, DB_NAME)
            try {
                db.peerDao().upsert(PEER)
            } finally {
                db.close()
            }
            val before = file.readBytes()

            assertEquals(Form.UNREADABLE, SqlCipherKey.upgrade(file, passphrase))
            assertArrayEquals(before, file.readBytes())
            assertTrue(opens(SqlCipherKey.raw(other)))
        }

    /** Whether [key], passed to SQLCipher as is, reads the file — a wrong key throws inside the open. */
    private fun opens(key: ByteArray): Boolean = runCatching { SQLCipherDriver(key, null, null).open(file.absolutePath).close() }.isSuccess

    private fun deleteDatabaseFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    private companion object {
        const val DB_NAME = "sqlcipher-raw-key-test.db"
        val PEER = PeerEntity(nodeId = "peer-1", name = "Raw Key Peer", updatedAt = 1_000L)
    }
}
