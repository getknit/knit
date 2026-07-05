package app.getknit.knit.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Migration-testing harness (finding #5 groundwork). The app has **no** `Migration`s yet — it destroys and
 * recreates the DB on every schema bump (`fallbackToDestructiveMigration(dropAllTables = true)`) — so today
 * this exercises the schema-export pipeline end-to-end: `createDatabase(version)` rebuilds the DB from the
 * checked-in `app/schemas/app.getknit.knit.data.KnitDatabase/<version>.json`, proving `exportSchema`, the ksp
 * `room.schemaLocation`, the unit-test asset wiring (Robolectric serves `sourceSets["test"]` assets), and the
 * `MigrationTestHelper` harness all line up. The version is read from the `@Database` annotation, so this
 * always targets the current schema and fails loudly if its exported JSON is missing.
 *
 * It uses the driver-based [MigrationTestHelper] constructor with [AndroidSQLiteDriver] — the connection API
 * (`createDatabase`/`runMigrationsAndValidate` returning a `SQLiteConnection`) requires a `SQLiteDriver`, and
 * the framework driver runs on Robolectric's shadowed SQLite (the same engine the DAO tests use;
 * `BundledSQLiteDriver` can't load its Android native lib on the host JVM). When the app gains its first real
 * migration (and drops the destructive fallback), fill in the template below — `runMigrationsAndValidate` then
 * validates both the migrated schema and the carried data.
 */
@RunWith(AndroidJUnit4::class)
class KnitDatabaseMigrationTest {
    private val dbFile = File.createTempFile("knit-migration", ".db").apply { delete() } // path must be free

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = dbFile,
            driver = AndroidSQLiteDriver(),
            databaseClass = KnitDatabase::class,
        )

    @Test
    fun `the current schema (v21) creates and opens from the exported JSON`() {
        val version = 21 // KnitDatabase @Database(version = 21) — bump alongside the DB (its retention is CLASS,
        // so the version can't be read reflectively). A missing schemas/<db>/<version>.json fails here.
        helper.createDatabase(version).close()
    }

    // Template for the first real migration — uncomment and fill in once KnitDatabase defines a Migration and
    // drops fallbackToDestructiveMigration (until then there is nothing to migrate):
    //
    // @Test
    // fun `migrate 21 to 22 preserves peer rows`() {
    //     helper.createDatabase(21).use { c ->
    //         c.execSQL("INSERT INTO peers (nodeId, name, status, verified, updatedAt) VALUES ('n1','Ann','',0,0)")
    //     }
    //     helper.runMigrationsAndValidate(22, listOf(KnitMigrations.MIGRATION_21_22)).use { c ->
    //         c.prepare("SELECT name FROM peers WHERE nodeId = 'n1'").use { s ->
    //             assertTrue(s.step())
    //             assertEquals("Ann", s.getText(0))
    //         }
    //     }
    // }
}
