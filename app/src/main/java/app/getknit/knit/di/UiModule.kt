package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.location.LocationSource
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.ui.addcontact.AddContactViewModel
import app.getknit.knit.ui.backup.BackupViewModel
import app.getknit.knit.ui.blocked.BlockedUsersViewModel
import app.getknit.knit.ui.chat.ChatViewModel
import app.getknit.knit.ui.chat.MessageDetailsViewModel
import app.getknit.knit.ui.chatlist.ChatListViewModel
import app.getknit.knit.ui.contacts.ContactsViewModel
import app.getknit.knit.ui.diagnostics.CrashLogViewModel
import app.getknit.knit.ui.diagnostics.DiagnosticsViewModel
import app.getknit.knit.ui.group.GroupDetailsViewModel
import app.getknit.knit.ui.lora.LoraRadioViewModel
import app.getknit.knit.ui.onboarding.OnboardingViewModel
import app.getknit.knit.ui.profile.ProfileDetailsViewModel
import app.getknit.knit.ui.profile.ProfileViewModel
import app.getknit.knit.ui.relay.InternetRelayViewModel
import app.getknit.knit.ui.requests.MessageRequestsViewModel
import app.getknit.knit.ui.search.SearchViewModel
import app.getknit.knit.ui.settings.SettingsViewModel
import app.getknit.knit.ui.yourmesh.YourMeshViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule =
    module {
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
            )
        }
        viewModel { ContactsViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel {
            SearchViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get<LoraStatusRepository>().facts,
                androidContext(),
                get<CommonsRepository>(),
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
            )
        }
        viewModel { CrashLogViewModel(get()) }
        // Your mesh: the plain-words companion to Diagnostics. Reads the concrete ForwardRepository for its
        // "carrying now" count — a UI projection deliberately kept off the ForwardStore seam.
        viewModel { YourMeshViewModel(get(), get(), get(), get<ForwardRepository>(), get()) }
        viewModel { ProfileViewModel(get(), get(), get(), get()) }
        viewModel { OnboardingViewModel(get(), get()) }
        viewModel {
            SettingsViewModel(get(), get(), get<RelayStatusRepository>().facts, get<LoraStatusRepository>().facts)
        }
        viewModel { BackupViewModel(androidContext(), get(), get()) }
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
                get<RelayStatusRepository>().statuses,
                androidContext(),
            )
        }
        // MessageDetailsViewModel takes the long-pressed message's id as a runtime param.
        viewModel { params -> MessageDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), get()) }
        // GroupDetailsViewModel takes the group id as a runtime param; the rest are resolved by type.
        viewModel { params ->
            GroupDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), get(), androidContext())
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
            )
        }
        viewModel { LoraRadioViewModel(get(), get(), get(), get()) }
    }
