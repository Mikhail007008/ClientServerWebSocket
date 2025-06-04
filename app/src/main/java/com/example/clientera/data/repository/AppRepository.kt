package com.example.clientera.data.repository

import com.example.clientera.data.model.AuthRequest
import com.example.clientera.data.model.ChatMessage
import com.example.clientera.data.model.ServerResponse
import com.example.clientera.data.remote.WebSocketConnectionStatus
import com.example.clientera.data.remote.WebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.InternalSerializationApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject constructor(
    private val webSocketService: WebSocketService
)  {
    val connectionStatus: StateFlow<WebSocketConnectionStatus> = webSocketService.connectionStatus
    @OptIn(InternalSerializationApi::class)
    val incomingServerMessages: SharedFlow<ServerResponse> = webSocketService.incomingServerResponses

    suspend fun connectToServer(serverUrl: String): Boolean {
        return webSocketService.connect(serverUrl)
    }

    suspend fun sendAuthenticationRequest(authRequest: AuthRequest): Boolean {
        return webSocketService.sendSerializable(authRequest, AuthRequest.serializer())
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun sendChatMessage(chatMessage: ChatMessage): Boolean {
        return webSocketService.sendSerializable(chatMessage, ChatMessage.serializer())
    }

    suspend fun sendRawJsonMessage(rawJson: String): Boolean {
        return webSocketService.sendRawJson(rawJson)
    }
//
//    @OptIn(InternalSerializationApi::class)
//    fun getIncomingMessages(): Flow<ServerResponse>? {
//        return webSocketService.observeIncomingMessages()
//    }

    suspend fun disconnectFromServer() {
        webSocketService.disconnect()
    }
}