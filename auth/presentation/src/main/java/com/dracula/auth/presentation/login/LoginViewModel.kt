package com.dracula.auth.presentation.login

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text2.input.textAsFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dracula.auth.domain.AuthRepository
import com.dracula.auth.domain.UserDataValidator
import com.dracula.auth.presentation.R
import com.dracula.core.domain.utils.DataError
import com.dracula.core.domain.utils.Result
import com.dracula.core.presentation.ui.UiText
import com.dracula.core.presentation.ui.asUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
class LoginViewModel(
	private val authRepository: AuthRepository,
	private val userDataValidator: UserDataValidator,
) : ViewModel() {
	var state by mutableStateOf(LoginState())
		private set

	private val eventsChannel = Channel<LoginEvent>()
	val events = eventsChannel.receiveAsFlow()

	init {
		combine(
			state.email.textAsFlow(),
			state.password.textAsFlow()
		) { email, password ->
			state = state.copy(
				canLogin = userDataValidator.isValidEmail(email = email.trim().toString())
						&& password.isNotEmpty()
			)
		}.launchIn(viewModelScope)
	}

	fun onAction(action: LoginAction) {
		when (action) {
			LoginAction.OnLoginClick -> {
				login()
			}


			LoginAction.OnTogglePasswordVisibility -> {
				state = state.copy(isPasswordVisible = !state.isPasswordVisible)
			}

			else -> Unit
		}
	}

	private fun login() {
		viewModelScope.launch {
			state = state.copy(isLoggingIn = true)
			val result = authRepository.login(
				email = state.email.text.toString().trim(),
				password = state.password.text.toString()
			)
			state = state.copy(isLoggingIn = false)
			when (result) {
				is Result.Success -> eventsChannel.send(LoginEvent.LoginSuccess)
				is Result.Error -> {
					if (result.error == DataError.Network.UNAUTHORIZED) {
						eventsChannel.send(LoginEvent.Error(UiText.from(R.string.error_email_password_incorrect)))
					} else {
						eventsChannel.send(LoginEvent.Error(result.error.asUiText()))
					}
				}
			}
		}
	}
}