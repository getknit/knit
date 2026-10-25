package app.getknit.knit.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.crypto.SqlCipherKey
import app.getknit.knit.data.crypto.Unwrapped
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.RatchetSessionEntity
import app.getknit.knit.data.settings.SettingsKeys
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A backup, end to end on a device, with nothing faked: the real [BackupWriter] seals a SQLCipher database,
 * the Keystore-wrapped identity and passphrase and the settings; the real [RestoreStager] opens it, re-wraps
 * the secrets under the live aliases and opens the database under its passphrase with the driver
 * ([BackupWriter.openSqlCipher]); and [RestoreApplier] moves the staged files into place. What comes out the
 * other end must be the same phone: the same node id from the identity file, the same passphrase from the key
 * file, the same message from the database. The JVM half (every refusal) is `RestoreStagerTest`.
 *
 * Every directory is a sandbox ([SandboxContext]); the installed app's files are never read or written.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val root =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "backup-roundtrip-${System.nanoTime()}")
    private val context = SandboxContext(InstrumentationRegistry.getInstrumentation().targetContext, root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        // A seeded build's own start mints an identity under the live alias in this same process, replacing the key
        // this test's read-back needs; run it from an unseeded build.
        assumeFalse("a seeded build mints its own identity under the same Keystore alias", BuildConfig.SEED_DEMO)
        System.loadLibrary("sqlcipher")
        // An unseeded start mints the database passphrase under the live alias too (a cleared package has no key), and
        // a mint racing this test's generates a second key over the one its read-back needs. Settle the app's own first:
        // its getOrCreate is synchronized, so this waits out a mint in flight, and every later store finds the key.
        GlobalContext.get().get<DatabaseKey>().getOrCreate()
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun aBackupRestoresTheSamePhone() =
        runBlocking {
            // The phone being backed up: a live keyed database with one message, an identity, a name.
            val databaseKey = DatabaseKey(context)
            val passphrase = databaseKey.getOrCreate()
            val live = KnitDatabase.build(context, passphrase, context.getDatabasePath(KnitDatabase.DB_NAME).absolutePath)
            live.messageDao().upsert(MessageEntity(id = "m1", senderId = "bob", conversationId = "bob", body = "still here", sentAt = 1L))
            val identitySecret = BackupWriter.identitySecret(context)
            val identity = Identity(IdentityKeyStore(identitySecret)) { "sandbox-device" }
            val nodeId = identity.nodeId()
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }
            val settings = SettingsStore(dataStore).also { it.setDisplayName("Ada") }

            val key = BackupKeys.generate()
            val archive = ByteArrayOutputStream()
            val written =
                try {
                    BackupWriter(context, identity, identitySecret, databaseKey, settings, dataStore).write(archive, key)
                } finally {
                    live.close()
                }
            assertEquals(nodeId, written.nodeId)
            assertEquals("Ada", written.displayName)
            assertFalse("the scratch copy is gone", File(context.noBackupFilesDir, BackupWriter.SCRATCH_DIR).exists())

            val stager = RestoreStager(context, BackupWriter::openSqlCipher)
            val staged = stager.stage(ByteArrayInputStream(archive.toByteArray()), key)
            assertEquals(nodeId, staged.nodeId)
            assertTrue(stager.isStaged())

            // Install into a second, empty phone's layout.
            val restored = File(root, "restored")
            val targets =
                RestoreApplier.Targets(
                    database = File(restored, "databases/${KnitDatabase.DB_NAME}"),
                    identity = File(restored, "files/${IdentityKeyStore.FILE_NAME}"),
                    databaseKey = File(restored, "files/${DatabaseKey.KEY_FILE}"),
                    settings = File(restored, "files/datastore/${SettingsKeys.DATASTORE_NAME}.preferences_pb"),
                )
            assertTrue(RestoreApplier.apply(RestoreApplier.stagingDir(context), targets, KnitDatabase.SCHEMA_VERSION))
            assertFalse(stager.isStaged())

            // The same passphrase and the same identity come back through the live aliases' wraps...
            val filesDir = targets.identity.parentFile!!
            val restoredPassphrase = KeystoreSecret(context, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, filesDir).read()
            assertArrayEquals(passphrase, (restoredPassphrase as Unwrapped.Present).bytes)
            val restoredIdentity = KeystoreSecret(context, IdentityKeyStore.KEYSTORE_ALIAS, IdentityKeyStore.FILE_NAME, filesDir).read()
            assertEquals(nodeId, IdentityKeyStore.nodeIdOf((restoredIdentity as Unwrapped.Present).bytes))
            // ...and the database opens under it with the message in it.
            SqlCipherKey.open(targets.database, passphrase).use { c ->
                c.prepare("SELECT body FROM messages WHERE id = 'm1'").use {
                    assertTrue(it.step())
                    assertEquals("still here", it.getText(0))
                }
            }
            assertTrue(targets.settings.length() > 0)
        }

    /**
     * The restore as a second phone lives it: that phone already has its own identity, passphrase, database (with a
     * stale write-ahead log beside it) and settings; the backup is staged and applied through [RestoreApplier.applyPending]
     * at that phone's real paths, and then the app's own readers — [DatabaseKey.getOrCreate], [IdentityKeyStore], Room
     * and [SettingsStore], each built fresh as the relaunched process builds them — must find the first phone. The
     * carried tables come back, custody and the ratchet come back empty, the search index is rebuilt, and the settings
     * carry the person's choices but not the phone-bound keys, with the two a restore sets.
     */
    @Test
    fun aRestoreReplacesAnotherPhoneAndTheAppsOwnReadersFindTheFirst() =
        runBlocking {
            val first = SandboxContext(context, File(root, "first"))
            val second = SandboxContext(context, File(root, "second"))

            // The first phone: the one the backup is made of.
            val passphrase = DatabaseKey(first).getOrCreate()
            val live = KnitDatabase.build(first, passphrase, first.getDatabasePath(KnitDatabase.DB_NAME).absolutePath)
            live.messageDao().upsert(MessageEntity(id = "m1", senderId = BOB, conversationId = BOB, body = "carried across", sentAt = 1L))
            live.blobDao().insert(BlobEntity("h1", "image/jpeg", byteArrayOf(9, 8, 7)))
            live.peerDao().upsert(PeerEntity(nodeId = BOB, name = "Bob", verified = true))
            live.groupDao().upsert(GroupEntity(groupId = "g1", name = "Climbing", members = "[\"$BOB\"]", createdBy = BOB, createdAt = 1L))
            live.forwardDao().insert(forwardRow())
            live.ratchetDao().upsertSession(ratchetRow())
            val firstSecret = BackupWriter.identitySecret(first)
            val firstIdentity = Identity(IdentityKeyStore(firstSecret)) { "first-device" }
            val nodeId = firstIdentity.nodeId()
            val firstStore = PreferenceDataStoreFactory.create(scope = scope) { File(root, "first.preferences_pb") }
            SettingsStore(firstStore).apply {
                setDisplayName("Ada")
                setStatus("on the wall")
                setMeshPausedUntil(Long.MAX_VALUE)
            }
            val key = BackupKeys.generate()
            val archive = ByteArrayOutputStream()
            try {
                BackupWriter(
                    first,
                    firstIdentity,
                    firstSecret,
                    DatabaseKey(first),
                    SettingsStore(firstStore),
                    firstStore,
                ).write(archive, key)
            } finally {
                live.close()
            }

            // The second phone, already somebody: its own identity, passphrase, database and settings.
            val otherPassphrase = DatabaseKey(second).getOrCreate()
            assertFalse(otherPassphrase.contentEquals(passphrase))
            val otherNodeId = Identity(IdentityKeyStore(BackupWriter.identitySecret(second))) { "second-device" }.nodeId()
            assertNotEquals(nodeId, otherNodeId)
            val secondDb = second.getDatabasePath(KnitDatabase.DB_NAME)
            KnitDatabase.build(second, otherPassphrase, secondDb.absolutePath).apply {
                messageDao().upsert(
                    MessageEntity(id = "m0", senderId = "carol", conversationId = "carol", body = "second phone's own", sentAt = 1L),
                )
                close()
            }
            File(secondDb.path + "-wal").writeBytes(ByteArray(4096) { 0x5a })
            val secondPrefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val secondPrefs = second.preferencesDataStoreFile(SettingsKeys.DATASTORE_NAME)
            SettingsStore(PreferenceDataStoreFactory.create(scope = secondPrefsScope) { secondPrefs }).setDisplayName("Grace")
            // The relaunch: nothing holds the old settings open when the applier runs.
            secondPrefsScope.coroutineContext.job.cancelAndJoin()

            val stager = RestoreStager(second, BackupWriter::openSqlCipher)
            assertEquals(nodeId, stager.stage(ByteArrayInputStream(archive.toByteArray()), key).nodeId)
            assertTrue(RestoreApplier.applyPending(second))
            assertFalse(stager.isStaged())
            assertFalse("the stale log is not replayed onto the restored file", File(secondDb.path + "-wal").exists())
            assertFalse("a second apply has nothing to do", RestoreApplier.applyPending(second))

            // The relaunched process: every reader built fresh, at the second phone's own paths.
            val restoredPassphrase = DatabaseKey(second).getOrCreate()
            assertArrayEquals(passphrase, restoredPassphrase)
            assertEquals(nodeId, Identity(IdentityKeyStore(BackupWriter.identitySecret(second))) { "second-device" }.nodeId())

            val restored = KnitDatabase.build(second, restoredPassphrase, secondDb.absolutePath)
            try {
                assertEquals("carried across", restored.messageDao().findById("m1")?.body)
                assertNull("the second phone's own history is gone", restored.messageDao().findById("m0"))
                assertEquals(
                    listOf("m1"),
                    restored.messageDao().searchBodies("carried*", listOf(BOB), emptyList(), false, 10).map { it.id },
                )
                assertArrayEquals(byteArrayOf(9, 8, 7), restored.blobDao().bytes("h1"))
                assertTrue(restored.peerDao().findByNodeId(BOB)!!.verified)
                assertEquals("Climbing", restored.groupDao().findById("g1")?.name)
                assertEquals("custody is never carried", 0, restored.forwardDao().count(0L))
                assertEquals("the ratchet is never carried", emptyList<String>(), restored.ratchetDao().sessionPeerIds())
            } finally {
                restored.close()
            }

            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { secondPrefs })
            assertEquals("Ada", settings.displayName.first())
            assertEquals("on the wall", settings.status.first())
            assertNull("a pause is about the phone it was set on", settings.meshPausedUntil.first())
            assertTrue(settings.onboardingSeen.first())
            assertTrue(settings.restorePending.first())
        }

    private fun forwardRow() =
        ForwardEntity(
            id = "f1",
            recipientId = BOB,
            groupId = null,
            senderId = BOB,
            type = "chat",
            origin = 0,
            signed = ByteArray(0),
            sig = ByteArray(0),
            sentAt = 1L,
            receivedAt = 1L,
            expiresAt = Long.MAX_VALUE,
        )

    private fun ratchetRow() =
        RatchetSessionEntity(
            peerId = BOB,
            confirmed = true,
            weAreInitiator = true,
            root = ByteArray(32),
            prevRoot = null,
            prevRootWeAreInitiator = false,
            prevRootExpiresAt = 0L,
            establishedAt = 1L,
            initEphPub = null,
            initPkid = 0,
            peerInitEphPub = null,
            peerBasePub = null,
            peerBaseEpoch = 0,
            sendEpoch = 0,
            sendEpochPub = null,
            sendChainKey = null,
            sendCount = 0,
            sendEpochStartedAt = 1L,
            sendEpochBaseEpoch = 0,
            sendEpochExport = null,
            highestPeAcked = 0,
            lastResetSentAt = 0L,
            updatedAt = 1L,
        )

    private companion object {
        const val BOB = "bob"
    }
}
