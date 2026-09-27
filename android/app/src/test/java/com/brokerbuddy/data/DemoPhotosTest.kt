package com.brokerbuddy.data

import com.brokerbuddy.core.demo.DemoApi
import com.brokerbuddy.core.demo.DemoChanges
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.PropertyEnvelope
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

/** Demo property photos: saved on the phone, served back, replaced, removed, kept across restarts. */
class DemoPhotosTest {
    private val snapshot = File("src/main/assets/demo/responses.json").readText()
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    private val dir = Files.createTempDirectory("demo-photos").toFile()
    private var saved: String? = null

    private fun phone(): Pair<DemoApi, OkHttpClient> {
        val api = DemoApi(snapshot, today, DemoChanges.fromJson(saved), onChange = { saved = it.toJson() })
        val photos = DemoPhotos(dir) { api }
        // Everything is answered here: a request that got past this would fail (no such host).
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val seg = req.url.pathSegments.dropWhile { it != "properties" }
            photos.handle(req, seg) ?: error("not a photo request: ${req.url}")
        }).build()
        return api to client
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200) { it.toByte() }
    private val base = "http://demo.invalid/api/v1"

    private fun OkHttpClient.upload(propertyId: String, bytes: ByteArray) = newCall(
        Request.Builder().url("$base/properties/$propertyId/photos").post(
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("photo", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType())).build(),
        ).build(),
    ).execute()

    private fun OkHttpClient.get(path: String) = newCall(Request.Builder().url("$base/$path").build()).execute()
    private fun OkHttpClient.delete(path: String) = newCall(Request.Builder().url("$base/$path").delete().build()).execute()

    @Test
    fun addViewReplaceRemoveAndRestart() {
        val (api, client) = phone()
        val propertyId = ApiJson.parseToJsonElement(api.handle("GET", "/api/v1/properties", emptyList()).body)
            .let { (it as kotlinx.serialization.json.JsonObject)["properties"] as kotlinx.serialization.json.JsonArray }
            .first().let { (it as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"') }

        // Add two photos.
        val first = client.upload(propertyId, jpeg).use { r ->
            assertEquals(201, r.code)
            ApiJson.decodeFromString(PropertyEnvelope.serializer(), r.body!!.string()).property.photoIds.last()
        }
        val second = client.upload(propertyId, jpeg.copyOf().also { it[10] = 7 }).use { r ->
            ApiJson.decodeFromString(PropertyEnvelope.serializer(), r.body!!.string()).property.photoIds.last()
        }
        // View: the same bytes come back.
        client.get("properties/$propertyId/photos/$first").use { r ->
            assertEquals(200, r.code)
            assertEquals("image/jpeg", r.header("Content-Type"))
            assertContentEquals(jpeg, r.body!!.bytes())
        }
        // Not an image, or someone else's photo id: refused.
        client.upload(propertyId, "hello".toByteArray()).use { assertEquals(415, it.code) }
        client.get("properties/$propertyId/photos/nope").use { assertEquals(404, it.code) }

        // Restart: photos are still there, in order.
        val (api2, client2) = phone()
        val property = ApiJson.decodeFromString(PropertyEnvelope.serializer(), api2.handle("GET", "/api/v1/properties/$propertyId", emptyList()).body).property
        assertEquals(listOf(first, second), property.photoIds.takeLast(2))
        client2.get("properties/$propertyId/photos/$second").use { assertEquals(200, it.code) }

        // Remove one: the record and the file go; the other stays.
        client2.delete("properties/$propertyId/photos/$first").use { assertEquals(204, it.code) }
        assertFalse(File(dir, first).exists())
        assertTrue(File(dir, second).exists())
        client2.get("properties/$propertyId/photos/$first").use { assertEquals(404, it.code) }

        // Orphan files (e.g. the app closed mid-upload) are cleaned up; used ones are kept.
        File(dir, "demophotoorphan").writeBytes(jpeg)
        DemoPhotos(dir) { api2 }.pruneUnused()
        assertFalse(File(dir, "demophotoorphan").exists())
        assertTrue(File(dir, second).exists())
    }

    @Test
    fun unknownPropertyLeavesNoFile() {
        val (_, client) = phone()
        client.upload("no-such-property", jpeg).use { assertEquals(404, it.code) }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
