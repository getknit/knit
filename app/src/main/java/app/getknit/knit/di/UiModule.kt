package app.getknit.knit.di

import app.getknit.knit.ui.chat.ChatViewModel
import app.getknit.knit.ui.profile.ProfileViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { ProfileViewModel(get(), get(), get()) }
}
