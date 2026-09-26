package com.brokerbuddy.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStoreTest {
    @Test
    fun normalizesServerUrls() {
        assertEquals("http://192.168.1.5:4000/", SessionStore.normalizeServerUrl("192.168.1.5:4000"))
        assertEquals("https://api.example.in/", SessionStore.normalizeServerUrl(" https://api.example.in "))
        assertEquals("http://10.0.2.2:4000/", SessionStore.normalizeServerUrl("http://10.0.2.2:4000/"))
    }
}
