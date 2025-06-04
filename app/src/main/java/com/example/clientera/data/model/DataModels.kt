package com.example.clientera.data.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(
    val command: String = "login",
    val user: String,
    val password: String
)

@InternalSerializationApi @Serializable
data class ChatMessage(
    val sender: String,
    val content: String,
    val timestamp: Long
)

@InternalSerializationApi @Serializable
data class ServerResponse(
    val type: String? = null,
    val success: Int? = null,
    val atype: Int? = null,
    val answer: String? = null,

    val status: String? = null,
    val data: String? = null,

    val sender: String? = null,
    val content: String? = null,
    val timestamp: Long? = null
)

@Serializable
data class RawJsonMessage(
    val jsonContent: String
)