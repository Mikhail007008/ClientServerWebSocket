package com.example.clientera.data.repository

/**
 * Репозиторий для взаимодействия с сетью.
 * Абстрагирует работу с [WebSocketService] от ViewModel.
 * Предоставляет методы для подключения, отправки данных и получения статусов/сообщений.
 */

import com.example.clientera.data.model.AuthRequest
import com.example.clientera.data.model.ChatMessage
import com.example.clientera.data.model.ServerResponse
import com.example.clientera.data.remote.WebSocketConnectionStatus
import com.example.clientera.data.remote.WebSocketService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.InternalSerializationApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject constructor(
    private val webSocketService: WebSocketService
)  {

    /** Поток текущего статуса WebSocket соединения из [WebSocketService] */
    val connectionStatus: StateFlow<WebSocketConnectionStatus> = webSocketService.connectionStatus
    /** Поток входящих сообщений от сервера из [WebSocketService] */
    @OptIn(InternalSerializationApi::class)
    val incomingServerMessages: SharedFlow<ServerResponse> = webSocketService.incomingServerResponses

    /**
     * Делегирует подключение к WebSocket серверу через [WebSocketService]
     */
    suspend fun connectToServer(serverUrl: String): Boolean {
        return webSocketService.connect(serverUrl)
    }

    /**
     * Отправляет запрос на аутентификацию на сервер
     */
    suspend fun sendAuthenticationRequest(authRequest: AuthRequest): Boolean {
        return webSocketService.sendSerializable(authRequest, AuthRequest.serializer())
    }

    /**
     * Отправляет сообщение чата на сервер
     */
    @OptIn(InternalSerializationApi::class)
    suspend fun sendChatMessage(chatMessage: ChatMessage): Boolean {
        return webSocketService.sendSerializable(chatMessage, ChatMessage.serializer())
    }

    /**
     * Отправляет JSON строку на сервер
     */
    suspend fun sendRawJsonMessage(rawJson: String): Boolean {
        return webSocketService.sendRawJson(rawJson)
    }

    /**
     * Инициирует разрыв WebSocket соединения
     */
    suspend fun disconnectFromServer() {
        webSocketService.disconnect()
    }
}