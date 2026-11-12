package com.tfassbender.shoppinglist.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.code
import com.tfassbender.shoppinglist.data.repo.AuthRepository
import com.tfassbender.shoppinglist.data.repo.RegisterResult
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
import com.tfassbender.shoppinglist.ui.common.ErrorCodeText
import com.tfassbender.shoppinglist.ui.common.ErrorText
import com.tfassbender.shoppinglist.ui.common.LinkText
import com.tfassbender.shoppinglist.ui.common.Page
import com.tfassbender.shoppinglist.ui.common.PageContent
import com.tfassbender.shoppinglist.ui.common.SuccessText
import com.tfassbender.shoppinglist.ui.common.pageViewModel
import com.tfassbender.shoppinglist.ui.navigation.LocalNavigator
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A form's request state: running, its error, and whether it succeeded (for "email sent" messages). */
data class FormState(val pending: Boolean = false, val error: ApiError? = null, val done: Boolean = false)

/**
 * Login, registration and "forgot password". Verifying the email and resetting the password happen in
 * the web app (the emailed links point there); the app only sends the emails.
 */
class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    private val state = MutableStateFlow(FormState())
    val form = state.asStateFlow()

    /** Set after registering when the email must be confirmed first. */
    private val verification = MutableStateFlow<String?>(null)
    val checkMailFor = verification.asStateFlow()

    private val resent = MutableStateFlow(false)
    val verificationResent = resent.asStateFlow()

    fun login(email: String, password: String) = run { auth.login(email, password) }

    fun register(email: String, displayName: String, password: String, language: String) = run {
        auth.register(email, displayName, password, language).also { result ->
            if (result is ApiResult.Success && result.value is RegisterResult.VerificationRequired) verification.value = email.trim()
        }
    }

    fun forgotPassword(email: String) = run { auth.forgotPassword(email) }

    fun resendVerification(email: String) {
        viewModelScope.launch {
            if (auth.resendVerification(email) is ApiResult.Success) resent.value = true
        }
    }

    private fun run(call: suspend () -> ApiResult<*>) {
        if (state.value.pending) return
        state.value = FormState(pending = true)
        viewModelScope.launch {
            state.value = when (val result = call()) {
                is ApiResult.Success -> FormState(done = true)
                is ApiResult.Failure -> FormState(error = result.error)
            }
        }
    }
}

@Composable
private fun AuthForm(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }
}

@Composable
fun LoginScreen(sessionExpired: Boolean) {
    val vm = pageViewModel { AuthViewModel(it.auth) }
    val navigator = LocalNavigator.current
    val form by vm.form.collectAsState()
    val resent by vm.verificationResent.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { if (email.isNotBlank() && password.isNotEmpty()) vm.login(email, password) }

    Page(stringResource(R.string.auth_login_title)) { padding ->
        PageContent(padding) {
            AuthForm {
                if (sessionExpired && form.error == null && !form.pending) ErrorCodeText("SESSION_EXPIRED")
                AppTextField(email, { email = it }, stringResource(R.string.auth_email), keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, maxLength = 254)
                AppTextField(password, { password = it }, stringResource(R.string.auth_password), password = true, onImeAction = submit, maxLength = 200)
                ErrorText(form.error)
                if (form.error?.code == "EMAIL_NOT_VERIFIED") {
                    if (resent) SuccessText(stringResource(R.string.auth_verify_resent))
                    else AppButton(stringResource(R.string.auth_verify_resend), { vm.resendVerification(email) }, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY)
                }
                AppButton(stringResource(R.string.auth_login_submit), submit, Modifier.fillMaxWidth(), enabled = !form.pending)
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LinkText(stringResource(R.string.auth_login_noAccount), { navigator.push(Screen.Register) })
                    LinkText(stringResource(R.string.auth_login_forgot), { navigator.push(Screen.ForgotPassword) }, muted = true)
                }
            }
        }
    }
}

private const val MIN_PASSWORD_LENGTH = 8

@Composable
fun RegisterScreen() {
    val vm = pageViewModel { AuthViewModel(it.auth) }
    val navigator = LocalNavigator.current
    val form by vm.form.collectAsState()
    val checkMailFor by vm.checkMailFor.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // The language of the texts now - also decides the language of the default categories.
    val language = if (LocalConfiguration.current.locales[0].language == "de") "de" else "en"
    val submit = { if (email.isNotBlank() && displayName.isNotBlank() && password.isNotEmpty()) vm.register(email, displayName, password, language) }

    Page(stringResource(R.string.auth_register_title)) { padding ->
        PageContent(padding) {
            AuthForm {
                if (checkMailFor != null) {
                    SuccessText(stringResource(R.string.auth_register_checkMail, checkMailFor!!))
                    return@AuthForm
                }
                AppTextField(email, { email = it }, stringResource(R.string.auth_email), keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, maxLength = 254)
                Column {
                    AppTextField(displayName, { displayName = it }, stringResource(R.string.auth_displayName), imeAction = ImeAction.Next, maxLength = 40)
                    Text(stringResource(R.string.auth_displayNameHint), color = AppTheme.colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Column {
                    AppTextField(password, { password = it }, stringResource(R.string.auth_password), password = true, onImeAction = submit, maxLength = 200)
                    Text(stringResource(R.string.auth_passwordHint, MIN_PASSWORD_LENGTH.toString()), color = AppTheme.colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                ErrorText(form.error)
                AppButton(stringResource(R.string.auth_register_submit), submit, Modifier.fillMaxWidth(), enabled = !form.pending)
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LinkText(stringResource(R.string.auth_register_haveAccount), { navigator.back() })
                }
            }
        }
    }
}

@Composable
fun ForgotPasswordScreen() {
    val vm = pageViewModel { AuthViewModel(it.auth) }
    val form by vm.form.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    val submit = { if (email.isNotBlank()) vm.forgotPassword(email) }

    Page(stringResource(R.string.auth_forgot_title)) { padding ->
        PageContent(padding) {
            AuthForm {
                if (form.done) {
                    SuccessText(stringResource(R.string.auth_forgot_sent))
                } else {
                    AppTextField(email, { email = it }, stringResource(R.string.auth_email), keyboardType = KeyboardType.Email, onImeAction = submit, maxLength = 254, autoFocus = true)
                    ErrorText(form.error)
                    AppButton(stringResource(R.string.auth_forgot_submit), submit, Modifier.fillMaxWidth(), enabled = !form.pending)
                }
            }
        }
    }
}
