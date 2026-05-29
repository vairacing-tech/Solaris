package com.apsu.gamestream.server

import android.content.Context
import com.apsu.gamestream.crypto.ServerIdentity
import com.apsu.gamestream.encoder.EncodedFrame
import com.apsu.gamestream.model.StreamConfig
import com.apsu.gamestream.pairing.PairingProtocol
import com.apsu.gamestream.pairing.PairingStore
import java.util.concurrent.atomic.AtomicLong

class GameStreamServer(
    context: Context,
    initialPin: String?,
    private val onIdrRequested: () -> Unit,
    private val onPinChanged: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val pairingStore = PairingStore(context.applicationContext)
    private val frameCount = AtomicLong()
    private var videoTransport: VideoRtpTransport? = null
    private var audioPingSink: AudioPingSink? = null
    private var legacyControlServer: LegacyControlTcpServer? = null
    private var inputSinkServer: TcpInputSinkServer? = null
    private var nvHttpServer: NvHttpServer? = null
    private var rtspServer: RtspServer? = null
    @Volatile private var activePin: String? = initialPin

    fun start(config: StreamConfig, activeMime: String) {
        if (nvHttpServer != null || rtspServer != null) return
        frameCount.set(0)
        val identity = ServerIdentity.load(appContext)
        val pairingProtocol = PairingProtocol(
            serverIdentity = identity,
            pairingStore = pairingStore,
            pinProvider = { activePin },
            hash = GameStreamProtocol.pairingHash,
        )
        videoTransport = VideoRtpTransport(
            port = Ports.VIDEO,
            includeFrameHeader = GameStreamProtocol.INCLUDE_VIDEO_FRAME_HEADER,
            onLog = onLog,
        ).also { it.start() }
        audioPingSink = AudioPingSink(
            port = Ports.AUDIO,
            onLog = onLog,
        ).also { it.start() }
        if (GameStreamProtocol.LEGACY_TCP_CONTROL) {
            legacyControlServer = LegacyControlTcpServer(
                port = Ports.LEGACY_CONTROL,
                onIdrRequested = {
                    onLog("Client requested IDR frame")
                    onIdrRequested()
                },
                onLog = onLog,
            ).also { it.start() }
            inputSinkServer = TcpInputSinkServer(
                port = Ports.LEGACY_INPUT,
                onLog = onLog,
            ).also { it.start() }
        }
        nvHttpServer = NvHttpServer(
            httpPort = Ports.HTTP,
            httpsPort = Ports.HTTPS,
            serverIdentity = identity,
            pairingStore = pairingStore,
            pairingProtocol = pairingProtocol,
            currentConfig = { config },
            activeVideoMime = { activeMime },
            onPinReceived = { pin -> setPairingPin(pin) },
            onLaunchRequested = {
                onLog("Launch requested by client")
                true
            },
            onLog = onLog,
        ).also { it.start() }
        rtspServer = RtspServer(
            port = Ports.RTSP,
            currentConfig = { config },
            activeVideoMime = { activeMime },
            onVideoPacketSize = { size -> videoTransport?.setPacketSize(size) },
            onPlay = {
                onLog("RTSP PLAY received; waiting for video UDP ping on ${Ports.VIDEO}")
            },
            onLog = onLog,
        ).also { it.start() }
        val pinState = activePin ?: "not set"
        onLog("Pairing PIN $pinState; GameStream servers listening on ${Ports.HTTP}/${Ports.HTTPS}/${Ports.RTSP}/${Ports.VIDEO}")
    }

    fun setPairingPin(pin: String) {
        activePin = pin
        onPinChanged(pin)
        onLog("Pairing PIN updated")
    }

    fun stop() {
        nvHttpServer?.stop()
        nvHttpServer = null
        rtspServer?.stop()
        rtspServer = null
        inputSinkServer?.stop()
        inputSinkServer = null
        legacyControlServer?.stop()
        legacyControlServer = null
        audioPingSink?.stop()
        audioPingSink = null
        videoTransport?.stop()
        videoTransport = null
        onLog("GameStream control servers stopped")
    }

    fun onEncodedFrame(frame: EncodedFrame) {
        val count = frameCount.incrementAndGet()
        videoTransport?.sendFrame(frame)
        if (count == 1L || count % 300L == 0L) {
            onLog("Encoded frame $count, ${frame.bytes.size} bytes, flags=${frame.flags}")
        }
    }
}
