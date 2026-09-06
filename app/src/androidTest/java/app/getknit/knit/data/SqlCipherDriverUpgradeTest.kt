package app.getknit.knit.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Just to keep [SqlCipherDriverUpgradeTest.buildDatabase]'s signature readable. */
private typealias Builder = RoomDatabase.Builder<KnitDatabase>

/**
 * The one test that opens a **real encrypted** database. Everything else runs on Robolectric's framework
 * SQLite via [RoomDbTest] — deliberately, per `.agents/context/testing.md` — because SQLCipher needs
 * `libsqlcipher.so`. This lives in `androidTest` for exactly that reason, and it is the only place the
 * SQLCipher integration itself is under test rather than assumed.
 *
 * **What it protects.** [KnitDatabase.build] installs SQLCipher through `setDriver(SQLCipherDriver)`. ADR 008
 * forbids a destructive fallback, so an already-installed user's `knit.db` must open through that driver with
 * its rows and its Room identity intact — a mismatched identity hash makes Room refuse the file rather than
 * migrate it, and there is no wipe-and-continue path to fall back on. Two cases, both starting from a
 * database SQLCipher wrote *before* Room ever saw it, materialised from the checked-in schema JSON:
 *
 * 1. An existing database already at the current version opens with no migration and no integrity complaint.
 * 2. A v1-era database walks every [KnitMigrations] entry under the driver, proving the migration chain runs
 *    on SQLCipher and not only on the framework engine the JVM migration suite uses.
 *
 * (The cross-seam case — a database written by the old `openHelperFactory(SupportOpenHelperFactory)` path and
 * reopened through the driver — was verified on the commit that made the switch, while Room 2.8 still offered
 * both. Room 3 deletes `openHelperFactory`, so it can no longer be expressed here.)
 *
 * It never touches the real `knit.db`: [DB_NAME] is a throwaway file deleted around every test, and the
 * passphrase is a fixed literal rather than [app.getknit.knit.data.crypto.DatabaseKey]'s Keystore-wrapped key.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherDriverUpgradeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase get() = PASSPHRASE.toByteArray()

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() = deleteDatabaseFiles()

    @Test
    fun anExistingCurrentVersionDatabaseOpensThroughTheDriver() =
        runBlocking {
            openRawConnection().use { connection ->
                connection.materialiseSchema(CURRENT_VERSION)
                connection.execSQL(
                    // `openToChat` is NOT NULL with no table-level default (v9 added it with one only on the
                    // ALTER), so a column-listed INSERT has to name it — as Room's own insert does.
                    "INSERT INTO peers (nodeId, name, status, verified, updatedAt, openToChat) " +
                        "VALUES ('${PEER.nodeId}', '${PEER.name}', '', 0, ${PEER.updatedAt}, 0)",
                )
            }

            // The assertion is that this open SUCCEEDS and runs no migration: a schema Room considered
            // different would throw ("Room cannot verify the data integrity") instead of handing one back,
            // and a version it thought was older would demand a migration that does not exist.
            val opened = buildDatabase { setDriver(SQLCipherDriver(passphrase, null, null)) }
            try {
                assertEquals(PEER.name, opened.peerDao().findByNodeId(PEER.nodeId)?.name)
                opened.messageDao().upsert(MESSAGE)
                val stored = opened.messageDao().observeById(MESSAGE.id).first()
                assertEquals(MESSAGE.body, stored?.body)
            } finally {
                opened.close()
            }

            openRawConnection().use { connection ->
                assertEquals(CURRENT_VERSION.toLong(), connection.readLong("PRAGMA user_version"))
                assertEquals(
                    exportedIdentityHash(CURRENT_VERSION),
                    connection.readText("SELECT identity_hash FROM room_master_table WHERE id = 42"),
                )
            }
        }

    @Test
    fun aV1DatabaseMigratesToCurrentUnderTheDriver() =
        runBlocking {
            openRawConnection().use { it.materialiseSchema(version = 1) }

            val migrated = buildDatabase { setDriver(SQLCipherDriver(passphrase, null, null)) }
            try {
                // A column added by MIGRATION_6_7, on a table that existed at v1: Room ran the chain over
                // the old file rather than recreating it (a recreate would also have dropped the row).
                migrated.messageDao().upsert(MESSAGE)
                val stored = migrated.messageDao().observeById(MESSAGE.id).first()
                assertEquals(MESSAGE.body, stored?.body)
                assertNull(stored?.arrivedAt)
            } finally {
                migrated.close()
            }

            openRawConnection().use { connection ->
                assertEquals(CURRENT_VERSION.toLong(), connection.readLong("PRAGMA user_version"))
                assertEquals(
                    exportedIdentityHash(CURRENT_VERSION),
                    connection.readText("SELECT identity_hash FROM room_master_table WHERE id = 42"),
                )
                // Tables the chain introduced: ratchet_sessions at v2 (MIGRATION_1_2), group_roots at v3,
                // message_receipts at v6. None of them exist in 1.json.
                for (table in MIGRATED_IN_TABLES) {
                    assertEquals(
                        table,
                        connection.readText("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'"),
                    )
                }
            }
        }

    private fun buildDatabase(engine: Builder.() -> Builder): KnitDatabase =
        Room
            .databaseBuilder(context, KnitDatabase::class.java, DB_NAME)
            .engine()
            .addMigrations(*KnitMigrations.ALL)
            .build()

    private fun openRawConnection(): SQLiteConnection =
        SQLCipherDriver(passphrase, null, null).open(context.getDatabasePath(DB_NAME).absolutePath)

    /**
     * Recreates [version]'s schema from its checked-in `app/schemas/…/<version>.json` — the same tables,
     * indices and `room_master_table` row Room's own exporter wrote — so Room opens it as a genuine
     * old database and migrates it. Mirrors what `MigrationTestHelper.createDatabase` does, except on a
     * SQLCipher connection, which that helper cannot give us.
     */
    private fun SQLiteConnection.materialiseSchema(version: Int) {
        val database = schemaJson(version).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            execSQL(entity.getString("createSql").replace(TABLE_NAME_PLACEHOLDER, table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME_PLACEHOLDER, table))
            }
        }
        val setupQueries = database.getJSONArray("setupQueries")
        for (i in 0 until setupQueries.length()) execSQL(setupQueries.getString(i))
        execSQL("PRAGMA user_version = $version")
    }

    private fun SQLiteConnection.readLong(sql: String): Long =
        prepare(sql).use {
            it.step()
            it.getLong(0)
        }

    private fun SQLiteConnection.readText(sql: String): String? = prepare(sql).use { if (it.step()) it.getText(0) else null }

    private fun exportedIdentityHash(version: Int): String = schemaJson(version).getJSONObject("database").getString("identityHash")

    /** The schemas ship as debug-variant assets (`app/build.gradle.kts`), so the app under test carries them. */
    private fun schemaJson(version: Int): JSONObject =
        JSONObject(
            context.assets
                .open("$SCHEMA_ASSET_DIR/$version.json")
                .bufferedReader()
                .use { it.readText() },
        )

    private fun deleteDatabaseFiles() {
        val database = context.getDatabasePath(DB_NAME)
        listOf(database, File("${database.path}-wal"), File("${database.path}-shm"), File("${database.path}-journal"))
            .forEach { it.delete() }
    }

    private companion object {
        const val DB_NAME = "sqlcipher-driver-upgrade-test.db"
        const val PASSPHRASE = "sqlcipher-driver-upgrade-test"
        const val SCHEMA_ASSET_DIR = "app.getknit.knit.data.KnitDatabase"
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"

        /** Bump alongside `KnitDatabase`'s `@Database(version = …)`; its retention is CLASS, so it can't be read. */
        const val CURRENT_VERSION = 10

        /** Tables the migration chain introduces after v1: none of these appear in `1.json`. */
        val MIGRATED_IN_TABLES = listOf("ratchet_sessions", "group_roots", "message_receipts")

        val MESSAGE =
            MessageEntity(
                id = "driver-upgrade-1",
                senderId = "sender",
                body = "written by the support factory",
                sentAt = 1_000L,
            )
        val PEER = PeerEntity(nodeId = "peer-1", name = "Upgrade Peer", updatedAt = 1_000L)
    }
}
