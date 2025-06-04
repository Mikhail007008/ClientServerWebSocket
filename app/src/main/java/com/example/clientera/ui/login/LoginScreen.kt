package com.example.clientera.ui.login

/**
 * Экран авторизации пользователя. Позволяет ввести данные для подключения и входа.
 * Использует [LoginViewModel] для управления состоянием и логикой входа.
 */

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.clientera.AppDestinations
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val serverAddress by viewModel.serverAddress
    val serverPort by viewModel.serverPort
    val username by viewModel.username
    val password by viewModel.password
    val passwordVisible by viewModel.passwordVisible
    val loginState by viewModel.loginState.collectAsState()

    LaunchedEffect(key1 = Unit) {
        viewModel.loginEvent.collectLatest { event ->
            when (event) {
                is LoginEvent.NavigateToMainChat -> {
                    navController.navigate(AppDestinations.MAIN_CHAT_ROUTE) {
                        popUpTo(AppDestinations.LOGIN_ROUTE) {
                            inclusive = true
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Контроль Доступа") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Авторизация", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = serverAddress,
                onValueChange = { viewModel.serverAddress.value = it },
                label = { Text("Адрес сервера") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = serverPort,
                onValueChange = { viewModel.serverPort.value = it },
                label = { Text("Порт сервера") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { viewModel.username.value = it },
                label = { Text("Пользователь") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.password.value = it },
                label = { Text("Пароль") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val image = if (passwordVisible)
                        Icons.Filled.Visibility
                    else Icons.Filled.VisibilityOff

                    val description = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                    IconButton(onClick = { viewModel.passwordVisible.value = !passwordVisible }) {
                        Icon(imageVector = image, description)
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))

            val isLoading =
                loginState is LoginState.Connecting || loginState is LoginState.Authenticating
            Button(
                onClick = { viewModel.onLoginClicked() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    when (loginState) {
                        is LoginState.Connecting -> "Соединение..."
                        is LoginState.Authenticating -> "Аутентификация..."
                        else -> "Войти"
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = loginState) {
                is LoginState.ConnectionFailed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }

                is LoginState.AuthenticationFailed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }

                is LoginState.AuthenticationSuccess -> {
                    Text("Аутентификация успешна!", color = MaterialTheme.colorScheme.primary)
                }

                else -> {}
            }
        }
    }
}