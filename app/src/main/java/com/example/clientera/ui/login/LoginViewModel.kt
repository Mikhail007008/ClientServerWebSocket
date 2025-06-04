package com.example.clientera.ui.login

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.clientera.data.model.AuthRequest
import com.example.clientera.data.remote.WebSocketConnectionStatus
import com.example.clientera.data.repository.AppRepository
import com.example.clientera.util.toMD5
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import javax.inject.Inject

sealed class LoginEvent {
    object NavigateToMainChat : LoginEvent()
}

sealed class LoginState {
    object Idle : LoginState()
    object Connecting : LoginState()
    object Authenticating : LoginState()
    data class ConnectionFailed(val message: String) : LoginState()
    data class AuthenticationFailed(val message: String) : LoginState()
    object AuthenticationSuccess : LoginState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val appRepository: AppRepository
) : ViewModel() {
    val serverAddress = mutableStateOf("192.168.0.35")
    val serverPort = mutableStateOf("7712")
    val username = mutableStateOf("1")
    val password = mutableStateOf("1")
    val passwordVisible = mutableStateOf(false)

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _loginEvent = MutableSharedFlow<LoginEvent>()
    val loginEvent: SharedFlow<LoginEvent> = _loginEvent.asSharedFlow()

    private var authJob: Job? = null
    private var connectionStatusJob: Job? = null

    @OptIn(InternalSerializationApi::class)
    fun onLoginClicked() {
        authJob?.cancel()
        connectionStatusJob?.cancel()

        val address = serverAddress.value.trim()
        val port = serverPort.value.trim()
        val user = username.value.trim()
        val pass = password.value.trim()

        if (address.isEmpty() || port.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            _loginState.value = LoginState.ConnectionFailed("Пожалуйста, заполните все поля")
            return
        }

        val serverUrl = "ws://$address:$port"
        val md5Password = pass.toMD5()

        _loginState.value = LoginState.Connecting

        authJob = viewModelScope.launch {
            val connected = appRepository.connectToServer(serverUrl)

            if (!connected && appRepository.connectionStatus.value !is WebSocketConnectionStatus.Connected) {
                kotlinx.coroutines.delay(500)
            }

            connectionStatusJob = launch {
                appRepository.connectionStatus.collectLatest { status ->
                    when (status) {
                        is WebSocketConnectionStatus.Connected -> {
                            if (_loginState.value == LoginState.Connecting || _loginState.value is LoginState.ConnectionFailed) {
                                _loginState.value = LoginState.Authenticating
                                Log.d("LoginVM", "Connected. Sending auth request...")

                                val authRequest = AuthRequest(user = user, password = md5Password)
                                val sent = appRepository.sendAuthenticationRequest(authRequest)

                                if (!sent) {
                                    _loginState.value =
                                        LoginState.AuthenticationFailed("Failed to send auth request")
                                    appRepository.disconnectFromServer()
                                }
                            }
                        }

                        is WebSocketConnectionStatus.FailedToConnect -> {
                            if (_loginState.value !is LoginState.AuthenticationFailed) {
                                _loginState.value = LoginState.ConnectionFailed("Connection failed")
                            }
                            connectionStatusJob?.cancel()
                        }

                        is WebSocketConnectionStatus.Error -> {
                            if (_loginState.value !is LoginState.AuthenticationFailed && _loginState.value !is LoginState.AuthenticationSuccess) {
                                _loginState.value =
                                    LoginState.ConnectionFailed("Connection error: ${status.message}")
                            }
                        }

                        is WebSocketConnectionStatus.Disconnected -> {
                            if (_loginState.value !is LoginState.Connecting && _loginState.value == LoginState.Authenticating) {
                                _loginState.value =
                                    LoginState.ConnectionFailed("Disconnected during login process. ${status.reason}")
                            }
                        }

                        else -> {}
                    }
                }
            }

            appRepository.incomingServerMessages
                .filter { it.type == "answer" }
                .collectLatest { serverResponse ->
                    if (_loginState.value == LoginState.Authenticating) {
                        if (serverResponse.success == 1) {
                            Log.d("LoginVVM", "Authentication successful: ${serverResponse.answer}")
                            _loginState.value = LoginState.AuthenticationSuccess
                            _loginEvent.emit(LoginEvent.NavigateToMainChat)
                            authJob?.cancel()
                            connectionStatusJob?.cancel()
                        } else {
                            Log.d("LoginVM", "Authentication failed: ${serverResponse.answer}")
                            _loginState.value =
                                LoginState.AuthenticationFailed("Authentication failed: ${serverResponse.answer}")
//                            appRepository.disconnectFromServer()
                            authJob?.cancel()
                            connectionStatusJob?.cancel()
                        }
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        authJob?.cancel()
        connectionStatusJob?.cancel()
    }
}