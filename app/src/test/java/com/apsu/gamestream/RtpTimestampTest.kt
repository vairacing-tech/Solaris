package com.apsu.gamestream

import com.apsu.gamestream.server.RtpTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class RtpTimestampTest {
    @Test
    fun derivesTimestampFromFrameIndexAndFps() {
        assertEquals(0, RtpTimestamp.forFrame(1, 60))
        assertEquals(1_500, RtpTimestamp.forFrame(2, 60))
        assertEquals(3_000, RtpTimestamp.forFrame(3, 60))
        assertEquals(90_000, RtpTimestamp.forFrame(61, 60))
    }
}
