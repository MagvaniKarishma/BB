package com.brokerbuddy.data

import com.brokerbuddy.BuildConfig
import com.brokerbuddy.core.model.ApiErrorBody
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ApiErrorDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
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
    /** The server couldn't be reached at all (wrong address, server not running, no network). */
    val unreachable: Boolean = false,
) : Exception(message) {
    val code: String? get() = error?.code
}

/**
 * Builds a Retrofit [ApiService] for the configured server, rebuilding it when
 * the server URL changes. The bearer token is read per request so sign-in and
 * sign-out take effect immediately.
 */
class ApiClient(private val sessionStore: SessionStore, val json: Json, demo: Interceptor? = null) {
    @Volatile private var cached: Pair<String, ApiService>? = null
    @Volatile var onUnauthorized: (() -> Unit)? = null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .apply { if (demo != null) addInterceptor(demo) } // first, so demo requests never reach the network
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
        Result.failure(ApiException(unreachableMessage(sessionStore.current().serverUrl), unreachable = true))
    } catch (e: SerializationException) {
        Result.failure(ApiException("Unexpected response from server: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        Result.failure(ApiException("Invalid server address: ${e.message}"))
    }

    companion object {
        fun defaultJson(): Json = ApiJson

        /** Names the address that failed; 10.0.2.2 is the emulator's alias for the computer and never works on a phone. */
        fun unreachableMessage(serverUrl: String): String {
            val address = serverUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            return if (address.startsWith("10.0.2.2")) {
                "Can't reach the server at $address. That address only works in the Android emulator. " +
                    "On a phone, enter the address of the computer running BrokerBuddy, e.g. http://192.168.1.23:4000."
            } else {
                "Can't reach the BrokerBuddy server at $address. Check that it's running, that the phone is on the same network, and the server address."
            }
        }
    }
}
