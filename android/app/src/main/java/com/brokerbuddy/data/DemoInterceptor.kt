package com.brokerbuddy.data

import android.content.Context
import com.brokerbuddy.core.demo.DemoApi
import com.brokerbuddy.core.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.LocalDate
import java.time.ZoneId

/**
 * While the offline demo is on, answers every API request on the phone from the bundled sample
 * data (assets/demo/responses.json) instead of the network. Otherwise passes requests through.
 */
class DemoInterceptor(private val context: Context, private val sessionStore: SessionStore) : Interceptor {
    private val api: DemoApi by lazy {
        val snapshot = context.assets.open("demo/responses.json").bufferedReader().use { it.readText() }
        DemoApi(snapshot, LocalDate.now(ZoneId.of("Asia/Kolkata")))
    }

    /** The demo broker to sign in as. */
    suspend fun user(): User = withContext(Dispatchers.IO) { api.user() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (runBlocking { sessionStore.current().token } != DEMO_TOKEN) return chain.proceed(request)
        val url = request.url
        val params = (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }
        val reply = api.handle(request.method, url.pathSegments.joinToString("/"), params)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.status)
            .message(if (reply.status == 200) "OK" else "Demo")
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
