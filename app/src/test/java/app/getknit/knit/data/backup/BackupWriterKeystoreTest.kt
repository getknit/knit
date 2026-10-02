package app.getknit.knit.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.FakeKeystoreCipher
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.ProviderException

/**
 * A backup reads the secrets the live app already holds, beside the open database, so a Keystore refusal must fail
 * the backup as something worth another try — and never take the old `DatabaseKey.getOrCreate()` path, which on a
 * refusal deleted the database the mesh had open and minted a second passphrase (ADR 2026-10.47rw). Every case here
 * fails before the export, so no SQLCipher is needed.
 */
@RunWith(AndroidJUnit4::class)
class BackupWriterKeystoreTest {
    /** Robolectric's StorageManager reports nothing allocatable, which would end every case at NO_SPACE. */
    private val storage = mockk<StorageManager>(relaxed = true) { every { getAllocatableBytes(any()) } returns Long.MAX_VALUE }
    private val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getSystemService(name: String): Any? =
                if (name ==
                    Context.STORAGE_SERVICE
                ) {
                    storage
                } else {
                    super.getSystemService(name)
                }
        }
    private val dbCipher = FakeKeystoreCipher()
    private val identityCipher = FakeKeystoreCipher()
    private val database = context.getDatabasePath(DatabaseKey.DB_NAME)
    private val databaseKey =
        DatabaseKey(context, KeystoreSecret(context, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, cipher = dbCipher, sleep = {}))

    private fun identitySecret() =
        KeystoreSecret(context, IdentityKeyStore.KEYSTORE_ALIAS, IdentityKeyStore.FILE_NAME, cipher = identityCipher, sleep = {})

    private val identity = Identity(IdentityKeyStore(identitySecret())) { "device" }
    private val writer = BackupWriter(context, identity, identitySecret(), databaseKey, mockk<SettingsStore>(), mockk())

    @After
    fun tearDown() {
        File(context.filesDir, DatabaseKey.KEY_FILE).delete()
        File(context.filesDir, IdentityKeyStore.FILE_NAME).delete()
        database.delete()
    }

    private fun refusal() = InvalidKeyException("Keystore operation failed", ProviderException("KM_ERROR_UNKNOWN_ERROR"))

    private suspend fun writeFailure(): IOException =
        runCatching { writer.write(ByteArrayOutputStream(), BackupKeys.generate()) }.exceptionOrNull() as IOException

    private fun problemOf(failure: IOException): BackupProblem? = (failure as? BackupException)?.problem

    /** A live phone: a passphrase, a database file beside it, an identity. */
    private suspend fun livePhone() {
        databaseKey.getOrCreate()
        database.parentFile!!.mkdirs()
        database.writeText("live")
        identity.nodeId()
    }

    @Test
    fun aRefusedPassphraseFailsTheBackupAndLeavesTheLiveDatabaseAlone() =
        runTest {
            livePhone()
            repeat(3) { dbCipher.openScript += refusal() }
            assertEquals(BackupProblem.KEYSTORE_UNAVAILABLE, problemOf(writeFailure()))
            assertEquals("live", database.readText())
            assertEquals(1, dbCipher.generated)
        }

    @Test
    fun aRefusedIdentityFailsTheBackup() =
        runTest {
            databaseKey.getOrCreate()
            IdentityKeyStore(identitySecret()).keys() // the phone has an identity; this writer's Identity has not read it yet
            repeat(3) { identityCipher.openScript += refusal() }
            assertEquals(BackupProblem.KEYSTORE_UNAVAILABLE, problemOf(writeFailure()))
            assertEquals("no second identity is minted", 1, identityCipher.generated)
        }

    @Test
    fun aRefusedIdentityFileFailsTheBackup() =
        runTest {
            livePhone() // Identity has the node id cached; the writer's own read of the file is refused
            repeat(3) { identityCipher.openScript += refusal() }
            assertEquals(BackupProblem.KEYSTORE_UNAVAILABLE, problemOf(writeFailure()))
        }

    @Test
    fun aPhoneWithNoPassphraseHasNothingToBackUpAndMintsNone() =
        runTest {
            val failure = writeFailure()
            assertEquals(null, problemOf(failure))
            assertFalse(File(context.filesDir, DatabaseKey.KEY_FILE).exists())
            assertTrue(failure.message!!.contains("database passphrase"))
        }
}
