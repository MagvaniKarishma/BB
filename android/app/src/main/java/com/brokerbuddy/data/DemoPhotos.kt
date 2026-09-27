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
import java.util.UUID

/** A demo answer as an HTTP response (never from the network). */
internal fun demoResponse(request: Request, reply: DemoApi.Reply): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(reply.status)
    .message(if (reply.status in 200..299) "OK" else "Demo")
    .body(reply.body.toResponseBody("application/json".toMediaType()))
    .build()

/**
 * Property photos in the demo, kept in the app's private storage ([dir]) and answered like the
 * server's photo endpoints: upload (JPEG/PNG/WebP, at most 5 MB), download, remove.
 */
class DemoPhotos(private val dir: File, private val api: () -> DemoApi) {
    /** Property photos in the demo: stored on the phone, like the server stores them. */
    fun handle(request: Request, seg: List<String>): Response? {
        val propertyId = seg[1]
        val photoId = seg.getOrNull(3)?.takeIf { SAFE_ID.matches(it) }
        return when {
            request.method == "POST" && seg.size == 3 -> {
                val part = (request.body as? MultipartBody)?.parts?.firstOrNull()?.body
                val bytes = part?.let { Buffer().also(it::writeTo).readByteArray() }
                    ?: return demoResponse(request, demoError(400, "VALIDATION_ERROR", "Attach the photo as the 'photo' field"))
                val type = imageType(bytes)
                    ?: return demoResponse(request, demoError(415, "UNSUPPORTED_IMAGE", "Photos must be JPEG, PNG or WebP images"))
                if (bytes.size > 5 * 1024 * 1024) return demoResponse(request, demoError(413, "TOO_LARGE", "Photos can be at most 5 MB"))
                val id = "demophoto" + UUID.randomUUID().toString().replace("-", "")
                val f = File(dir, id)
                // File first, then the record: a failed save leaves no record pointing at nothing.
                val saved = runCatching {
                    dir.mkdirs()
                    File(dir, "$id.tmp").apply { writeBytes(bytes) }.renameTo(f)
                }.getOrDefault(false)
                if (!saved) return demoResponse(request, demoError(507, "SAVE_FAILED", "Couldn't save the photo on this phone (is storage full?)"))
                runCatching { File(dir, "$id.type").writeText(type) } // the type is only a hint when serving
                val r = api().addPhoto(propertyId, id)
                if (r.status !in 200..299) {
                    f.delete()
                    File(dir, "$id.type").delete()
                }
                demoResponse(request, r)
            }
            request.method == "GET" && photoId != null -> {
                val f = File(dir, photoId)
                if (!api().hasPhoto(propertyId, photoId) || !f.exists()) {
                    return demoResponse(request, demoError(404, "NOT_FOUND", "Photo not found"))
                }
                val type = runCatching { File(dir, "$photoId.type").readText() }.getOrDefault("image/jpeg")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", type)
                    .body(f.readBytes().toResponseBody(type.toMediaType())).build()
            }
            request.method == "DELETE" && photoId != null -> {
                val r = api().handle("DELETE", seg.joinToString("/"), emptyList(), null)
                if (r.status in 200..299) {
                    File(dir, photoId).delete()
                    File(dir, "$photoId.type").delete()
                }
                demoResponse(request, r)
            }
            else -> null
        }
    }

    /** Removes photo files no property refers to any more (e.g. the app closed mid-upload). */
    fun pruneUnused() {
        val inUse = api().photoIdsInUse()
        dir.listFiles()?.filter { it.name.substringBefore('.') !in inUse }?.forEach { it.delete() }
    }

    private fun demoError(status: Int, code: String, message: String) =
        DemoApi.Reply(status, """{"error":{"code":"$code","message":"$message","details":null}}""")

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun imageType(b: ByteArray): String? = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }
}
