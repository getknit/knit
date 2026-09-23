package app.getknit.knit.data.backup

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The export against a **file-backed** live database on Robolectric's SQLite (no SQLCipher, no `KEY`
 * — the clause is parsed and ignored there; the keyed case is `DatabaseExportSqlCipherTest` in
 * `androidTest`). What it pins: every carried table arrives whole, the transient ones exist and are
 * empty, the FTS index over the copied bodies works, the copy is one file that Room opens at the current
 * version, and a write that lands mid-export neither breaks the copy nor is blocked by it.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File
    private lateinit var liveFile: File
    private lateinit var live: KnitDatabase

    @Before
    fun openLive() {
        dir = File(context.cacheDir, "export-${System.nanoTime()}").apply { mkdirs() }
        liveFile = File(dir, "live.db")
        live = Room.databaseBuilder(context, KnitDatabase::class.java, liveFile.absolutePath).allowMainThreadQueries().build()
    }

    @After
    fun closeLive() {
        live.close()
        dir.deleteRecursively()
    }

    private fun exporter(beforeCopy: suspend () -> Unit = {}) =
        DatabaseExport(
            buildSchema = { file ->
                DatabaseExport.createSchema(Room.databaseBuilder(context, KnitDatabase::class.java, file.absolutePath).build())
                beforeCopy()
            },
            openRaw = { AndroidSQLiteDriver().open(it.absolutePath) },
        )

    private suspend fun seed() {
        live.blobDao().insert(BlobEntity("h1", "image/jpeg", byteArrayOf(1, 2, 3, 4)))
        live.peerDao().upsert(PeerEntity(nodeId = "bob", verified = true, updatedAt = 5L))
        live.messageDao().upsert(
            MessageEntity(id = "m1", senderId = "bob", conversationId = "bob", body = "hello sailor", sentAt = 1L, attachmentHash = "h1"),
        )
        live.messageDao().upsert(MessageEntity(id = "m2", senderId = "bob", conversationId = "bob", body = "second wind", sentAt = 2L))
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
        live.savedFileDao().upsert("h1", "content://docs/document/report.pdf", 3L)
    }

    @Test
    fun carriesEveryCarriedTableAndLeavesTheTransientOnesEmpty() =
        runTest {
            seed()
            val dest = File(dir, "export.db")
            exporter().export(liveFile, "unused".encodeToByteArray(), dest)
            AndroidSQLiteDriver().open(dest.absolutePath).use { c ->
                assertEquals(2L, c.count("messages"))
                assertEquals(1L, c.count("blobs"))
                assertEquals(1L, c.count("peers"))
                for (table in BackupTables.TRANSIENT) assertEquals(table, 0L, c.count(table))
                for (table in BackupTables.DEVICE_LOCAL) assertEquals(table, 0L, c.count(table))
                assertEquals("ok", c.scalar("PRAGMA quick_check"))
                assertEquals(KnitDatabase.SCHEMA_VERSION.toString(), c.scalar("PRAGMA user_version"))
            }
            assertFalse(File(dest.path + "-wal").exists())
            assertFalse(File(dest.path + "-shm").exists())
            // The live side is untouched, custody included.
            assertEquals(1, live.forwardDao().count(0L))
        }

    @Test
    fun theCopyOpensThroughRoomWithItsBytesAndItsSearchIndex() =
        runTest {
            seed()
            val dest = File(dir, "export.db")
            exporter().export(liveFile, ByteArray(0), dest)
            val copy = Room.databaseBuilder(context, KnitDatabase::class.java, dest.absolutePath).allowMainThreadQueries().build()
            try {
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), copy.blobDao().bytes("h1"))
                assertTrue(copy.peerDao().findByNodeId("bob")!!.verified)
                val hits = copy.messageDao().searchBodies("sailor*", listOf("bob"), emptyList(), hideFlagged = false, limit = 10)
                assertEquals(listOf("m1"), hits.map { it.id })
                assertEquals(0, copy.forwardDao().count(0L))
                // The index stays live in the copy: a body written after the restore is searchable too.
                copy.messageDao().upsert(
                    MessageEntity(id = "m3", senderId = "bob", conversationId = "bob", body = "third time", sentAt = 3L),
                )
                assertEquals(listOf("m3"), copy.messageDao().searchBodies("third*", listOf("bob"), emptyList(), false, 10).map { it.id })
            } finally {
                copy.close()
            }
        }

    @Test
    fun aWriteLandingBeforeTheCopyIsCarriedAndOneDuringItIsNotBlocked() =
        runTest {
            seed()
            val dest = File(dir, "export.db")
            // A live write between the schema build and the copy: the copy reads a snapshot taken at the
            // first read, so it is in.
            exporter(beforeCopy = {
                live.messageDao().upsert(MessageEntity(id = "m9", senderId = "bob", conversationId = "bob", body = "late", sentAt = 9L))
            }).export(liveFile, ByteArray(0), dest)
            AndroidSQLiteDriver().open(dest.absolutePath).use { c ->
                assertEquals(3L, c.count("messages"))
                assertEquals("ok", c.scalar("PRAGMA quick_check"))
            }
            // And the live database is still writable afterwards — nothing was left attached or locked.
            live.messageDao().upsert(MessageEntity(id = "m10", senderId = "bob", conversationId = "bob", body = "after", sentAt = 10L))
            assertEquals(1, live.messageDao().searchBodies("after*", listOf("bob"), emptyList(), false, 10).size)
        }

    private fun SQLiteConnection.count(table: String): Long =
        prepare("SELECT COUNT(*) FROM $table").use {
            it.step()
            it.getLong(0)
        }

    private fun SQLiteConnection.scalar(sql: String): String =
        prepare(sql).use {
            it.step()
            it.getText(0)
        }
}
