package com.tfassbender.shoppinglist.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base for pages with online actions (the web app's `useMutation`): one action at a time, its error shown
 * on the page until the next action starts.
 */
abstract class ActionViewModel : ViewModel() {
    private val errorState = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = errorState.asStateFlow()

    private val pendingState = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = pendingState.asStateFlow()

    fun clearError() {
        errorState.value = null
    }

    /** Runs [call]; on success [onSuccess], on failure the error is shown. */
    protected fun <T> act(call: suspend () -> ApiResult<T>, onSuccess: (T) -> Unit = {}) {
        if (pendingState.value) return
        pendingState.value = true
        errorState.value = null
        viewModelScope.launch {
            try {
                when (val result = call()) {
                    is ApiResult.Success -> onSuccess(result.value)
                    is ApiResult.Failure -> errorState.value = result.error
                }
            } finally {
                pendingState.value = false
            }
        }
    }
}
