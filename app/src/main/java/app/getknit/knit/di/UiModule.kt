package app.getknit.knit.di

import app.getknit.knit.ui.blocked.BlockedUsersViewModel
import app.getknit.knit.ui.chat.ChatViewModel
import app.getknit.knit.ui.chatlist.ChatListViewModel
import app.getknit.knit.ui.contacts.ContactsViewModel
import app.getknit.knit.ui.diagnostics.DiagnosticsViewModel
import app.getknit.knit.ui.profile.ProfileDetailsViewModel
import app.getknit.knit.ui.profile.ProfileViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    // ChatViewModel takes the conversationId (the Nearby room, a peer's node id, or a group id) as a
    // runtime param; the rest (incl. GroupRepository) are resolved by type.
    viewModel { params ->
        ChatViewModel(
            params.get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            androidContext(),
        )
    }
    viewModel { ChatListViewModel(get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { ContactsViewModel(get(), get(), get(), get(), get()) }
    viewModel { DiagnosticsViewModel(get(), get(), get(), get(), get()) }
    viewModel { ProfileViewModel(get(), get(), get(), get()) }
    // ProfileDetailsViewModel takes the tapped peer's node id as a runtime param.
    viewModel { params -> ProfileDetailsViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { BlockedUsersViewModel(get(), get()) }
}
