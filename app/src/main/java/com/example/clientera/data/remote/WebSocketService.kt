package com.example.clientera.data.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json // Прямой импорт
import com.example.clientera.data.model.ServerResponse // Ваша модель
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.InternalSerializationApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketService @Inject constructor(
    private val httpClient: HttpClient,
    private val appJson: Json
) {

    private var session: DefaultClientWebSocketSession? = null
    private val _connectionStatus =
        MutableStateFlow<WebSocketConnectionStatus>(WebSocketConnectionStatus.Disconnected())
    val connectionStatus: StateFlow<WebSocketConnectionStatus> = _connectionStatus.asStateFlow()

    @OptIn(InternalSerializationApi::class)
    private val _incomingServerResponses = MutableSharedFlow<ServerResponse>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @OptIn(InternalSerializationApi::class)
    val incomingServerResponses: SharedFlow<ServerResponse> =
        _incomingServerResponses.asSharedFlow()

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var observationJob: Job? = null

    private val isConnectedOrConnecting
        get() =
            _connectionStatus.value is WebSocketConnectionStatus.Connecting ||
                    _connectionStatus.value is WebSocketConnectionStatus.Connected

//    private val isDisconnectedState
//        get() =
//            _connectionStatus.value is WebSocketConnectionStatus.Disconnected ||
//                    _connectionStatus.value == WebSocketConnectionStatus.FailedToConnect

    suspend fun connect(serverUrl: String): Boolean {
        if (isConnectedOrConnecting) {
            Log.d(TAG, "WebSocket already connected or connecting to another URL.")
            return _connectionStatus.value is WebSocketConnectionStatus.Connected
        }

        _connectionStatus.value = WebSocketConnectionStatus.Connecting
        observationJob?.cancel()
        serviceJob.cancelChildren()
        serviceJob = SupervisorJob()
        serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

        return try {
            Log.d(TAG, "Attempting to connect to $serverUrl")
            val currentSession = httpClient.webSocketSession(serverUrl)
            session = currentSession
            if (currentSession.isActive) {
                _connectionStatus.value = WebSocketConnectionStatus.Connected
                Log.d(TAG, "WebSocket connected successfully to $serverUrl")
                startObservingIncomingMessages(currentSession)
                true
            } else {
                handleConnectionFailure(
                    "Session is not active immediately after connection.",
                    currentSession
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection attempt failed for $serverUrl: ${e.message}", e)
            handleConnectionFailure("Connection attempt failed: ${e.message}", null)
            false
        }
    }

    private fun startObservingIncomingMessages(currentSession: DefaultClientWebSocketSession) {
        Log.d(TAG, "Starting to observe incoming messages for session: $currentSession")
        observationJob = serviceScope.launch {
            try {
                currentSession.incoming.consumeAsFlow().collect { frame ->
                    handleIncomingFrame(frame)
                }
                updateStatusIfConnected(WebSocketConnectionStatus.Disconnected("Incoming channel closed by peer"))
            } catch (e: ClosedReceiveChannelException) {
                Log.d(TAG, "WebSocket incoming channel closed: ${e.message}")
                updateStatusIfConnected(WebSocketConnectionStatus.Disconnected("Connection lost: ${e.message}"))
            } catch (e: Exception) {
                Log.i(TAG, "Error in incoming messages observation: ${e.message}")
                updateStatusIfConnected(WebSocketConnectionStatus.Error("Receive Error: ${e.message}"))
            } finally {
                checkSessionStateInFinally()
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun handleIncomingFrame(frame: Frame) {
        when (frame) {
            is Frame.Text -> {
                val jsonText = frame.readText()
                Log.d(TAG, "Message received: $jsonText")
                try {
                    val serverResponse = appJson.decodeFromString<ServerResponse>(jsonText)
                    Log.d(TAG, "Parsed server response: $serverResponse")
                    val emitted = _incomingServerResponses.tryEmit(serverResponse)

                    if (!emitted) {
                        Log.w(TAG, "Failed to emit server response to flow: $serverResponse")
                    }
                } catch (e: kotlinx.serialization.SerializationException) {
                    Log.e(
                        TAG,
                        "Error parsing JSON in handleIncomingFrame: ${e.message}. JSON: $jsonText",
                        e
                    )
                    _incomingServerResponses.tryEmit(
                        ServerResponse(
                            status = "parse_error",
                            data = "Failed to parse server message: ${e.message}. Original: $jsonText"
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in handleIncomingFrame: ${e.message}", e)
                }
            }

            is Frame.Close -> {
                Log.d(TAG, "Received Close frame from server: ${frame.readReason()}")
                updateStatusIfConnected(
                    WebSocketConnectionStatus.Disconnected(
                        "Server closed: ${
                            frame.readReason()?.toString()
                        }"
                    )
                )
                serviceScope.launch { session?.close() }
            }

            else -> Log.d(TAG, "Received other frame type: ${frame.frameType.name}")
        }
    }

    suspend fun <T : Any> sendSerializable(data: T, serializer: KSerializer<T>): Boolean {
        val currentSession = session
        if (currentSession == null || !currentSession.isActive) {
            Log.w(TAG, "Cannot send serializable, session is null or not active")
            if (_connectionStatus.value is WebSocketConnectionStatus.Connected) {
                _connectionStatus.value =
                    WebSocketConnectionStatus.Error("Attempted to send on inactive session")
            }
            return false
        }
        return try {
            val jsonMessage = appJson.encodeToString(serializer, data)
            currentSession.send(Frame.Text(jsonMessage))
            Log.d(TAG, "Serializable message sent: $jsonMessage")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending serializable message: ${e.message}", e)
            if (_connectionStatus.value is WebSocketConnectionStatus.Connected) {
                _connectionStatus.value =
                    WebSocketConnectionStatus.Error("Send failed: ${e.message}")
            }
            false
        }
    }

    suspend fun sendRawJson(jsonContent: String): Boolean {
        val currentSession = session

        if (currentSession == null || !currentSession.isActive) {
            Log.w(TAG, "Cannot send raw JSON, session is null or not active")
            return false
        }

        return try {
            currentSession.send(Frame.Text(jsonContent))
            Log.d(TAG, "Raw JSON message sent: $jsonContent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending raw JSON message: ${e.message}", e)
            false
        }
    }

    private suspend fun handleConnectionFailure(
        reason: String,
        session: DefaultClientWebSocketSession?,
        exception: Exception? = null
    ) {
        _connectionStatus.value = WebSocketConnectionStatus.FailedToConnect
        if (exception != null) {
            Log.e(TAG, "Error connecting to WebSocket: $reason", exception)
        } else {
            Log.e(TAG, "Failed to connect to WebSocket: $reason")
        }
        session?.close()
        this.session = null
    }

    private fun updateStatusIfConnected(newStatus: WebSocketConnectionStatus) {
        if (_connectionStatus.value == WebSocketConnectionStatus.Connected) {
            _connectionStatus.value = newStatus
            Log.d(TAG, "Status updated to: $newStatus because previous was Connected")
        }
    }

    private fun checkSessionStateInFinally() {
        Log.d(
            TAG,
            "Incoming messages observation finished. Final status: ${_connectionStatus.value}"
        )
        val s = session
        if (_connectionStatus.value == WebSocketConnectionStatus.Connected && (s == null || !s.isActive)) {
            Log.w(TAG, "Status was Connected but session is inactive. Updating to Disconnected.")
            _connectionStatus.value =
                WebSocketConnectionStatus.Disconnected("Session found inactive in finally")
        }
    }

//    @OptIn(InternalSerializationApi::class)
//    suspend fun sendMessage(message: Message) {
//        val currentSession = session ?: run {
//            Log.w(TAG, "Cannot send message, session is null.")
//            updateStatusIfConnected(WebSocketConnectionStatus.Disconnected("Attempted to send on null session"))
//            return
//        }
//
//        if (!currentSession.isActive) {
//            Log.w(TAG, "WebSocket session is not active, cannot send message.")
//            updateStatusIfConnected(WebSocketConnectionStatus.Disconnected("Tried to send on inactive session"))
//            return
//        }
//
//        runCatching {
//            val jsonMessage = Json.encodeToString(message)
//            currentSession.send(Frame.Text(jsonMessage))
//            Log.d(TAG, "Message sent: $jsonMessage")
//        }.onFailure { e ->
//            Log.e(TAG, "Error sending message: ${e.message}", e)
//            val newStatus = if (currentSession.isActive) {
//                WebSocketConnectionStatus.Error("Send failed: ${e.message}")
//            } else {
//                WebSocketConnectionStatus.Disconnected("Send failed, session became inactive: ${e.message}")
//            }
//            _connectionStatus.value = newStatus
//        }
//    }

//    @OptIn(InternalSerializationApi::class)
//    fun observeIncomingMessages(): Flow<ServerResponse>? {
//        val currentSession = session
//        if (currentSession == null || !currentSession.isActive) {
//            Log.w(TAG, "Cannot observe messages, session is null or not active")
//            return null
//        }
//
//        return currentSession.incoming
//            .consumeAsFlow()
//            .filterIsInstance<Frame.Text>()
//            .map { frame ->
//                val jsonText = frame.readText()
//                runCatching {
//                    Json.decodeFromString<ServerResponse>(jsonText)
//                }.getOrElse { e ->
//                    Log.e(TAG, "Error parsing JSON: ${e.message}", e)
//                    ServerResponse("error", "Failed to parse server message: ${e.message}")
//                }
//            }
//            .catch { e ->
//                Log.e(TAG, "Error in public observeIncomingMessages flow: ${e.message}", e)
//                val newStatus = if (e is ClosedReceiveChannelException) {
//                    WebSocketConnectionStatus.Disconnected("Channel closed during observation")
//                } else {
//                    WebSocketConnectionStatus.Error("Error observing messages: ${e.message}")
//                }
//                updateStatusIfConnected(newStatus)
//            }
//            .onCompletion { cause ->
//                Log.d(TAG, "Public observeIncomingMessages flow completed. Cause: $cause")
//                if (cause == null &&
//                    _connectionStatus.value == WebSocketConnectionStatus.Connected &&
//                    (session == null || session?.isActive == false)
//                ) {
//                    Log.w(
//                        TAG,
//                        "Public flow completed, session inactive, but status was Connected. Updating."
//                    )
//                    _connectionStatus.value =
//                        WebSocketConnectionStatus.Disconnected("Observation flow completed, session inactive")
//                }
//            }
//    }

//    fun isConnected(): Boolean {
//        val active = session?.isActive == true
//        if (!active) {
//            updateStatusIfConnected(WebSocketConnectionStatus.Disconnected("isConnected check found inactive session"))
//        }
//        return active
//    }

    suspend fun disconnect() {
        Log.d(TAG, "Disconnect called. Current status: ${_connectionStatus.value}")

        if (_connectionStatus.value is WebSocketConnectionStatus.Disconnected && session == null) {
            Log.d(TAG, "Already disconnected or failed to connect. Skipping disconnect steps.")
            return
        }

        observationJob?.cancel()
        val currentSession = session
        session = null
        _connectionStatus.value =
            WebSocketConnectionStatus.Disconnected("Disconnect initiated by client")
        serviceJob.cancelChildren()

        runCatching {
            currentSession?.close()
            Log.d(TAG, "WebSocket session close requested.")
        }.onFailure { e ->
            Log.e(TAG, "Error closing WebSocket session: ${e.message}", e)
        }

        Log.d(TAG, "WebSocket disconnected by client. Final status: ${_connectionStatus.value}")
    }

    companion object {
        private const val TAG = "WebSocketService"
    }
}

sealed class WebSocketConnectionStatus {
    data class Disconnected(val reason: String? = null) : WebSocketConnectionStatus()
    object Connecting : WebSocketConnectionStatus()
    object Connected : WebSocketConnectionStatus()
    object FailedToConnect : WebSocketConnectionStatus()
    data class Error(val message: String?) : WebSocketConnectionStatus()
}