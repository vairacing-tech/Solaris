package com.apsu.gamestream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.apsu.gamestream.R
import com.apsu.gamestream.audio.AudioCaptureSession
import com.apsu.gamestream.audio.OpusEncoderSession
import com.apsu.gamestream.crypto.ServerIdentity
import com.apsu.gamestream.encoder.EncoderSelector
import com.apsu.gamestream.encoder.EncoderSession
import com.apsu.gamestream.model.CodecPreference
import com.apsu.gamestream.model.ServerState
import com.apsu.gamestream.model.StreamConfig
import com.apsu.gamestream.pairing.PairingStore
import com.apsu.gamestream.pairing.PairingPin
import com.apsu.gamestream.server.GameStreamServer
import com.apsu.gamestream.server.HostIdentity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectionStreamService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderSession: EncoderSession? = null
    private var audioCaptureSession: AudioCaptureSession? = null
    private var opusEncoderSession: OpusEncoderSession? = null
    private var gameStreamServer: GameStreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fallbackConfig: StreamConfig = StreamConfig()
    private var activeStreamConfig: StreamConfig? = null
    private var activeEncoderMime: String? = null
    private var congestionAdjustedBitrate: Int? = null
    private var lastCongestionActionMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var idrHeartbeatActive = false

    private val idrHeartbeat = object : Runnable {
        override fun run() {
            val encoder = encoderSession
            if (encoder == null) {
                idrHeartbeatActive = false
                return
            }
            encoder.requestSyncFrame()
            mainHandler.postDelayed(this, IDR_HEARTBEAT_MS)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            updateStatus(ServerState.IDLE, "MediaProjection stopped")
            stopStreaming()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromIntent(intent)
            ACTION_SET_PAIRING_PIN -> updatePairingPin(intent.getStringExtra(EXTRA_PAIRING_PIN), "UI")
            ACTION_CLEAR_PAIRINGS -> clearPairings()
            ACTION_RESET_HOST_IDENTITY -> resetHostIdentity()
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun startFromIntent(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = projectionData(intent)
        if (resultCode == 0 || projectionData == null) {
            updateStatus(ServerState.ERROR, "Missing MediaProjection permission result")
            stopSelf()
            return
        }

        val config = StreamConfigExtras.from(intent)
        fallbackConfig = config
        updateStatus(ServerState.WAITING_FOR_PROJECTION, "Starting host with ${config.resolutionLabel} ${config.fps}fps fallback")
        startInForeground("Starting GameStream host")
        acquireWakeLock()

        try {
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mediaProjectionManager.getMediaProjection(resultCode, projectionData)
                ?: throw IllegalStateException("MediaProjection could not be created")
            projection.registerCallback(projectionCallback, mainHandler)
            this.projection = projection

            val pin = PairingPin.normalize(currentPin)
            if (pin == null) {
                log("Pairing PIN not set; enter the PIN shown by Artemis/Moonlight")
            }
            val server = GameStreamServer(
                context = applicationContext,
                initialPin = pin,
                onIdrRequested = { encoderSession?.requestSyncFrame() },
                onVideoCongestion = { handleVideoCongestion() },
                onStreamConfigRequested = { requestedConfig -> startOrRestartCapture(requestedConfig) },
                onPinChanged = { newPin -> currentPin = newPin },
                onLog = { log(it) },
            )
            gameStreamServer = server
            server.start(config, advertisedMimeFor(config))
            updateStatus(ServerState.READY, "Host ready; waiting for client stream settings")
            startInForeground("Host ready for Moonlight/Artemis")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start host", t)
            updateStatus(ServerState.ERROR, t.message ?: "Failed to start host")
            stopStreaming()
            stopSelf()
        }
    }

    @Synchronized
    private fun startOrRestartCapture(config: StreamConfig): String? {
        val activeProjection = projection ?: run {
            updateStatus(ServerState.ERROR, "MediaProjection is not available")
            return null
        }
        val currentMime = activeEncoderMime
        if (encoderSession != null && activeStreamConfig == config && currentMime != null) {
            encoderSession?.requestSyncFrame()
            return currentMime
        }

        updateStatus(
            ServerState.READY,
            "Configuring ${config.resolutionLabel} ${config.fps}fps ${config.codecPreference.name}",
        )
        val encoderInfo = runCatching {
            EncoderSelector.select(
                codecPreference = config.codecPreference,
                width = config.width,
                height = config.height,
                fps = config.fps,
                bitrate = config.bitrate,
            )
        }.getOrElse { throwable ->
            Log.e(TAG, "Client stream config rejected", throwable)
            updateStatus(ServerState.ERROR, throwable.message ?: "No compatible hardware encoder")
            return null
        }

        stopCapturePipeline()
        val server = gameStreamServer ?: run {
            updateStatus(ServerState.ERROR, "GameStream server is not running")
            return null
        }

        return try {
            val encoder = EncoderSession(
                config = config,
                encoderInfo = encoderInfo,
                onFrame = { frame -> server.onEncodedFrame(frame) },
                onError = { throwable ->
                    updateStatus(ServerState.ERROR, "Encoder error: ${throwable.message}")
                    stopCapturePipeline()
                },
            )
            val inputSurface = encoder.start()
            encoderSession = encoder

            val metrics = resources.displayMetrics
            virtualDisplay = activeProjection.createVirtualDisplay(
                "SolarisGameStream",
                config.width,
                config.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                mainHandler,
            )

            if (config.audioEnabled) {
                startAudioPipeline(activeProjection, server)
            }

            activeStreamConfig = config
            activeEncoderMime = encoderInfo.mime
            congestionAdjustedBitrate = config.bitrate
            lastCongestionActionMs = 0L
            startIdrHeartbeat()
            updateStatus(
                ServerState.STREAMING,
                "Streaming ${config.resolutionLabel} ${config.fps}fps via ${encoderInfo.codecName} (${encoderInfo.vendor})",
            )
            startInForeground("Streaming ${config.resolutionLabel} ${config.fps}fps")
            encoderInfo.mime
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start capture pipeline", t)
            stopCapturePipeline()
            updateStatus(ServerState.ERROR, t.message ?: "Failed to start capture pipeline")
            null
        }
    }

    @Synchronized
    private fun stopCapturePipeline() {
        stopIdrHeartbeat()
        stopAudioPipeline()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        encoderSession?.stop()
        encoderSession = null
        activeStreamConfig = null
        activeEncoderMime = null
        congestionAdjustedBitrate = null
        lastCongestionActionMs = 0L
    }

    @Synchronized
    private fun handleVideoCongestion() {
        val encoder = encoderSession ?: return
        encoder.requestSyncFrame()

        val config = activeStreamConfig ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCongestionActionMs < CONGESTION_BITRATE_COOLDOWN_MS) return

        val currentBitrate = congestionAdjustedBitrate ?: config.bitrate
        val floorBitrate = congestionBitrateFloor(config)
        if (currentBitrate <= floorBitrate) {
            lastCongestionActionMs = now
            log("Video congestion: requested IDR; bitrate already at floor ${formatMbps(floorBitrate)} Mbps")
            return
        }

        val nextBitrate = ((currentBitrate.toLong() * CONGESTION_BITRATE_SCALE_PERCENT) / 100L)
            .toInt()
            .coerceAtLeast(floorBitrate)
        if (encoder.setVideoBitrate(nextBitrate)) {
            congestionAdjustedBitrate = nextBitrate
            lastCongestionActionMs = now
            log(
                "Video congestion: reduced encoder bitrate " +
                    "${formatMbps(currentBitrate)} -> ${formatMbps(nextBitrate)} Mbps and requested IDR",
            )
        } else {
            lastCongestionActionMs = now
            log("Video congestion: requested IDR; encoder rejected dynamic bitrate change")
        }
    }

    private fun startAudioPipeline(activeProjection: MediaProjection, server: GameStreamServer) {
        val opusEncoder = OpusEncoderSession(
            onPacket = { packet, durationMillis ->
                server.onOpusAudioPacket(packet, durationMillis)
            },
            onLog = { message -> log(message) },
            onError = { throwable ->
                log("Audio encoding disabled: ${throwable.message}")
                stopAudioPipeline()
            },
        )
        runCatching { opusEncoder.start() }
            .onFailure { throwable ->
                log("Audio encoding disabled: ${throwable.message}")
                opusEncoder.stop()
                return
            }
        opusEncoderSession = opusEncoder

        val audioSession = AudioCaptureSession(
            projection = activeProjection,
            onPcm = { pcm, _ -> opusEncoderSession?.queuePcm(pcm) },
            onError = { throwable ->
                log("Audio capture disabled: ${throwable.message}")
                stopAudioPipeline()
            },
        )
        audioCaptureSession = audioSession
        runCatching { audioSession.start() }
            .onFailure { throwable ->
                log("Audio capture disabled: ${throwable.message}")
                audioSession.stop()
                audioCaptureSession = null
                opusEncoderSession?.stop()
                opusEncoderSession = null
            }
    }

    @Synchronized
    private fun stopAudioPipeline() {
        audioCaptureSession?.stop()
        audioCaptureSession = null
        opusEncoderSession?.stop()
        opusEncoderSession = null
    }

    private fun startIdrHeartbeat() {
        if (idrHeartbeatActive) return
        idrHeartbeatActive = true
        mainHandler.postDelayed(idrHeartbeat, IDR_HEARTBEAT_MS)
    }

    private fun stopIdrHeartbeat() {
        idrHeartbeatActive = false
        mainHandler.removeCallbacks(idrHeartbeat)
    }

    @Synchronized
    private fun stopStreaming() {
        stopCapturePipeline()
        gameStreamServer?.stop()
        gameStreamServer = null
        releaseWakeLock()
        val activeProjection = projection
        projection = null
        runCatching { activeProjection?.unregisterCallback(projectionCallback) }
        runCatching { activeProjection?.stop() }
        updateStatus(ServerState.IDLE, "Stopped")
        currentPin = "----"
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun advertisedMimeFor(config: StreamConfig): String =
        when (config.codecPreference) {
            CodecPreference.HEVC -> "video/hevc"
            CodecPreference.H264 -> "video/avc"
            CodecPreference.AUTO -> "video/avc"
        }

    private fun updatePairingPin(pin: String?, source: String) {
        val normalized = PairingPin.normalize(pin) ?: run {
            log("Rejected invalid pairing PIN from $source")
            return
        }
        currentPin = normalized
        gameStreamServer?.setPairingPin(normalized)
        log("Pairing PIN set from $source")
    }

    private fun clearPairings() {
        PairingStore(applicationContext).clear()
        currentPin = "----"
        updateStatus(currentState, "Paired clients cleared")
    }

    private fun resetHostIdentity() {
        stopStreaming()
        runCatching { PairingStore(applicationContext).clear() }
        runCatching { ServerIdentity.reset(applicationContext) }
        runCatching { HostIdentity.reset(applicationContext) }
        currentPin = "----"
        updateStatus(ServerState.IDLE, "Host identity, TLS certificate and pairings reset")
    }

    private fun startInForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceType(),
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (fallbackConfig.audioEnabled || activeStreamConfig?.audioEnabled == true) &&
            hasRecordAudioPermission()
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun hasRecordAudioPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun congestionBitrateFloor(config: StreamConfig): Int {
        val pixels = config.width * config.height
        val resolutionFloor = when {
            pixels >= 2560 * 1440 -> 8_000_000
            pixels >= 1920 * 1080 -> 5_000_000
            else -> 2_500_000
        }
        val originalFloor = ((config.bitrate.toLong() * CONGESTION_MIN_ORIGINAL_PERCENT) / 100L).toInt()
        return minOf(config.bitrate, maxOf(resolutionFloor, originalFloor))
    }

    private fun formatMbps(bitrate: Int): String =
        String.format(Locale.US, "%.1f", bitrate / 1_000_000.0)

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Stop server",
                    stopServicePendingIntent(),
                ).build(),
            )
            .build()
    }

    private fun stopServicePendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(
            this,
            STOP_ACTION_REQUEST_CODE,
            stopIntent(this),
            flags,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Streaming",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:streaming",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun log(message: String) {
        Log.i(TAG, message)
        lastMessage = message
        recordLog(message)
    }

    private fun updateStatus(state: ServerState, message: String) {
        currentState = state
        lastMessage = message
        Log.i(TAG, "$state: $message")
        recordLog("$state: $message")
        SolarisTileService.requestTileRefresh(applicationContext)
    }

    companion object {
        private const val TAG = "ProjectionStreamService"
        private const val CHANNEL_ID = "apsu-stream"
        private const val NOTIFICATION_ID = 42
        private const val STOP_ACTION_REQUEST_CODE = 43
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_PAIRING_PIN = "pairing_pin"
        const val ACTION_START = "com.apsu.gamestream.START"
        const val ACTION_SET_PAIRING_PIN = "com.apsu.gamestream.SET_PAIRING_PIN"
        const val ACTION_CLEAR_PAIRINGS = "com.apsu.gamestream.CLEAR_PAIRINGS"
        const val ACTION_RESET_HOST_IDENTITY = "com.apsu.gamestream.RESET_HOST_IDENTITY"
        const val ACTION_STOP = "com.apsu.gamestream.STOP"
        private const val MAX_RECENT_LOGS = 80
        private const val IDR_HEARTBEAT_MS = 1_000L
        private const val CONGESTION_BITRATE_COOLDOWN_MS = 6_000L
        private const val CONGESTION_BITRATE_SCALE_PERCENT = 85
        private const val CONGESTION_MIN_ORIGINAL_PERCENT = 45
        private val logLock = Any()
        private val timestampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val recentLogLines = mutableListOf<String>()

        @Volatile var currentState: ServerState = ServerState.IDLE
            private set
        @Volatile var lastMessage: String = "Idle"
            private set
        @Volatile var currentPin: String = "----"
            private set

        fun setPendingPairingPin(pin: String): Boolean {
            val normalized = PairingPin.normalize(pin) ?: return false
            currentPin = normalized
            lastMessage = "Pairing PIN ready"
            recordLog("Pairing PIN ready")
            return true
        }

        fun recentLogs(): List<String> =
            synchronized(logLock) { recentLogLines.toList() }

        private fun recordLog(message: String) {
            synchronized(logLock) {
                val line = "${timestampFormat.format(Date())}  $message"
                recentLogLines.add(line)
                while (recentLogLines.size > MAX_RECENT_LOGS) {
                    recentLogLines.removeAt(0)
                }
            }
        }

        fun setPinIntent(context: Context, pin: String): Intent =
            Intent(context, ProjectionStreamService::class.java)
                .setAction(ACTION_SET_PAIRING_PIN)
                .putExtra(EXTRA_PAIRING_PIN, pin)

        fun startIntent(context: Context, resultCode: Int, data: Intent, config: StreamConfig): Intent {
            val intent = Intent(context, ProjectionStreamService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            return StreamConfigExtras.put(intent, config)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ProjectionStreamService::class.java).setAction(ACTION_STOP)

        fun clearPairingsIntent(context: Context): Intent =
            Intent(context, ProjectionStreamService::class.java).setAction(ACTION_CLEAR_PAIRINGS)

        fun resetHostIdentityIntent(context: Context): Intent =
            Intent(context, ProjectionStreamService::class.java).setAction(ACTION_RESET_HOST_IDENTITY)
    }
}
