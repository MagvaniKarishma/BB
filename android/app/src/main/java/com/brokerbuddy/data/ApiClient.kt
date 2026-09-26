package com.brokerbuddy.data

import com.brokerbuddy.BuildConfig
import com.brokerbuddy.core.model.ApiErrorBody
import com.brokerbuddy.core.model.ApiErrorDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An API failure with a human-readable message and the backend's error code, if any. */
class ApiException(
    message: String,
    val httpStatus: Int? = null,
    val error: ApiErrorDetail? = null,
) : Exception(message) {
    val code: String? get() = error?.code
}

/**
 * Builds a Retrofit [ApiService] for the configured server, rebuilding it when
 * the server URL changes. The bearer token is read per request so sign-in and
 * sign-out take effect immediately.
 */
class ApiClient(private val sessionStore: SessionStore, val json: Json) {
    @Volatile private var cached: Pair<String, ApiService>? = null
    @Volatile var onUnauthorized: (() -> Unit)? = null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Voice notes are transcribed and analysed synchronously; allow for that.
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = runBlocking { sessionStore.current().token }
            val request = chain.request().newBuilder().apply {
                if (token != null) header("Authorization", "Bearer $token")
            }.build()
            val response = chain.proceed(request)
            if (response.code == 401 && token != null) onUnauthorized?.invoke()
            response
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
        }
        .build()

    suspend fun service(): ApiService {
        val base = sessionStore.current().serverUrl + "api/v1/"
        cached?.let { (url, svc) -> if (url == base) return svc }
        val svc = Retrofit.Builder()
            .baseUrl(base)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
        cached = base to svc
        return svc
    }

    /** Runs an API call, mapping transport and HTTP errors to [ApiException]. */
    suspend fun <T> call(block: suspend ApiService.() -> T): Result<T> = try {
        Result.success(service().block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        val detail = e.response()?.errorBody()?.string()?.let { body ->
            runCatching { json.decodeFromString(ApiErrorBody.serializer(), body).error }.getOrNull()
        }
        Result.failure(ApiException(detail?.message ?: "Server error (${e.code()})", e.code(), detail))
    } catch (e: IOException) {
        Result.failure(ApiException("Can't reach the BrokerBuddy server. Check your connection and server address."))
    } catch (e: SerializationException) {
        Result.failure(ApiException("Unexpected response from server: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        Result.failure(ApiException("Invalid server address: ${e.message}"))
    }

    companion object {
        fun defaultJson() = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
            coerceInputValues = true
        }
    }
}
