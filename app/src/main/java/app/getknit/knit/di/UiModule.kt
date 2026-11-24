package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.badge.BadgeKeys
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CaptureHealth
import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.call.VoiceNoteSpeech
import app.getknit.knit.contacts.DirectContacts
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.StickerRepository
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.meshcore.MeshcoreSetupStore
import app.getknit.knit.data.presencekey.PresenceKeyRepository
import app.getknit.knit.data.relay.FreeUploads
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.AwareSwitch
import app.getknit.knit.data.settings.BadgeStore
import app.getknit.knit.data.settings.CallPreferenceStore
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.data.settings.HiddenKnownStore
import app.getknit.knit.data.settings.IncomingCallStore
import app.getknit.knit.data.settings.MeshcoreClaimStore
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TicketKeyStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.location.LocationSource
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.lora.meshcore.MeshcoreForget
import app.getknit.knit.mesh.power.LowPowerSource
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.redact.FaceDetector
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.translate.ChatTranslation
import app.getknit.knit.translate.ReaderLanguage
import app.getknit.knit.ui.StorageGate
import app.getknit.knit.ui.addcontact.AddContactViewModel
import app.getknit.knit.ui.backup.BackupViewModel
import app.getknit.knit.ui.blocked.BlockedUsersViewModel
import app.getknit.knit.ui.chat.ChatViewModel
import app.getknit.knit.ui.chat.MeshcoreNaming
import app.getknit.knit.ui.chat.MessageDetailsViewModel
import app.getknit.knit.ui.chatlist.ChatListViewModel
import app.getknit.knit.ui.commons.CommonsDetailsViewModel
import app.getknit.knit.ui.contacts.ContactsViewModel
import app.getknit.knit.ui.diagnostics.CrashLogViewModel
import app.getknit.knit.ui.diagnostics.DiagnosticsViewModel
import app.getknit.knit.ui.diagnostics.FieldLogViewModel
import app.getknit.knit.ui.group.GroupDetailsViewModel
import app.getknit.knit.ui.lora.LoraRadioViewModel
import app.getknit.knit.ui.media.ChatMediaViewModel
import app.getknit.knit.ui.onboarding.OnboardingViewModel
import app.getknit.knit.ui.preset.StartingPointViewModel
import app.getknit.knit.ui.profile.ProfileDetailsViewModel
import app.getknit.knit.ui.profile.ProfileViewModel
import app.getknit.knit.ui.relay.InternetRelayViewModel
import app.getknit.knit.ui.requests.MessageRequestsViewModel
import app.getknit.knit.ui.search.SearchViewModel
import app.getknit.knit.ui.settings.ChatSettingsViewModel
import app.getknit.knit.ui.settings.SettingsViewModel
import app.getknit.knit.ui.share.OutsideUris
import app.getknit.knit.ui.update.PeerUpdateViewModel
import app.getknit.knit.ui.yourmesh.YourMeshViewModel
import app.getknit.knit.update.AndroidAppBuild
import app.getknit.knit.update.PeerUpdates
import app.getknit.knit.update.UpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule =
    module {
        // A DM's half of a peer update (ADR 2026-11.29m7), beside ChatViewModel and keyed the same way. Its seams are
        // absent in a build without peer updates, so it shows nothing there.
        viewModel { params ->
            PeerUpdateViewModel(
                conversationId = params.get(),
                updates = if (BuildConfig.PEER_UPDATE) get<PeerUpdates>() else null,
                installer = if (BuildConfig.PEER_UPDATE) get<UpdateInstaller>() else null,
                build = if (BuildConfig.PEER_UPDATE) get<AndroidAppBuild>() else null,
                staging = get(),
                transfers = get(),
                settings = get(),
            )
        }
        // ChatViewModel takes the conversationId (the Nearby room, a peer's node id, or a group id) as a
        // runtime param; the rest (incl. GroupRepository) are resolved by type.
        viewModel { params ->
            ChatViewModel(
                params.get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get<LinkCardStore>(),
                get<LinkPreviewService>(),
                get<LocationSource>(),
                get<RelayStatusRepository>().facts,
                get<LoraStatusRepository>().facts,
                androidContext(),
                get<TransferManager>(),
                get<CommonsRepository>(),
                get<AttachmentIdentities>(),
                if (CALLS_WIRED) get<CallManager>() else null,
                freeUploads = if (ANYWHERE_WIRED) get<FreeUploads>() else null,
                preferMeshCalls = if (LIVE_CALLS_WIRED && WALKIE_WIRED) get<CallPreferenceStore>().preferMesh else flowOf(false),
                directLinksOn = if (DIRECT_LINKS_WIRED) get<SettingsStore>().directLinksEnabled else flowOf(false),
                noteSpeech = if (STT_WIRED) get<VoiceNoteSpeech>() else null,
                translation = if (TRANSLATE_WIRED) get<ChatTranslation>() else null,
                pins = get<PinRepository>(),
                localDelete = get<LocalMessageDelete>(),
                outsideUris = get<OutsideUris>(),
                // Each bridged room by its own kind's facts, and the MeshCore board's own name (ADR 2026-11.gsqu, Stage 6).
                loraRooms = get<LoraStatusRepository>().perKind,
                meshcoreBoardName = get<MeshcoreClaimStore>().boardKey.map(::meshcoreBoardName),
                // The board's live name, its owner's handle, off its plane's face (ADR 2026-11.f4d3), where the build carries it.
                meshcoreNaming =
                    if (BuildConfig.MESHCORE_PLANE) {
                        getOrNull<LoraPlaneStatus>(loraPlane(BoardKind.MESHCORE))
                            ?.status
                            ?.map { status ->
                                status.meshcore?.let { face ->
                                    face.handle?.let { MeshcoreNaming(it, face.namedDefault, face.handleUnpostable) }
                                }
                            }?.distinctUntilChanged()
                            ?: flowOf(null)
                    } else {
                        flowOf(null)
                    },
                // Whether the bound MeshCore board may share where it is, off its plane's status, where the build carries it.
                meshcoreSharesPosition =
                    if (BuildConfig.MESHCORE_PLANE) {
                        getOrNull<LoraPlaneStatus>(loraPlane(BoardKind.MESHCORE))
                            ?.status
                            ?.map { it.meshcore?.sharesPosition }
                            ?.distinctUntilChanged()
                            ?: flowOf(null)
                    } else {
                        flowOf(null)
                    },
                faces = if (FACE_REDACTION_WIRED) get<FaceDetector>() else null,
                mutes = get<ChatMutes>(),
                stickers = get<StickerRepository>(),
            )
        }
        viewModel {
            ChatListViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get<TransferManager>(),
                get<RelayStatusRepository>().facts,
                get<LoraStatusRepository>().facts,
                androidContext(),
                get<CommonsRepository>(),
                get<ReactionRepository>(),
                receipts = get<MessageReceiptRepository>(),
                loraRooms = get<LoraStatusRepository>().perKind,
                mutes = get<ChatMutes>(),
            )
        }
        viewModel { ContactsViewModel(get(), get(), get(), get(), get(), get()) }
        // The scoped thread is an optional runtime param — "Search" in a chat's overflow; none is app-wide search.
        viewModel { params ->
            SearchViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get<LoraStatusRepository>().facts,
                androidContext(),
                get<CommonsRepository>(),
                inConversation = params.getOrNull(),
                loraRooms = get<LoraStatusRepository>().perKind,
            )
        }
        viewModel {
            DiagnosticsViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                radios = RadioSupport.probe(androidContext()),
                loraFacts = get<LoraStatusRepository>().facts,
                hostGuard = get(),
                captureHealth = if (LIVE_CALLS_WIRED) get<CaptureHealth>() else null,
                awareSwitchOn = get<AwareSwitch>().on,
                hiddenKnown = get<HiddenKnownStore>(),
                presenceKeyHolders = get<PresenceKeyRepository>().observePeersWithKeys(),
                directContacts = get<DirectContacts>().observe(),
            )
        }
        viewModel { CrashLogViewModel(get(), get()) }
        viewModel { FieldLogViewModel(get(), get(), get(), get()) }
        // Your mesh: the plain-words companion to Diagnostics. Reads the concrete ForwardRepository for its
        // "carrying now" count — a UI projection deliberately kept off the ForwardStore seam.
        viewModel { YourMeshViewModel(get(), get(), get(), get<ForwardRepository>(), get()) }
        viewModel {
            ProfileViewModel(
                get(),
                get(),
                get(),
                get(),
                get<CoroutineScope>(),
                // The own badge's switch, only where Knit Anywhere is wired: elsewhere no key or badge is ever held.
                badges = if (ANYWHERE_WIRED) get<BadgeStore>() else null,
                ticketKeys = get<TicketKeyStore>(),
                pins = BadgeKeys.forBuild(BuildConfig.DEBUG),
                names = get(),
            )
        }
        viewModel { OnboardingViewModel(get(), get(), names = get(), presets = get()) }
        viewModel { ChatSettingsViewModel(get(), reader = if (TRANSLATE_WIRED) get<ReaderLanguage>() else null) }
        viewModel {
            SettingsViewModel(
                get(),
                get(),
                get<RelayStatusRepository>().facts,
                get<LoraStatusRepository>().facts,
                // A build without own servers (OWN_ICE_WIRED off) never names one, whatever the store holds.
                if (OWN_ICE_WIRED) get<OwnIceStore>().ownIce else flowOf(OwnIceServers()),
                get(),
                get<LowPowerSource>().posture,
                intake = get<IncomingCallStore>().intake,
                liveCalls = LIVE_CALLS_WIRED,
                meshCalls = WALKIE_WIRED,
                awareSwitch = get(),
                presets = get(),
            )
        }
        viewModel { StartingPointViewModel(get(), get<SettingsStore>().spoolConsented) }
        viewModel {
            BackupViewModel(
                androidContext(),
                get(),
                get(),
                // A restore loses this phone's MeshCore setup records: the dialog warns, where the build carries the plane.
                meshcoreSetupOnPhone =
                    if (BuildConfig.MESHCORE_PLANE) {
                        getOrNull<MeshcoreSetupStore>()?.let { store -> suspend { store.anyRecord() } }
                    } else {
                        null
                    },
            )
        }
        // ProfileDetailsViewModel takes the tapped peer's node id as a runtime param.
        viewModel { params ->
            ProfileDetailsViewModel(
                params.get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get<RelayStatusRepository>().statuses,
                androidContext(),
                calls = if (CALLS_WIRED) get<CallManager>() else null,
                pins = get<PinRepository>(),
                mutes = get<ChatMutes>(),
            )
        }
        // MessageDetailsViewModel takes the long-pressed message's id as a runtime param.
        viewModel { params ->
            MessageDetailsViewModel(
                params.get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get<MeshController>().loraRadioReached,
                blobs = get(),
                context = androidContext(),
            )
        }
        // ChatMediaViewModel takes the thread's conversation id as a runtime param (ADR 2026-11.dgvf).
        viewModel { params ->
            ChatMediaViewModel(
                conversationId = params.get(),
                media = get(),
                blobs = get(),
                peers = get(),
                settings = get(),
                identity = get<Identity>(),
                flaggedHashes = get<ImageScreeningService>().observeFlaggedHashes(),
                linkCards = get<LinkCardStore>(),
                export = get(),
                context = androidContext(),
            )
        }
        // GroupDetailsViewModel takes the group id as a runtime param; the rest are resolved by type.
        viewModel { params ->
            GroupDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), get(), androidContext(), mutes = get<ChatMutes>())
        }
        // A commons' room details, keyed by its conversation id; no store while the commons is hidden.
        viewModel { params ->
            CommonsDetailsViewModel(
                params.get(),
                if (BuildConfig.COMMONS) get<CommonsRepository>() else null,
                get(),
                get<MeshController>(),
                get<RelayStatusRepository>().facts,
                get(),
                androidContext(),
                mutes = get<ChatMutes>(),
            )
        }
        viewModel { BlockedUsersViewModel(get(), get()) }
        viewModel { MessageRequestsViewModel(get(), get(), get(), get(), get(), get(), androidContext()) }
        viewModel { AddContactViewModel(get(), get(), get(), get(), get(), relays = get()) }
        // Same seam as the mesh's: no store while the commons is hidden, so the relay editor draws no room.
        viewModel {
            InternetRelayViewModel(
                get(),
                get(),
                if (BuildConfig.COMMONS) get<CommonsRepository>() else null,
                get<MeshController>(),
                inbox = get(),
                applier = get(),
                gate = get(),
                // In every build: removing a relay forgets a ticket the debug bridge imported even where no key exists.
                ticketKeys = get<TicketKeyStore>(),
                // A row's call details (TURN, STUN, casts, the ticket and its key) only where calls or Knit Anywhere are
                // wired, and its free tickets' switch only where Knit Anywhere is.
                spoolTickets = if (CALLS_WIRED || ANYWHERE_WIRED) get<SettingsStore>() else null,
                freeUploads = if (ANYWHERE_WIRED) get<FreeUploads>() else null,
                directLinks = DIRECT_LINKS_WIRED,
            )
        }
        // The radio screen drives the bound board: Meshtastic's plane, and MeshCore's with its Forget only where the build
        // carries that plane (ADR 2026-11.gsqu's Stage 7 amendment); a release build resolves neither and reads as before.
        viewModel {
            LoraRadioViewModel(
                settings = get(),
                lora = get(loraPlane(BoardKind.MESHTASTIC)),
                boards = get(),
                notifier = get(),
                meshcore = if (BuildConfig.MESHCORE_PLANE) getOrNull<LoraPlaneStatus>(loraPlane(BoardKind.MESHCORE)) else null,
                forget = if (BuildConfig.MESHCORE_PLANE) getOrNull<MeshcoreForget>() else null,
                names = if (BuildConfig.MESHCORE_PLANE) get() else null,
            )
        }
        // What MainActivity opens on a worker before KnitApp composes — exactly what KnitApp's first composition
        // reads (ReviewPrompter → the database, MeshController → the identity keys), so those become cache hits —
        // and the Try again screen when the Keystore refuses (ADR 2026-10.47rw). SEED_DEMO builds skip the
        // controller, as KnitApp does.
        single {
            val koin = getKoin()
            StorageGate(
                scope = get(),
                openStorage = {
                    koin.get<KnitDatabase>()
                    koin.get<IdentityKeyStore>().keys()
                    if (!BuildConfig.SEED_DEMO) koin.get<MeshController>()
                },
            )
        }
    }
