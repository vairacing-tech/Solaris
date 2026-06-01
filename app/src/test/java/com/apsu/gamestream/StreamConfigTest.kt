package com.apsu.gamestream

import com.apsu.gamestream.model.CodecPreference
import com.apsu.gamestream.model.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamConfigTest {
    @Test
    fun defaultsPreferH2641080p60() {
        val config = StreamConfig()

        assertEquals(CodecPreference.H264, config.codecPreference)
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(60, config.fps)
        assertEquals(16_000_000, config.bitrate)
        assertEquals(false, config.audioEnabled)
    }

    @Test
    fun rejectsInvalidBitrate() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamConfig(bitrate = 0)
        }
    }
}
