package app.getknit.knit.di

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room3.withWriteTransaction
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.badge.BadgeKeys
import app.getknit.knit.badge.PeerBadges
import app.getknit.knit.contacts.ContactCards
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.contacts.ContactRemover
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.crash.ProcessExitReasons
import app.getknit.knit.crash.crashStore
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.LabelAnchors
import app.getknit.knit.data.LabelFade
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.QuoteVerifier
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.backup.BackupWriter
import app.getknit.knit.data.backup.RestoreStager
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.crypto.AndroidDeviceKeystore
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreCipher
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.emoji.AndroidGlyphCheck
import app.getknit.knit.data.emoji.EmojiCatalogLoader
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.ratchet.GroupRootRepository
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.data.settings.BadgeStore
import app.getknit.knit.data.settings.CallPreferenceStore
import app.getknit.knit.data.settings.HomeStore
import app.getknit.knit.data.settings.IncomingCallStore
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.ReviewPromptStore
import app.getknit.knit.data.settings.SettingsKeys
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TicketKeyStore
import app.getknit.knit.demo.DemoComposer
import app.getknit.knit.device.DeviceImage
import app.getknit.knit.identity.AndroidDeviceIdSource
import app.getknit.knit.identity.DeviceIdSource
import app.getknit.knit.identity.Identity
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.linkpreview.OkHttpPreviewFetcher
import app.getknit.knit.linkpreview.PreviewFetcher
import app.getknit.knit.linkpreview.PreviewImage
import app.getknit.knit.location.AndroidLocationSource
import app.getknit.knit.location.LocationSource
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetStore
import app.getknit.knit.mesh.crypto.ratchet.RatchetStore
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.spool.CommonsStore
import app.getknit.knit.mesh.spool.FreeIssuers
import app.getknit.knit.mesh.spool.GroupRootStore
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.InternetGate
import app.getknit.knit.notifications.ConversationFaces
import app.getknit.knit.notifications.ConversationGate
import app.getknit.knit.notifications.ConversationShortcutSync
import app.getknit.knit.notifications.ConversationShortcuts
import app.getknit.knit.notifications.MessageNotifier
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.review.ReviewPrompter
import app.getknit.knit.ui.OfferedConversations
import app.getknit.knit.ui.RouteInbox
import app.getknit.knit.ui.addcontact.ContactCardInbox
import app.getknit.knit.ui.chat.quoteLabels
import app.getknit.knit.ui.relay.RelayInviteInbox
import app.getknit.knit.ui.review.ReviewPromptInbox
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.theme.AndroidNightMode
import app.getknit.knit.ui.theme.NightMode
import app.getknit.knit.ui.theme.ThemePreferences
import app.getknit.knit.ui.voice.VoicePlayer
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule =
    module {
        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.create {
                androidContext().preferencesDataStoreFile(SettingsKeys.DATASTORE_NAME)
            }
        }
        single { SettingsStore(get()) }
        // §3.14: the person's own call servers, beside the settings; read by the Settings row in every build.
        single { OwnIceStore(get()) }
        // C-3.3-7: which incoming calls this phone takes; read by the Settings row in every build, as the servers are.
        single { IncomingCallStore(get()) }
        // C-3.11-3: whether the chat always puts the mesh call first; read by the Settings row in every build.
        single { CallPreferenceStore(get()) }
        // The platform's per-app night mode (API 31+), the one UiModeManager user in the app.
        single<NightMode> { AndroidNightMode(androidContext()) }
        // Warmed at process start by KnitApplication so the first composition can read the Material You flag
        // synchronously, and the one place the stored light/dark choice reaches the platform; see
        // ThemePreferences for why only one of the two needs warming.
        single {
            ThemePreferences(
                get<SettingsStore>().dynamicColor,
                get<SettingsStore>().themeMode,
                get(),
                get<CoroutineScope>(),
            )
        }
        // Emoji catalog for the reaction picker: parsed once per process, off the main thread, the first time the
        // sheet opens; emoji this device's fonts cannot draw are dropped at load (Paint.hasGlyph).
        single { EmojiCatalogLoader(open = { androidContext().assets.open(EmojiCatalogLoader.ASSET) }, canRender = AndroidGlyphCheck()) }
        // Stable per-device id (ANDROID_ID) — seeds the soft block-continuity DeviceTag, not the nodeId.
        single<DeviceIdSource> { AndroidDeviceIdSource(androidContext()) }
        // The AndroidKeyStore, behind the seam KeystoreSecret's verdicts are tested through; the debug variant can
        // make it refuse on cue for a device trial (di/KeystoreWiring, ADR 2026-10.47rw).
        single<KeystoreCipher> { keystoreCipher(androidContext()) }
        // E2E identity keypair, wrapped under a hardware AndroidKeyStore key in filesDir (outside the DB).
        single {
            IdentityKeyStore(
                KeystoreSecret(androidContext(), IdentityKeyStore.KEYSTORE_ALIAS, IdentityKeyStore.FILE_NAME, cipher = get()),
            )
        }
        // nodeId is derived from the keypair's public bundle; the device id only feeds the block tag.
        single { Identity(get(), get()) }
        single { AvatarStore(androidContext(), get(), get()) }
        // One plaintext, one identity (ADR 2026-10.mptm): what a pick goes out as when the phone already holds it,
        // and the room copy a want takes from a sealed one instead of the mesh.
        single { AttachmentIdentities(get(), get()) }
        single { AttachmentStore(androidContext(), get(), get(), get()) }
        // Link previews (sender-fetched cards, off by default). The decoded-card store is app-scoped like the
        // blobs it opens; the gate is the one ConnectivityManager user outside the NAN data path; the fetcher
        // is the one place besides the spool dialer that speaks OkHttp, bound to the gate's validated network.
        single { LinkCardStore(get()) }
        single { AndroidInternetGate(androidContext(), get<CoroutineScope>()) }
        single<InternetGate> { get<AndroidInternetGate>() }
        single<PreviewFetcher> {
            val gate = get<AndroidInternetGate>()
            val base = OkHttpPreviewFetcher.baseClient()
            OkHttpPreviewFetcher(clientFor = { gate.currentNetwork()?.let { OkHttpPreviewFetcher.bound(base, it) } })
        }
        single {
            LinkPreviewService(
                gate = get(),
                fetcher = get(),
                screenImage = get<ImageScreeningService>()::isImageExplicit,
                textFlagged = { text, isRoom -> get<ScopedTextModerator>().classify(text, isRoom).flagged },
                shrink = PreviewImage::shrink,
                log = { Log.i(OkHttpPreviewFetcher.TAG, it) },
            )
        }
        // Where the composer's "Send location" reads the device's position: the one android.location importer,
        // behind a seam so the ViewModel is tested against a fake. Nothing collects it but the staged tile.
        single<LocationSource> { AndroidLocationSource(androidContext()) }
        single { GallerySaver(androidContext()) }
        // One voice player for the whole app: any number of voice-note bubbles can be on screen, and
        // starting one note has to stop whichever was playing. Owns its own scope (see VoicePlayer).
        single { VoicePlayer(androidContext(), get()) }
        // The conversation shortcuts every message notification names, kept inside the chat list's universe by
        // one sync (ADR 2026-10.jbsa). The sync's inputs are lazy: a phone with no shortcut never opens the
        // database for it, and MainActivity resolves the sync on the main thread.
        single { ConversationShortcuts(androidContext(), get()) }
        single<Notifier> { MessageNotifier(androidContext(), get()) }
        single { ConversationFaces(get(), get(), get(), commonsTitle = { get<CommonsRepository>().find(it)?.name }) }
        single {
            OfferedConversations(
                androidContext(),
                get(),
                get(),
                get(),
                get(),
                get<Identity>(),
                get<LoraStatusRepository>().facts,
                get<CommonsRepository>(),
            )
        }
        single {
            ConversationShortcutSync(
                shortcuts = get(),
                notifier = get(),
                scope = get(),
                offered = lazy { get<OfferedConversations>() },
                faces = lazy { get<ConversationFaces>() },
            )
        }
        single<ConversationGate> { get<ConversationShortcutSync>() }
        // Single-shot handoff for content arriving via the system share sheet (ACTION_SEND).
        single { ShareInbox() }
        // Debug trailer seam driving the real Nearby composer (see DemoComposer). Inert in every build
        // unless the debug DemoDirector emits into it; R8 strips it from release.
        single { DemoComposer() }
        // Single-shot handoff for a notification-tap deep-link route (drained by KnitApp).
        single { RouteInbox() }
        // Contact cards (docs/CONTACT_CARD.md): the minter, the importer, and the link handoff inbox.
        single { ContactCardInbox() }
        single { ContactCards(get(), get(), get<MessageCrypto>()::signRaw, home = get<HomeStore>().home) }
        single { ContactImporter(get(), get(), get(), get(), BuildConfig.INTERNET_PLANE) }
        // Its inverse: clears our own contact signals locally, tells no one (ADR 2026-09.adgd).
        single { ContactRemover(get(), get(), get(), get(), get(), get(), get(), get()) }
        // Relay invites (docs/RELAY_INVITE.md): the link handoff inbox and the one apply sequence both doors
        // share. The commons store rides only while `BuildConfig.COMMONS` is on — null is the wx8e seam.
        single { RelayInviteInbox() }
        // The customer keys an invite hands this phone (ADR 2026-10.3sk7), beside the relay list in the settings DataStore.
        // The free issuers this build ships (C-7.6-33) seed its free half; a debug build's bridge may add its own.
        // This phone's home relay and the directory it is picked from (ADR 2026-10.8kfx), beside the relay list.
        single { HomeStore(get()) }
        // With Knit Anywhere, each customer key's device key (C-7.6-39, ADR 2026-10.wk3m) in the Keystore beside it.
        single {
            TicketKeyStore(
                get(),
                FreeIssuers.parse(androidContext().resources.getStringArray(R.array.free_issuers)),
                keystore = if (ANYWHERE_WIRED) AndroidDeviceKeystore(androidContext().packageManager) else null,
                // Counts deep sleep, so "refused for an hour" means an hour on a dozing phone too.
                elapsed = SystemClock::elapsedRealtime,
            )
        }
        // The supporter badge (docs/SUPPORTER_BADGE.md, ADR 2026-10.r99s): the holder's toggle and held badges beside the
        // customer keys, and every peer's received badges verified at display time against this build's pinned keys.
        single { BadgeStore(get()) }
        single { PeerBadges(get<PeerRepository>().observeBadges(), get<CoroutineScope>(), BadgeKeys.forBuild(BuildConfig.DEBUG)) }
        // A build without Knit Anywhere (release: ANYWHERE_WIRED off) hands the applier no key store, so an invite's
        // issuer and key are ignored without a word, as the room half is with the commons dark.
        single {
            RelayInviteApplier(
                get(),
                if (BuildConfig.COMMONS) get<CommonsRepository>() else null,
                get(),
                keys = if (ANYWHERE_WIRED) get<TicketKeyStore>() else null,
            )
        }
        // Single-shot signal that the rate/review prompt should show (drained by KnitApp).
        single { ReviewPromptInbox() }
        // Decides when to ask for an app rating and where to route it (installer-aware); no-op in demo builds.
        single { ReviewPromptStore(get()) }
        single { ReviewPrompter(androidContext(), get(), get(), get(), get()) }

        single {
            DatabaseKey(
                androidContext(),
                KeystoreSecret(androidContext(), DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, cipher = get()),
            )
        }
        // Throws KeystoreUnavailableException, with every file left as it was, when the Keystore refuses the unwrap:
        // MeshService stands down and ui/StorageGate offers Try again (ADR 2026-10.47rw).
        single { KnitDatabase.build(androidContext(), get<DatabaseKey>().getOrCreate()) }
        // Backup and restore (docs/BACKUP_FORMAT.md). The writer reads the identity file through its own
        // KeystoreSecret over the same alias and file IdentityKeyStore uses; the stager verifies a staged
        // database by opening it with the SQLCipher driver alone. Neither touches the live database's
        // write lock; the apply itself runs pre-Koin in KnitApplication (data/backup/RestoreApplier).
        single { BackupWriter(androidContext(), get(), BackupWriter.identitySecret(androidContext(), get()), get(), get(), get()) }
        single { RestoreStager(androidContext(), BackupWriter::openSqlCipher) }
        single { get<KnitDatabase>().messageDao() }
        single { get<KnitDatabase>().peerDao() }
        single { get<KnitDatabase>().reactionDao() }
        single { get<KnitDatabase>().blobDao() }
        single { get<KnitDatabase>().groupDao() }
        single { get<KnitDatabase>().blobVerdictDao() }
        single { get<KnitDatabase>().forwardDao() }
        single { get<KnitDatabase>().ratchetDao() }
        single { get<KnitDatabase>().groupRatchetDao() }
        single { get<KnitDatabase>().groupRootDao() }
        single { get<KnitDatabase>().messageReceiptDao() }
        single { get<KnitDatabase>().draftDao() }
        single { get<KnitDatabase>().commonsDao() }
        single { get<KnitDatabase>().metPeerDao() }
        single { get<KnitDatabase>().savedFileDao() }
        single { get<KnitDatabase>().attachmentHolderDao() }
        single { MessageRepository(get()) }
        // Judges an inbound reply's quote against the original it names (ADR 2026-10.gpa3), labelling a rebuilt
        // quote in the reader's own strings; one backlog batch is one write transaction.
        single {
            val db = get<KnitDatabase>()
            QuoteVerifier(
                messages = get(),
                labels = { quoteLabels(androidContext()) },
                transact = { block -> db.withWriteTransaction { block() } },
            )
        }
        // The name-clash fade (ADR 2026-10.k8ge): the never-stale anchors, shared on the app scope so every screen's
        // directory reads one authored-in query; the stamps bank in one write transaction on the metrics tick.
        single { LabelAnchors.from(get(), get(), get<SettingsStore>(), get<Identity>(), get<CoroutineScope>()) }
        single {
            val db = get<KnitDatabase>()
            val fade =
                LabelFade(
                    anchors = get(),
                    clock = System::currentTimeMillis,
                    ticks = LabelFade.hourly(),
                    transact = { block -> db.withWriteTransaction { block() } },
                )
            PeerRepository(get(), get<SettingsStore>(), get<Identity>(), fade = fade)
        }
        // Crash reports. The capture-side CrashStore is built by hand in KnitApplication.onCreate BEFORE
        // startKoin, so a crash inside startup itself is still captured; this is the reader side over the
        // same fixed directory (crashStore() is the single definition of the path, so the two can't drift).
        // Two instances is deliberate and harmless — CrashStore holds no state beyond that File.
        single { crashStore(androidContext()) }
        // Applies the known-contact-name redaction pass the dying handler couldn't run (the names live in
        // the encrypted DB and DataStore) and stages the share copy under cacheDir/crash.
        single { CrashReports(androidContext(), get(), get(), get(), get()) }
        // The one thing CrashHandler structurally cannot see — a native crash — read back from the
        // platform's own exit records. ADR 028 named this as the follow-on; ModelLoadGuard consumes it.
        single { ProcessExitReasons(androidContext()) }
        // Whether this phone is a generic system image over an older vendor, read once from its build fingerprints:
        // hosting a Wi-Fi Direct group (ADR 2026-10.w3dw) and a call's microphone (ADR 2026-10.kw29) work around it.
        single {
            DeviceImage.read(
                fingerprint = Build.FINGERPRINT,
                partitions =
                    runCatching { Build.getFingerprintedPartitions().associate { it.name to it.fingerprint } }.getOrDefault(
                        emptyMap(),
                    ),
            )
        }
        single { ReactionRepository(get(), get()) }
        // Who has acked each message — the message-details screen's per-recipient delivery split. Owns the
        // delivery write (tick + acker row in one transaction), so it wraps MessageRepository.
        single { MessageReceiptRepository(get(), get(), get()) }
        // BlobRepository: blobDao, messageDao, peerDao, settings, blobVerdictDao, groupDao, forwardDao, db,
        // savedFileDao.
        single { BlobRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { GroupRepository(get(), get(), get(), get(), get()) }
        // Store-and-forward custody for DMs, backed by the encrypted forward_store table. Takes the shared
        // StoreDigest (from meshModule) so every carry-store mutation keeps the cue-plane content digest in sync, and the
        // MeshMetrics (from meshModule too) its group gate counts refusals on (ADR 2026-10.7mqm),
        // plus the KnitDatabase so store/remove/sweep run their DB writes in a transaction under the repo mutex.
        // The concrete class is registered too: YourMeshViewModel reads its "carrying for others" projection,
        // which is a UI read and deliberately not on the seam (the seam's fakes model custody, not counts).
        single { ForwardRepository(get(), get(), get(), metrics = get()) }
        single<ForwardStore> { get<ForwardRepository>() }
        // DM epoch-ratchet session state (docs/FORWARD_SECRECY_RATCHET.md), in the encrypted DB so the
        // ratchet advance commits in the same transaction as the message row it decrypted/sealed.
        single<RatchetStore> { RatchetRepository(get()) }
        // Group sender-key ratchet state (docs/GROUP_FORWARD_SECRECY.md), same transactional posture.
        single<GroupRatchetStore> { GroupRatchetRepository(get()) }
        // The spool plane's shared group roots (docs/SPOOL_PROTOCOL.md §3.2). Deliberately NOT scoped to the
        // Internet plane's own lifetime: a device with the plane off still adopts and re-gossips roots, which
        // is what carries one across a plane-off member sitting between two plane-on ones.
        single<GroupRootStore> { GroupRootRepository(get()) }
        // Unsent composer text, one row per thread. App-scoped on purpose: the write that keeps a draft is
        // started as the user leaves the chat, so it cannot run on the screen's own scope.
        single { DraftRepository(get(), get<CoroutineScope>()) }
        single { CommonsRepository(get(), get(), get()) }
        // The phones this one has met — the lifetime union of the nearby set, for the Your mesh screen.
        single { MetPeerRepository(get(), get()) }
        single<CommonsStore> { get<CommonsRepository>() }
    }
