package app.getknit.knit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Base class for JVM Room DAO tests that execute the **real SQL** (finding #5 — the eviction/orphan/GC queries
 * were previously verified only against hand-mirrored fakes).
 *
 * It builds [KnitDatabase] with [Room.inMemoryDatabaseBuilder], which — unlike the production
 * [KnitDatabase.build] — installs **no** SQLCipher `openHelperFactory`, so it runs on Robolectric's framework
 * SQLite: no passphrase, no `System.loadLibrary("sqlcipher")`, no encryption. The eviction/GC SQL runs
 * byte-identically; SQLCipher only encrypts at rest. Runs under Robolectric (SDK pinned in
 * `app/src/test/resources/robolectric.properties`). Call the `suspend` DAO methods inside `runTest { }`.
 */
@RunWith(AndroidJUnit4::class)
abstract class RoomDbTest {
    protected lateinit var db: KnitDatabase

    @Before
    fun openDb() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDb() {
        db.close()
    }
}
