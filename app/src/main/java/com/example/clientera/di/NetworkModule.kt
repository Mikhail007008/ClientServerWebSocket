package com.example.clientera.di

/**
 * Dagger Hilt модуль для предоставления зависимостей, связанных с сетью.
 * Предоставляет HttpClient для Ktor и настроенный Json для сериализации.
 */

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Предоставляет сконфигурированный экземпляр kotlinx.serialization.Json */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideAppJson(): Json {
        return Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /** Предоставляет сконфигурированный Ktor HttpClient с поддержкой WebSockets и логированием */
    @Provides
    @Singleton
    fun provideHttpClient(appJson: Json): HttpClient {
        return HttpClient(CIO) {
            install(WebSockets)
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Log.i("KtorHttpClient", message)
                    }
                }
                level = LogLevel.ALL
            }
            install(ContentNegotiation) {
                json(appJson)
            }
        }
    }
}