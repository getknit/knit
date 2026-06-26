package app.getknit.knit.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.AndroidDeviceIdSource
import app.getknit.knit.identity.DeviceIdSource
import app.getknit.knit.identity.Identity
import app.getknit.knit.notifications.MessageNotifier
import app.getknit.knit.notifications.Notifier
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create {
            androidContext().preferencesDataStoreFile("knit_settings")
        }
    }
    single<DeviceIdSource> { AndroidDeviceIdSource(androidContext()) }
    single { SettingsStore(get(), get()) }
    single { Identity(get()) }
    single { AvatarStore(androidContext()) }
    single { AttachmentStore(androidContext()) }
    single { GallerySaver(androidContext()) }
    single<Notifier> { MessageNotifier(androidContext()) }

    single { DatabaseKey(androidContext()) }
    single { KnitDatabase.build(androidContext(), get<DatabaseKey>().getOrCreate()) }
    single { get<KnitDatabase>().messageDao() }
    single { get<KnitDatabase>().peerDao() }
    single { get<KnitDatabase>().reactionDao() }
    single { MessageRepository(get()) }
    single { PeerRepository(get()) }
    single { ReactionRepository(get()) }
}
