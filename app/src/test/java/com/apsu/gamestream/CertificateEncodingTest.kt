package com.apsu.gamestream

import com.apsu.gamestream.crypto.CertificateEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class CertificateEncodingTest {
    @Test
    fun pemBytesWrapDerForMoonlightIosPairing() {
        val der = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x05)
        val pem = CertificateEncoding.pemBytesForDer(der).toString(StandardCharsets.US_ASCII)

        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("\n-----END CERTIFICATE-----\n"))

        val body = pem
            .removePrefix("-----BEGIN CERTIFICATE-----\n")
            .removeSuffix("\n-----END CERTIFICATE-----\n")
        assertArrayEquals(der, Base64.getMimeDecoder().decode(body))
    }
}
