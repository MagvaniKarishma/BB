package com.brokerbuddy.data

import com.brokerbuddy.core.demo.DemoApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File

/**
 * Voice-note recordings in the demo, kept in the app's private storage ([dir]) and answered like
 * the server's endpoints: upload (the note then waits for the broker to type what was said — there's
 * no speech-to-text without a server) and play back.
 */
class DemoVoiceNotes(private val dir: File, private val api: () -> DemoApi) {
    fun handle(request: Request, seg: List<String>): Response? = when {
        request.method == "POST" && seg.size == 1 && request.body is MultipartBody -> upload(request)
        request.method == "GET" && seg.size == 3 && seg[2] == "audio" -> play(request, seg[1])
        else -> null
    }

    private fun upload(request: Request): Response {
        val parts = (request.body as MultipartBody).parts.associate { part ->
            val name = part.headers?.get("Content-Disposition")?.let { Regex("name=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            name to part.body
        }
        fun text(name: String) = parts[name]?.let { Buffer().also(it::writeTo).readUtf8() }?.trim()?.ifEmpty { null }
        val audio = parts["audio"]?.let { Buffer().also(it::writeTo).readByteArray() }
        val clientId = text("clientId")
        if (audio == null || audio.isEmpty() || clientId == null) {
            return demoResponse(request, DemoApi.Reply(400, """{"error":{"code":"VALIDATION_ERROR","message":"Attach the recording","details":null}}"""))
        }
        if (audio.size > 25 * 1024 * 1024) {
            return demoResponse(request, DemoApi.Reply(413, """{"error":{"code":"TOO_LARGE","message":"Recordings can be at most 25 MB","details":null}}"""))
        }
        // File first, then the record: a failed save leaves no note without its recording.
        val tmp = File(dir, "upload-${System.nanoTime()}.tmp")
        val written = runCatching { dir.mkdirs(); tmp.writeBytes(audio); true }.getOrDefault(false)
        if (!written) {
            tmp.delete()
            return demoResponse(request, DemoApi.Reply(507, """{"error":{"code":"SAVE_FAILED","message":"Couldn't save the recording on this phone (is storage full?)","details":null}}"""))
        }
        val (reply, id) = api().addRecording(clientId, text("inquiryId"), text("language") ?: "AUTO", text("durationMs")?.toLongOrNull())
        if (id == null || !tmp.renameTo(File(dir, id))) tmp.delete()
        return demoResponse(request, reply)
    }

    private fun play(request: Request, noteId: String): Response {
        val f = File(dir, noteId).takeIf { SAFE_ID.matches(noteId) && it.exists() && api().hasRecording(noteId) }
            ?: return demoResponse(request, DemoApi.Reply(404, """{"error":{"code":"NOT_FOUND","message":"Recording not found","details":null}}"""))
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "audio/mp4")
            .body(f.readBytes().toResponseBody("audio/mp4".toMediaType())).build()
    }

    /** Removes recordings of notes that no longer exist (e.g. after the app closed mid-save). */
    fun pruneUnused() {
        val ids = api().voiceNoteIds()
        dir.listFiles()?.filter { it.name.substringBefore('.') !in ids }?.forEach { it.delete() }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
