package app.getknit.knit.data.backup

import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The pre-Koin apply on plain directories: what lands where, what a crash mid-way leaves, what is refused. */
class RestoreApplierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File
    private lateinit var targets: RestoreApplier.Targets

    private fun layout() {
        staging = tmp.newFolder("no_backup", RestoreApplier.STAGING_DIR)
        val databases = tmp.newFolder("databases")
        val files = tmp.newFolder("files")
        targets =
            RestoreApplier.Targets(
                database = File(databases, "knit.db"),
                identity = File(files, IdentityKeyStore.FILE_NAME),
                databaseKey = File(files, DatabaseKey.KEY_FILE),
                settings = File(files, "datastore/knit_settings.preferences_pb"),
            )
    }

    private fun stage(schema: Int = 14) {
        File(staging, BackupFormat.ENTRY_DATABASE).writeText("new db")
        File(staging, IdentityKeyStore.FILE_NAME).writeText("new identity")
        File(staging, DatabaseKey.KEY_FILE).writeText("new key")
        File(staging, "knit_settings.preferences_pb").writeText("new prefs")
        File(staging, RestoreApplier.MANIFEST).writeText("$schema\n")
        File(staging, RestoreApplier.READY).writeText("")
    }

    @Test
    fun movesEveryStagedFileIntoPlaceAndRetiresTheStaging() {
        layout()
        targets.database.writeText("old db")
        File(targets.database.path + "-wal").writeText("stale wal")
        File(targets.database.path + "-shm").writeText("stale shm")
        targets.identity.writeText("old identity")
        stage()
        assertTrue(RestoreApplier.apply(staging, targets, 14))
        assertEquals("new db", targets.database.readText())
        assertEquals("new identity", targets.identity.readText())
        assertEquals("new key", targets.databaseKey.readText())
        assertEquals("new prefs", targets.settings.readText())
        assertFalse(File(targets.database.path + "-wal").exists())
        assertFalse(File(targets.database.path + "-shm").exists())
        assertFalse(staging.exists())
    }

    @Test
    fun nothingHappensWithoutTheReadyMarker() {
        layout()
        stage()
        File(staging, RestoreApplier.READY).delete()
        targets.database.writeText("old db")
        assertFalse(RestoreApplier.apply(staging, targets, 14))
        assertEquals("old db", targets.database.readText())
        assertTrue(File(staging, BackupFormat.ENTRY_DATABASE).exists())
    }

    @Test
    fun aCrashBetweenMovesResumesOnTheNextStart() {
        layout()
        stage()
        // As if the first attempt died after the database landed: the staged copy is gone, READY remains.
        targets.database.parentFile!!.mkdirs()
        File(staging, BackupFormat.ENTRY_DATABASE).renameTo(targets.database)
        assertTrue(RestoreApplier.apply(staging, targets, 14))
        assertEquals("new db", targets.database.readText())
        assertEquals("new identity", targets.identity.readText())
        assertEquals("new prefs", targets.settings.readText())
        assertFalse(staging.exists())
    }

    @Test
    fun aNewerSchemaThanThisBuildIsDiscardedNotInstalled() {
        layout()
        targets.database.writeText("old db")
        stage(schema = 15)
        assertFalse(RestoreApplier.apply(staging, targets, 14))
        assertEquals("old db", targets.database.readText())
        assertFalse(staging.exists())
    }

    @Test
    fun anOlderSchemaIsInstalledForRoomToMigrate() {
        layout()
        stage(schema = 13)
        assertTrue(RestoreApplier.apply(staging, targets, 14))
        assertEquals("new db", targets.database.readText())
    }
}
