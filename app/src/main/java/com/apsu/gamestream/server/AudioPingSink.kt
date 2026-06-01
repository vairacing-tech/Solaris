package com.apsu.gamestream.server

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioPingSink(
    private val port: Int,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    @Volatile private var peer: InetSocketAddress? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        socket = DatagramSocket(port, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
        }
        thread(name = "apsu-audio-ping-sink", isDaemon = true) {
            val buffer = ByteArray(256)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching {
                    socket?.receive(packet)
                    packet
                }.getOrNull() ?: continue
                val nextPeer = InetSocketAddress(received.address, received.port)
                ClientConnectionState.mark("Audio ping", received.address.hostAddress)
                if (peer != nextPeer) {
                    peer = nextPeer
                    onLog("Audio ping peer ${nextPeer.address.hostAddress}:${nextPeer.port}")
                }
            }
        }
        onLog("Audio ping sink listening on UDP $port")
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        peer = null
    }
}
