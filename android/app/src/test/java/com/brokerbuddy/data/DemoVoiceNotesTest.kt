package com.brokerbuddy.data

import com.brokerbuddy.core.demo.DemoApi
import com.brokerbuddy.core.demo.DemoChanges
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ClientList
import com.brokerbuddy.core.model.VoiceNoteList
import com.brokerbuddy.core.model.VoiceNoteResponse
import com.brokerbuddy.core.model.VoiceNoteStatus
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Demo voice-note recordings: saved on the phone, played back, kept across restarts, cleaned up. */
class DemoVoiceNotesTest {
    private val snapshot = File("src/main/assets/demo/responses.json").readText()
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    private val dir = Files.createTempDirectory("demo-voice").toFile()
    private var saved: String? = null
    private val base = "http://demo.invalid/api/v1"
    private val audio = ByteArray(4000) { (it % 251).toByte() }

    private fun phone(): Pair<DemoApi, OkHttpClient> {
        val api = DemoApi(snapshot, today, DemoChanges.fromJson(saved), onChange = { saved = it.toJson() })
        val voice = DemoVoiceNotes(dir) { api }
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            voice.handle(req, req.url.pathSegments.dropWhile { it != "voice-notes" }) ?: error("not handled: ${req.url}")
        }).build()
        return api to client
    }

    @Test
    fun recordSavePlayRestart() {
        val (api, client) = phone()
        val clientId = ApiJson.decodeFromString(ClientList.serializer(), api.handle("GET", "/api/v1/clients", listOf("group" to "all")).body).clients.first().id
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("clientId", clientId)
            .addFormDataPart("language", "HINGLISH")
            .addFormDataPart("durationMs", "8000")
            .addFormDataPart("audio", "note.m4a", audio.toRequestBody("audio/mp4".toMediaType()))
            .build()
        val note = client.newCall(Request.Builder().url("$base/voice-notes").post(body).build()).execute().use { r ->
            assertEquals(201, r.code)
            ApiJson.decodeFromString(VoiceNoteResponse.serializer(), r.body!!.string()).voiceNote
        }
        assertEquals(VoiceNoteStatus.NEEDS_TRANSCRIPT, note.status)
        assertTrue(note.hasAudio)
        client.newCall(Request.Builder().url("$base/voice-notes/${note.id}/audio").build()).execute().use { r ->
            assertEquals(200, r.code)
            assertContentEquals(audio, r.body!!.bytes())
        }

        // Restart: listed on the client's profile, still playable.
        val (api2, client2) = phone()
        val list = ApiJson.decodeFromString(VoiceNoteList.serializer(), api2.handle("GET", "/api/v1/voice-notes", listOf("clientId" to clientId)).body)
        assertEquals(note.id, list.voiceNotes.first().id)
        client2.newCall(Request.Builder().url("$base/voice-notes/${note.id}/audio").build()).execute().use { assertEquals(200, it.code) }

        // Reset forgets the note; its file is then cleaned up.
        api2.reset()
        DemoVoiceNotes(dir) { api2 }.pruneUnused()
        assertFalse(File(dir, note.id).exists())
    }

    @Test
    fun unknownClientLeavesNoFile() {
        val (_, client) = phone()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("clientId", "nobody").addFormDataPart("language", "AUTO")
            .addFormDataPart("audio", "note.m4a", audio.toRequestBody("audio/mp4".toMediaType())).build()
        client.newCall(Request.Builder().url("$base/voice-notes").post(body).build()).execute().use { assertEquals(404, it.code) }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
