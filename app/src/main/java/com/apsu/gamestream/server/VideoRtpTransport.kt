package com.apsu.gamestream.server

import android.media.MediaCodec
import com.apsu.gamestream.encoder.EncodedFrame
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

class VideoRtpTransport(
    private val port: Int,
    private val includeFrameHeader: Boolean,
    private val onPeerReady: () -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    @Volatile private var peer: InetSocketAddress? = null
    private var receiveThread: Thread? = null
    private var rtpSequence = 1
    private var streamPacketIndex = 0
    private var frameIndex = 1
    private var sentFrames = 0L
    @Volatile private var packetSize = DEFAULT_GAMESTREAM_PACKET_SIZE

    fun start() {
        if (!running.compareAndSet(false, true)) return
        rtpSequence = 1
        streamPacketIndex = 0
        frameIndex = 1
        sentFrames = 0L
        socket = DatagramSocket(port, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
            it.sendBufferSize = 1024 * 1024
        }
        receiveThread = thread(name = "apsu-video-udp", isDaemon = true) {
            val buffer = ByteArray(2048)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching {
                    socket?.receive(packet)
                    packet
                }.getOrNull() ?: continue

                val nextPeer = InetSocketAddress(received.address, received.port)
                ClientConnectionState.mark("Video RTP", received.address.hostAddress)
                if (peer != nextPeer) {
                    peer = nextPeer
                    onLog("Video UDP peer ${nextPeer.address.hostAddress}:${nextPeer.port}")
                    onPeerReady()
                }
            }
        }
        onLog("Video RTP listening on UDP $port")
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        peer = null
        rtpSequence = 1
        streamPacketIndex = 0
        frameIndex = 1
        sentFrames = 0L
        receiveThread = null
    }

    fun setPacketSize(requestedPacketSize: Int) {
        val nextPacketSize = requestedPacketSize.coerceIn(MIN_GAMESTREAM_PACKET_SIZE, MAX_GAMESTREAM_PACKET_SIZE)
        if (packetSize != nextPacketSize) {
            packetSize = nextPacketSize
            onLog("Video packet size set to $nextPacketSize")
        }
    }

    fun sendFrame(frame: EncodedFrame) {
        val activeSocket = socket ?: return
        val activePeer = peer ?: return
        if (frame.bytes.isEmpty()) return

        val frameType = if ((frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) 2 else 1
        val payload = buildFramePayload(frame.bytes, frameType)
        val payloadUnitSize = (packetSize - NV_VIDEO_HEADER_SIZE).coerceAtLeast(1)
        val dataPackets = (payload.size + payloadUnitSize - 1) / payloadUnitSize
        if (dataPackets <= 0 || dataPackets > 1023) {
            onLog("Dropping oversized encoded frame: ${payload.size} bytes, $dataPackets packets")
            return
        }

        val currentFrame = frameIndex++
        val timestamp = ((frame.presentationTimeUs * 90L) / 1000L).toInt()
        var offset = 0
        for (packetIndex in 0 until dataPackets) {
            val chunkLength = min(payloadUnitSize, payload.size - offset)
            val sequence = nextRtpSequence()
            val currentStreamPacketIndex = nextStreamPacketIndex()
            val flags = FLAG_CONTAINS_PIC_DATA or
                (if (packetIndex == 0) FLAG_SOF else 0) or
                (if (packetIndex == dataPackets - 1) FLAG_EOF else 0)
            val packet = buildPacket(
                sequence = sequence,
                streamPacketIndex = currentStreamPacketIndex,
                timestamp = timestamp,
                frameIndex = currentFrame,
                packetIndex = packetIndex,
                dataPackets = dataPackets,
                flags = flags,
                payload = payload,
                payloadOffset = offset,
                payloadLength = chunkLength,
            )
            activeSocket.send(DatagramPacket(packet, packet.size, activePeer.address, activePeer.port))
            offset += chunkLength
        }
        sentFrames++
        if (sentFrames == 1L || sentFrames % SENT_FRAME_LOG_INTERVAL == 0L) {
            onLog("Sent video frame $sentFrames as $dataPackets UDP packets to ${activePeer.address.hostAddress}:${activePeer.port}")
        }
    }

    private fun nextRtpSequence(): Int {
        val sequence = rtpSequence and 0xFFFF
        rtpSequence = (rtpSequence + 1) and 0xFFFF
        return sequence
    }

    private fun nextStreamPacketIndex(): Int {
        val index = streamPacketIndex and STREAM_PACKET_INDEX_MASK
        streamPacketIndex = (streamPacketIndex + 1) and STREAM_PACKET_INDEX_MASK
        return index
    }

    private fun buildFramePayload(encodedFrame: ByteArray, frameType: Int): ByteArray {
        if (!includeFrameHeader) return encodedFrame
        val frameHeader = ByteBuffer.allocate(FRAME_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0x01)
            .putShort(0)
            .put(frameType.toByte())
            .putShort(0)
            .putShort(0)
            .array()
        return frameHeader + encodedFrame
    }

    private fun buildPacket(
        sequence: Int,
        streamPacketIndex: Int,
        timestamp: Int,
        frameIndex: Int,
        packetIndex: Int,
        dataPackets: Int,
        flags: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ): ByteArray {
        val packetSize = RTP_HEADER_SIZE + RTP_EXTENSION_SIZE + NV_VIDEO_HEADER_SIZE + payloadLength
        val buffer = ByteBuffer.allocate(packetSize)

        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x90.toByte())
        buffer.put(96.toByte())
        buffer.putShort(sequence.toShort())
        buffer.putInt(timestamp)
        buffer.putInt(0)

        buffer.putInt(0)

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(NvVideoPacketHeader.encodeStreamPacketIndex(streamPacketIndex))
        buffer.putInt(frameIndex)
        buffer.put(flags.toByte())
        buffer.put(0)
        buffer.put(0x10)
        buffer.put(0)
        buffer.putInt((packetIndex shl 12) or (dataPackets shl 22))
        buffer.put(payload, payloadOffset, payloadLength)
        return buffer.array()
    }

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val RTP_EXTENSION_SIZE = 4
        private const val NV_VIDEO_HEADER_SIZE = 16
        private const val DEFAULT_GAMESTREAM_PACKET_SIZE = 1024
        private const val MIN_GAMESTREAM_PACKET_SIZE = 512
        private const val MAX_GAMESTREAM_PACKET_SIZE = 1392
        private const val FRAME_HEADER_SIZE = 8
        private const val SENT_FRAME_LOG_INTERVAL = 300L
        private const val STREAM_PACKET_INDEX_MASK = 0x00FF_FFFF

        private const val FLAG_CONTAINS_PIC_DATA = 0x01
        private const val FLAG_EOF = 0x02
        private const val FLAG_SOF = 0x04
    }
}

internal object NvVideoPacketHeader {
    private const val STREAM_PACKET_INDEX_MASK = 0x00FF_FFFF

    fun encodeStreamPacketIndex(index: Int): Int =
        (index and STREAM_PACKET_INDEX_MASK) shl 8
}
