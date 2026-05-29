package com.apsu.gamestream.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build

class AudioCaptureSession(
    private val projection: MediaProjection,
    private val onPcm: (ByteArray, Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || running) return

        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10)
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        record = audioRecord
        running = true
        audioRecord.startRecording()
        thread = Thread({
            val buffer = ByteArray(minBuffer)
            while (running) {
                try {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        onPcm(buffer.copyOf(read), System.nanoTime() / 1_000L)
                    }
                } catch (t: Throwable) {
                    onError(t)
                    stop()
                }
            }
        }, "apsu-audio-capture").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
    }
}
