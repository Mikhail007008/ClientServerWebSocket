package com.example.clientera.ui.chat

/**
 * Основной экран чата. Отображает список сообщений и поле для отправки JSON.
 * Позволяет выйти из системы.
 * Использует [MainChatViewModel] для управления состоянием и логикой.
 */

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.clientera.AppDestinations
import com.example.clientera.data.model.ChatMessage
import com.example.clientera.data.model.ServerResponse
import kotlinx.serialization.InternalSerializationApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, InternalSerializationApi::class)
@Composable
fun MainChatScreen(
    navController: NavController,
    viewModel: MainChatViewModel = hiltViewModel()
) {
    val chatMessages = viewModel.chatMessages
    val connectionStatusText by viewModel.connectionStatus.collectAsState()
    val currentJsonInput by viewModel.currentJsonInput

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Просто экран") },
                actions = {
                    Text(
                        text = connectionStatusText,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (connectionStatusText == "Connected") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    IconButton(onClick = {
                        viewModel.disconnect()
                        navController.navigate(AppDestinations.LOGIN_ROUTE) {
                            popUpTo(AppDestinations.MAIN_CHAT_ROUTE) {
                                inclusive = true
                            }
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Logout and Disconnect"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true
            ) {
                items(
                    items = chatMessages.asReversed(),
                    key = { item ->
                        when (item) {
                            is UiChatMessage -> "chat_${item.message.timestamp}_${item.message.sender}_${item.message.content.hashCode()}"
                            is UiServerInfoMessage -> "info_${item.serverResponse.hashCode()}_${
                                System.identityHashCode(
                                    item.serverResponse
                                )
                            }"

                            is UiSystemNotification -> "sys_${item.text.hashCode()}_${
                                System.identityHashCode(
                                    item
                                )
                            }"
                        }
                    }
                ) { item ->
                    when (item) {
                        is UiChatMessage -> ChatMessageDisplayItem(chatMessage = item.message)
                        is UiServerInfoMessage -> ServerInfoDisplayItem(serverResponse = item.serverResponse)
                        is UiSystemNotification -> SystemNotificationDisplayItem(notification = item)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Отправка JSON: ", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = currentJsonInput,
                    onValueChange = { viewModel.onJsonInputChanged(it) },
                    label = { Text("Вводи JSON") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5
                )
                Spacer(modifier = Modifier.height(8.dp))
                IconButton(
                    onClick = { viewModel.sendRawJsonMessage() },
                    enabled = connectionStatusText == "Connected" && currentJsonInput.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send JSON Message"
                    )
                }
            }
        }
    }
}

/** Отображает элемент сообщения в чате (отправленное или полученное) */
@OptIn(InternalSerializationApi::class)
@Composable
fun ChatMessageDisplayItem(chatMessage: ChatMessage) {
    val isFromClient = chatMessage.sender == "AndroidClient"
    val alignment = if (isFromClient) Alignment.End else Alignment.Start

    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .align(alignment as Alignment)
                .widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isFromClient) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isFromClient) "You" else chatMessage.sender,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = chatMessage.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDisplayTimestamp(chatMessage.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

/** Отображает информационное сообщение от сервера */
@OptIn(InternalSerializationApi::class)
@Composable
fun ServerInfoDisplayItem(serverResponse: ServerResponse) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (serverResponse.status == "error" || serverResponse.success == 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Server Info:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            serverResponse.type?.let {
                Text(
                    text = "Type: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            serverResponse.success?.let {
                Text(
                    text = "Success: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            serverResponse.atype?.let {
                Text(
                    "Action Type: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            serverResponse.answer?.let {
                Text(
                    "Answer: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            serverResponse.status?.let {
                if (serverResponse.type == null) Text(
                    "Status: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            serverResponse.data?.let {
                Text(
                    "Data: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (serverResponse.sender != null && serverResponse.content != null && serverResponse.type != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Original Sender: ${serverResponse.sender}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Original Content: ${serverResponse.content}",
                    style = MaterialTheme.typography.bodySmall
                )
                serverResponse.timestamp?.let {
                    Text(
                        text = formatDisplayTimestamp(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

/** Отображает системное уведомление в UI чата (например, об ошибке отправки) */
@Composable
fun SystemNotificationDisplayItem(notification: UiSystemNotification) {
    Text(
        text = notification.text,
        color = if (notification.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
    )
}

private fun formatDisplayTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}