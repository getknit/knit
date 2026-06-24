package app.getknit.knit.di

import app.getknit.knit.ui.chat.ChatViewModel
import app.getknit.knit.ui.chatlist.ChatListViewModel
import app.getknit.knit.ui.profile.ProfileViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { ChatListViewModel(get(), get(), get(), get(), get(), androidContext()) }
    viewModel { ProfileViewModel(get(), get(), get()) }
}
