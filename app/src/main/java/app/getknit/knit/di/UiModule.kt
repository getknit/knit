package app.getknit.knit.di

import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.ui.addcontact.AddContactViewModel
import app.getknit.knit.ui.blocked.BlockedUsersViewModel
import app.getknit.knit.ui.chat.ChatViewModel
import app.getknit.knit.ui.chat.MessageDetailsViewModel
import app.getknit.knit.ui.chatlist.ChatListViewModel
import app.getknit.knit.ui.contacts.ContactsViewModel
import app.getknit.knit.ui.diagnostics.CrashLogViewModel
import app.getknit.knit.ui.diagnostics.DiagnosticsViewModel
import app.getknit.knit.ui.group.GroupDetailsViewModel
import app.getknit.knit.ui.lora.LoraRadioViewModel
import app.getknit.knit.ui.profile.ProfileDetailsViewModel
import app.getknit.knit.ui.profile.ProfileViewModel
import app.getknit.knit.ui.relay.InternetRelayViewModel
import app.getknit.knit.ui.requests.MessageRequestsViewModel
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
                get<LinkCardStore>(),
                get<LinkPreviewService>(),
                get<RelayStatusRepository>().facts,
                get<LoraStatusRepository>().facts,
                androidContext(),
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
                get<RelayStatusRepository>().facts,
                get<LoraStatusRepository>().facts,
                androidContext(),
            )
        }
        viewModel { ContactsViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { DiagnosticsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { CrashLogViewModel(get()) }
        viewModel {
            ProfileViewModel(get(), get(), get(), get(), get<RelayStatusRepository>().facts, get<LoraStatusRepository>().facts)
        }
        // ProfileDetailsViewModel takes the tapped peer's node id as a runtime param.
        viewModel { params -> ProfileDetailsViewModel(params.get(), get(), get(), get(), get()) }
        // MessageDetailsViewModel takes the long-pressed message's id as a runtime param.
        viewModel { params -> MessageDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), get()) }
        // GroupDetailsViewModel takes the group id as a runtime param; the rest are resolved by type.
        viewModel { params ->
            GroupDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), androidContext())
        }
        viewModel { BlockedUsersViewModel(get(), get()) }
        viewModel { MessageRequestsViewModel(get(), get(), get(), get(), get(), androidContext()) }
        viewModel { AddContactViewModel(get(), get(), get(), get(), get()) }
        viewModel { InternetRelayViewModel(get(), get()) }
        viewModel { LoraRadioViewModel(get(), get(), get(), get()) }
    }
