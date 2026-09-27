package com.brokerbuddy.core.demo

import com.brokerbuddy.core.match.Locality
import com.brokerbuddy.core.match.Matching
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.FloorBand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The demo's on-phone matcher gives the same answers as the server: every match list in the
 * bundled sample data (captured from the real server) is recomputed and compared.
 */
class MatchingParityTest {
    private val snapshot = File("../app/src/main/assets/demo/responses.json").readText()
    private val root = ApiJson.parseToJsonElement(snapshot).jsonObject
    private val responses = root.getValue("responses").jsonObject
    // No date shift, so possession dates compare exactly as they did on the server.
    private val api = DemoApi(snapshot, LocalDate.parse(root.getValue("capturedOn").jsonPrimitive.content))

    /** id, score, and every check, of each match. */
    private fun summary(body: JsonObject, itemKey: String): List<String> =
        (body.getValue("matches") as JsonArray).map { m ->
            val o = m.jsonObject
            val checks = (o.getValue("checks") as JsonArray).joinToString(";") { c ->
                val co = c.jsonObject
                "${co.str("field")}=${co.str("outcome")}/${co.getValue("mandatory")}/${co.str("detail")}"
            }
            "${o.obj(itemKey)?.str("id")} score=${o.getValue("score")} verify=${o.getValue("needsVerification")} $checks"
        }

    @Test
    fun requirementMatchesAgreeWithTheServer() {
        val keys = responses.keys.filter { Regex("^inquiries/[^/?]+/matches$").matches(it) }
        assertTrue(keys.size >= 8, "sample data has requirement matches")
        var nonEmpty = 0
        for (key in keys) {
            val server = responses.getValue(key).jsonObject
            val phone = ApiJson.parseToJsonElement(api.handle("GET", "/api/v1/$key", emptyList()).body).jsonObject
            assertEquals(summary(server, "property"), summary(phone, "property"), key)
            if ((server.getValue("matches") as JsonArray).isNotEmpty()) nonEmpty++
        }
        assertTrue(nonEmpty >= 5, "most sample requirements have matches")
    }

    @Test
    fun propertyMatchesAgreeWithTheServer() {
        val keys = responses.keys.filter { Regex("^properties/[^/?]+/matches$").matches(it) }
        assertTrue(keys.size >= 10)
        for (key in keys) {
            val server = responses.getValue(key).jsonObject
            val phone = ApiJson.parseToJsonElement(api.handle("GET", "/api/v1/$key", emptyList()).body).jsonObject
            assertEquals(summary(server, "inquiry"), summary(phone, "inquiry"), key)
        }
    }

    @Test
    fun matchCountsAgreeWithTheServer() {
        val server = responses.getValue("inquiries").jsonObject.arr("inquiries").associate { it.str("id") to it.str("matchCount") }
        val phone = ApiJson.parseToJsonElement(api.handle("GET", "/api/v1/inquiries", emptyList()).body).jsonObject
            .arr("inquiries").associate { it.str("id") to it.str("matchCount") }
        assertEquals(server, phone)
    }

    @Test
    fun floorBandsAndLocalities() {
        assertEquals(FloorBand.LOWER, Matching.floorBandOf(0, 10))
        assertEquals(FloorBand.MIDDLE, Matching.floorBandOf(5, 10))
        assertEquals(FloorBand.HIGHER, Matching.floorBandOf(7, 10))
        assertNull(Matching.floorBandOf(5, null))
        assertNull(Matching.floorBandOf(null, 10))
        assertTrue(Locality.matches("Andheri", "Andheri (W)"))
        assertTrue(Locality.matches("andheri w", "Andheri West"))
        assertTrue(!Locality.matches("Andheri West", "Andheri East"))
    }
}
