package com.brokerbuddy.data

import android.content.Context
import com.brokerbuddy.core.demo.DemoApi
import com.brokerbuddy.core.demo.DemoChanges
import com.brokerbuddy.core.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * While the offline demo is on, answers every API request on the phone — nothing reaches the
 * network. Reads come from the bundled sample data (assets/demo/responses.json, never modified);
 * what the broker adds or edits in the demo is saved in the app's private storage
 * (files/demo/changes.json), separate from any real account, and survives restarts until
 * [reset]. Property photos and voice-note recordings added in the demo are kept in files/demo/photos
 * and files/demo/voice. Outside the demo,
 * requests pass through untouched.
 */
class DemoInterceptor(private val context: Context, private val sessionStore: SessionStore) : Interceptor {
    private val file: File get() = File(context.filesDir, "demo/changes.json")
    private val photoDir: File get() = File(context.filesDir, "demo/photos")
    private val photos by lazy { DemoPhotos(photoDir) { api } }
    private val voiceDir: File get() = File(context.filesDir, "demo/voice")
    private val recordings by lazy { DemoVoiceNotes(voiceDir) { api } }

    private val api: DemoApi by lazy {
        val snapshot = context.assets.open("demo/responses.json").bufferedReader().use { it.readText() }
        val saved = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
        DemoApi(snapshot, LocalDate.now(ZoneId.of("Asia/Kolkata")), DemoChanges.fromJson(saved), onChange = ::save).also { api ->
            DemoPhotos(photoDir) { api }.pruneUnused()
            DemoVoiceNotes(voiceDir) { api }.pruneUnused()
        }
    }

    /** The demo broker to sign in as. */
    suspend fun user(): User = withContext(Dispatchers.IO) { api.user() }

    /** How many demo changes are saved on this phone. */
    suspend fun changeCount(): Int = withContext(Dispatchers.IO) { api.changes.count }

    /** Back to the original sample data: forgets every demo change (the sample data itself is untouched). */
    suspend fun reset() = withContext(Dispatchers.IO) {
        api.reset()
        photoDir.deleteRecursively()
        voiceDir.deleteRecursively()
    }

    @Synchronized
    private fun save(changes: DemoChanges) {
        val f = file
        f.parentFile?.mkdirs()
        if (changes.count == 0) {
            f.delete()
            return
        }
        // Write then rename, so a crash mid-write never leaves a half-written file.
        val tmp = File(f.parentFile, "changes.json.tmp")
        tmp.writeText(changes.toJson())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (runBlocking { sessionStore.current().token } != DEMO_TOKEN) return chain.proceed(request)
        val url = request.url
        val seg = url.pathSegments.dropWhile { it != "properties" }
        if (seg.size >= 3 && seg[0] == "properties" && seg[2] == "photos") photos.handle(request, seg)?.let { return it }
        val voiceSeg = url.pathSegments.dropWhile { it != "voice-notes" }
        if (voiceSeg.isNotEmpty()) recordings.handle(request, voiceSeg)?.let { return it }
        val params = (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }
        // JSON bodies only (photos and recordings are handled above).
        val body = request.body?.takeIf { it.contentType()?.subtype == "json" }?.let { b ->
            Buffer().also { b.writeTo(it) }.readUtf8()
        }
        val reply = api.handle(request.method, url.pathSegments.joinToString("/"), params, body)
        return demoResponse(request, reply)
    }
}
