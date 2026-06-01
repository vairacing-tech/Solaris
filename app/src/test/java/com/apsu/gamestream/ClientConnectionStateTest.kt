package com.apsu.gamestream

import com.apsu.gamestream.server.ClientConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientConnectionStateTest {
    @Test
    fun tracksLatestClientActivityAndCurrentGame() {
        ClientConnectionState.clear()

        ClientConnectionState.mark("RTSP", "192.168.1.50")
        ClientConnectionState.setCurrentGame(1)

        val snapshot = ClientConnectionState.snapshot()
        assertEquals("192.168.1.50", snapshot.address)
        assertEquals("RTSP", snapshot.channel)
        assertEquals(1, snapshot.currentGameId)
        assertTrue(snapshot.isActive(snapshot.lastActivityEpochMillis + 1_000))
    }

    @Test
    fun clearRemovesActiveClient() {
        ClientConnectionState.mark("Video RTP", "192.168.1.50", currentGameId = 1)
        ClientConnectionState.clear()

        val snapshot = ClientConnectionState.snapshot()
        assertNull(snapshot.address)
        assertEquals(0, snapshot.currentGameId)
        assertFalse(snapshot.isActive(snapshot.lastActivityEpochMillis + 1_000))
    }
}
