package com.example.clientera.ui.chat

/**
 * ViewModel для экрана [MainChatScreen].
 * Управляет списком сообщений чата, вводом JSON, статусом соединения.
 * Взаимодействует с [AppRepository] для получения сообщений и отправки данных.
 */

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.clientera.data.model.ChatMessage
import com.example.clientera.data.model.ServerResponse
import com.example.clientera.data.remote.WebSocketConnectionStatus
import com.example.clientera.data.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import javax.inject.Inject

/** Определяет элементы списка для UI чата: сообщение, инфо от сервера, системное уведомление */
sealed interface ChatUiListItem
/** UI-обертка для сообщения чата [ChatMessage] */
data class UiChatMessage @OptIn(InternalSerializationApi::class) constructor(val message: ChatMessage) :
    ChatUiListItem

/** UI-обертка для ответа сервера [ServerResponse] (не сообщения чата) */
data class UiServerInfoMessage @OptIn(InternalSerializationApi::class) constructor(val serverResponse: ServerResponse) :
    ChatUiListItem

/** UI-обертка для системных уведомлений */
data class UiSystemNotification @OptIn(InternalSerializationApi::class) constructor(
    val text: String,
    val isError: Boolean = false
) : ChatUiListItem

@OptIn(InternalSerializationApi::class)
@HiltViewModel
class MainChatViewModel @Inject constructor(
    private val appRepository: AppRepository
) : ViewModel() {

    /** Список элементов для отображения в чате (сообщения, уведомления) */
    val chatMessages: SnapshotStateList<ChatUiListItem> = mutableStateListOf()
    /** Текущее значение в поле ввода JSON */
    val currentJsonInput = mutableStateOf("")
    /** Текстовое представление статуса соединения для UI */
    val connectionStatus: StateFlow<String> = appRepository.connectionStatus
        .map { status ->
            when (status) {
                is WebSocketConnectionStatus.Disconnected -> "Disconnected" + (status.reason?.let { " ($it)" }
                    ?: "")
                WebSocketConnectionStatus.Connected -> "Connected"
                WebSocketConnectionStatus.Connecting -> "Connecting..."
                WebSocketConnectionStatus.FailedToConnect -> "Failed to connect"
                is WebSocketConnectionStatus.Error -> "Error: ${status.message ?: "Unknown"}"
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "Loading status"
        )

    private var messageObservationJob: Job? = null

    init {
        observeServerMessages()

        if (appRepository.connectionStatus.value !is WebSocketConnectionStatus.Connected) {
            chatMessages.add(
                UiSystemNotification(
                    "Attempting to use chat while not connected.",
                    isError = true
                )
            )
        }
    }

    /**
     * Подписывается на входящие сообщения от сервера из [AppRepository]
     * и преобразует их в [ChatUiListItem] для отображения в чате.
     */
    @OptIn(InternalSerializationApi::class)
    fun observeServerMessages() {
        messageObservationJob?.cancel()
        messageObservationJob = viewModelScope.launch {
            appRepository.incomingServerMessages.collect { serverResponse ->
                if (serverResponse.sender != null && serverResponse.content != null && serverResponse.timestamp == null) {
                    val chatMsg = ChatMessage(
                        sender = serverResponse.sender,
                        content = serverResponse.content,
                        timestamp = serverResponse.timestamp ?: System.currentTimeMillis()
                    )
                    chatMessages.add(UiChatMessage(chatMsg))
                } else if (serverResponse.type == "answer" && serverResponse.atype == 0) {
                    Log.d(
                        "MainChatVM",
                        "Received login answer on chat screen: ${serverResponse.answer}"
                    )
                } else {
                    chatMessages.add(UiServerInfoMessage(serverResponse))
                }
            }
        }
    }

    fun onJsonInputChanged(newText: String) {
        currentJsonInput.value = newText
    }

    fun sendRawJsonMessage() {
        if (currentJsonInput.value.isNotBlank()) {
            viewModelScope.launch {
                val success = appRepository.sendRawJsonMessage(currentJsonInput.value)
                if (success) {
                    chatMessages.add(UiSystemNotification("Sent JSON: ${currentJsonInput.value}"))
                    currentJsonInput.value = ""
                } else {
                    chatMessages.add(UiSystemNotification("Failed to send JSON", isError = true))
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            appRepository.disconnectFromServer()
        }
    }

    override fun onCleared() {
        super.onCleared()
        messageObservationJob?.cancel()
    }
}