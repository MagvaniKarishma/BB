package com.brokerbuddy.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiClientTest {
    @Test
    fun unreachableMessageNamesTheAddress() {
        val lan = ApiClient.unreachableMessage("http://192.168.1.23:4000/")
        assertTrue("192.168.1.23:4000" in lan)
        assertFalse("emulator" in lan)
    }

    @Test
    fun emulatorAddressOnAPhoneIsExplained() {
        val msg = ApiClient.unreachableMessage("http://10.0.2.2:4000/")
        assertTrue("only works in the Android emulator" in msg)
    }
}
