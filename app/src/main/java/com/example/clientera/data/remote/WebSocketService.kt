package com.example.clientera.data.remote
/**
 * Управляет WebSocket соединением: подключение, отключение, отправка/прием сообщений.
 * Предоставляет статус соединения и входящие сообщения через Flow.
 * Используется [AppRepository] для сетевого взаимодействия
 */

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
import kotlinx.serialization.json.Json
import com.example.clientera.data.model.ServerResponse
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

    /** Текущий статус WebSocket соединения (Disconnected, Connecting, Connected, Error, FailedToConnect) */
    val connectionStatus: StateFlow<WebSocketConnectionStatus> = _connectionStatus.asStateFlow()

    @OptIn(InternalSerializationApi::class)
    private val _incomingServerResponses = MutableSharedFlow<ServerResponse>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Flow для получения десериализованных сообщений от сервера. Имеет буфер на 1 сообщение*/
    @OptIn(InternalSerializationApi::class)
    val incomingServerResponses: SharedFlow<ServerResponse> =
        _incomingServerResponses.asSharedFlow()

    /** Основная корутина для всех операций сервиса, использует SupervisorJob для изоляции дочерних ошибок */
    private var serviceJob = SupervisorJob()
    /** Scope для запуска корутин сервиса, работает на Dispatchers.IO */
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    /** Корутина, ответственная за прослушивание входящих сообщений от WebSocket */
    private var observationJob: Job? = null

    private val isConnectedOrConnecting
        get() =
            _connectionStatus.value is WebSocketConnectionStatus.Connecting ||
                    _connectionStatus.value is WebSocketConnectionStatus.Connected

    /**
     * Инициирует подключение к WebSocket серверу по указанному URL.
     * Обновляет [connectionStatus] и запускает прослушивание сообщений при успехе.
     * @param serverUrl URL WebSocket сервера.
     * @return true, если сессия успешно установлена и активна, false в противном случае.
     */
    suspend fun connect(serverUrl: String): Boolean {
        if (isConnectedOrConnecting) {
            Log.d(TAG, "WebSocket already connected or connecting to another URL.")
            return _connectionStatus.value is WebSocketConnectionStatus.Connected
        }

        _connectionStatus.value = WebSocketConnectionStatus.Connecting
        observationJob?.cancel()
        /** Основная корутина для всех операций сервиса, использует SupervisorJob для изоляции дочерних ошибок */
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

    /**
     * Запускает корутину для чтения и обработки входящих фреймов из WebSocket сессии.
     * Десериализует текстовые фреймы в [ServerResponse].
     */
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

    /**
     * Обрабатывает входящий фрейм от WebSocket.
     * Текстовые фреймы парсит как JSON в [ServerResponse] и эмитит в [incomingServerResponses].
     * Обрабатывает Close фреймы.
     */
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

    /**
     * Отправляет сериализуемый объект [data] на сервер в виде JSON.
     * @param data Объект для отправки.
     * @param serializer Сериализатор для типа [T].
     * @return true, если сообщение успешно отправлено, false в противном случае.
     */
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

    /**
     * Отправляет JSON строку на сервер.
     * @param jsonContent Строка JSON для отправки.
     * @return true, если сообщение успешно отправлено, false в противном случае.
     */
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

    /**
     * Инициирует закрытие WebSocket соединения и отмену всех активных задач.
     * Обновляет [connectionStatus] на Disconnected.
     */
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

/**
 * Определяет различные состояния WebSocket соединения
 */
sealed class WebSocketConnectionStatus {
    data class Disconnected(val reason: String? = null) : WebSocketConnectionStatus()
    object Connecting : WebSocketConnectionStatus()
    object Connected : WebSocketConnectionStatus()
    object FailedToConnect : WebSocketConnectionStatus()
    data class Error(val message: String?) : WebSocketConnectionStatus()
}