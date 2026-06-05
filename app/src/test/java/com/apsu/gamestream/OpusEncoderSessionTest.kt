package com.apsu.gamestream

import com.apsu.gamestream.audio.OpusEncoderSession
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusEncoderSessionTest {
    @Test
    fun frameConstantsMatchLegacyMoonlightFiveMilliseconds() {
        assertEquals(5, OpusEncoderSession.PACKET_DURATION_MS)
        assertEquals(240, OpusEncoderSession.FRAME_SAMPLES_PER_CHANNEL)
        assertEquals(960, OpusEncoderSession.FRAME_BYTES)
    }

    @Test
    fun concentusEncodesFiveMillisecondStereoPacket() {
        val encoder = OpusEncoder(
            OpusEncoderSession.SAMPLE_RATE,
            OpusEncoderSession.CHANNEL_COUNT,
            OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY,
        ).apply {
            setBitrate(128_000)
            setUseVBR(false)
            setUseInbandFEC(false)
        }
        val pcm = ShortArray(OpusEncoderSession.FRAME_SAMPLES_PER_CHANNEL * OpusEncoderSession.CHANNEL_COUNT)
        for (sample in 0 until OpusEncoderSession.FRAME_SAMPLES_PER_CHANNEL) {
            val value = (sin(2.0 * PI * 440.0 * sample / OpusEncoderSession.SAMPLE_RATE) * 12_000).toInt().toShort()
            pcm[sample * 2] = value
            pcm[sample * 2 + 1] = value
        }
        val output = ByteArray(1_275)

        val encodedLength = encoder.encode(
            pcm,
            0,
            OpusEncoderSession.FRAME_SAMPLES_PER_CHANNEL,
            output,
            0,
            output.size,
        )

        assertTrue(encodedLength > 0)
    }
}
