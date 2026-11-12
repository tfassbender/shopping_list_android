package com.tfassbender.shoppinglist.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** ViewModel factory that hands the [AppContainer] to [create] (as in the homeautomation app). */
inline fun <reified VM : ViewModel> appViewModelFactory(
    container: AppContainer,
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create(container) }
}

/** The ViewModel of the current page - it lives as long as the page is on the back stack (see NavHost). */
@Composable
inline fun <reified VM : ViewModel> pageViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(factory = appViewModelFactory(container, create))
}

/** The app container for composables that only read app-wide state (sync badge, theme). */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("no AppContainer provided") }

fun <T> Flow<T>.stateIn(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** The error of a failed call, null on success. */
val <T> ApiResult<T>.error: ApiError? get() = (this as? ApiResult.Failure)?.error
