package com.apsu.gamestream

import com.apsu.gamestream.pairing.PairingPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPinTest {
    @Test
    fun acceptsExactlyFourDigits() {
        assertEquals("1234", PairingPin.normalize("1234"))
        assertEquals("9876", PairingPin.normalize(" 9876 "))
    }

    @Test
    fun rejectsMissingOrMalformedPins() {
        assertNull(PairingPin.normalize(null))
        assertNull(PairingPin.normalize("123"))
        assertNull(PairingPin.normalize("12345"))
        assertNull(PairingPin.normalize("12a4"))
    }

    @Test
    fun extractsCommonQueryNames() {
        assertEquals("2468", PairingPin.fromQuery(mapOf("pin" to "2468")))
        assertEquals("1357", PairingPin.fromQuery(mapOf("code" to "1357")))
    }
}
