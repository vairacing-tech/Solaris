package com.apsu.gamestream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.Log
import com.apsu.gamestream.R
import com.apsu.gamestream.audio.AudioCaptureSession
import com.apsu.gamestream.encoder.EncoderSelector
import com.apsu.gamestream.encoder.EncoderSession
import com.apsu.gamestream.model.ServerState
import com.apsu.gamestream.model.StreamConfig
import com.apsu.gamestream.pairing.PairingPin
import com.apsu.gamestream.server.GameStreamServer

class ProjectionStreamService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderSession: EncoderSession? = null
    private var audioCaptureSession: AudioCaptureSession? = null
    private var gameStreamServer: GameStreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        updateStatus(ServerState.WAITING_FOR_PROJECTION, "Starting ${config.resolutionLabel} ${config.fps}fps")
        startInForeground("Starting ${config.resolutionLabel} ${config.fps}fps")
        acquireWakeLock()

        try {
            val encoderInfo = EncoderSelector.select(
                codecPreference = config.codecPreference,
                width = config.width,
                height = config.height,
                fps = config.fps,
                bitrate = config.bitrate,
            )
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
                onPinChanged = { newPin -> currentPin = newPin },
                onLog = { log(it) },
            )
            server.start(config, encoderInfo.mime)
            gameStreamServer = server

            val encoder = EncoderSession(
                config = config,
                encoderInfo = encoderInfo,
                onFrame = { frame -> server.onEncodedFrame(frame) },
                onError = { throwable ->
                    updateStatus(ServerState.ERROR, "Encoder error: ${throwable.message}")
                    stopStreaming()
                },
            )
            val inputSurface = encoder.start()
            encoderSession = encoder

            val metrics = resources.displayMetrics
            virtualDisplay = projection.createVirtualDisplay(
                "ApsuGameStream",
                config.width,
                config.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                mainHandler,
            )

            if (config.audioEnabled) {
                val audioSession = AudioCaptureSession(
                    projection = projection,
                    onPcm = { pcm, _ -> if (pcm.isNotEmpty()) Unit },
                    onError = { throwable -> log("Audio capture disabled: ${throwable.message}") },
                )
                audioCaptureSession = audioSession
                runCatching { audioSession.start() }
                    .onFailure { throwable ->
                        log("Audio capture disabled: ${throwable.message}")
                        audioSession.stop()
                        audioCaptureSession = null
                    }
            }

            updateStatus(
                ServerState.STREAMING,
                "Streaming via ${encoderInfo.codecName} (${encoderInfo.vendor})",
            )
            startInForeground("Streaming ${config.resolutionLabel} ${config.fps}fps")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start stream", t)
            updateStatus(ServerState.ERROR, t.message ?: "Failed to start stream")
            stopStreaming()
            stopSelf()
        }
    }

    private fun stopStreaming() {
        audioCaptureSession?.stop()
        audioCaptureSession = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        encoderSession?.stop()
        encoderSession = null
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

    private fun updatePairingPin(pin: String?, source: String) {
        val normalized = PairingPin.normalize(pin) ?: run {
            log("Rejected invalid pairing PIN from $source")
            return
        }
        currentPin = normalized
        gameStreamServer?.setPairingPin(normalized)
        log("Pairing PIN set from $source")
    }

    private fun startInForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

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
            .build()
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
    }

    private fun updateStatus(state: ServerState, message: String) {
        currentState = state
        lastMessage = message
        Log.i(TAG, "$state: $message")
    }

    companion object {
        private const val TAG = "ProjectionStreamService"
        private const val CHANNEL_ID = "apsu-stream"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_PAIRING_PIN = "pairing_pin"
        const val ACTION_START = "com.apsu.gamestream.START"
        const val ACTION_SET_PAIRING_PIN = "com.apsu.gamestream.SET_PAIRING_PIN"
        const val ACTION_STOP = "com.apsu.gamestream.STOP"

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
            return true
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
    }
}
