package app.getknit.knit.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.AndroidDeviceIdSource
import app.getknit.knit.identity.DeviceIdSource
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.notifications.MessageNotifier
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.RouteInbox
import app.getknit.knit.ui.share.ShareInbox
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create {
            androidContext().preferencesDataStoreFile("knit_settings")
        }
    }
    single { SettingsStore(get()) }
    // Stable per-device id (ANDROID_ID) — seeds the soft block-continuity DeviceTag, not the nodeId.
    single<DeviceIdSource> { AndroidDeviceIdSource(androidContext()) }
    // E2E identity keypair, wrapped under a hardware AndroidKeyStore key in filesDir (outside the DB).
    single { IdentityKeyStore(KeystoreSecret(androidContext(), "knit_identity_key", "identity.key")) }
    // nodeId is derived from the keypair's public bundle; the device id only feeds the block tag.
    single { Identity(get(), get()) }
    single { AvatarStore(androidContext(), get()) }
    single { AttachmentStore(androidContext(), get()) }
    single { GallerySaver(androidContext()) }
    single<Notifier> { MessageNotifier(androidContext()) }
    // Single-shot handoff for content arriving via the system share sheet (ACTION_SEND).
    single { ShareInbox() }
    // Single-shot handoff for a notification-tap deep-link route (drained by KnitApp).
    single { RouteInbox() }

    single { DatabaseKey(androidContext()) }
    single { KnitDatabase.build(androidContext(), get<DatabaseKey>().getOrCreate()) }
    single { get<KnitDatabase>().messageDao() }
    single { get<KnitDatabase>().peerDao() }
    single { get<KnitDatabase>().reactionDao() }
    single { get<KnitDatabase>().blobDao() }
    single { get<KnitDatabase>().groupDao() }
    single { get<KnitDatabase>().blobVerdictDao() }
    single { get<KnitDatabase>().forwardDao() }
    single { MessageRepository(get()) }
    single { PeerRepository(get()) }
    single { ReactionRepository(get()) }
    // BlobRepository: blobDao, messageDao, peerDao, settings, blobVerdictDao, imageModerator, groupDao, forwardDao.
    single { BlobRepository(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { GroupRepository(get(), get(), get()) }
    // Store-and-forward custody for DMs, backed by the encrypted forward_store table. Takes the shared
    // StoreDigest (from meshModule) so every carry-store mutation keeps the cue-plane content digest in sync.
    single<ForwardStore> { ForwardRepository(get(), get()) }
}
