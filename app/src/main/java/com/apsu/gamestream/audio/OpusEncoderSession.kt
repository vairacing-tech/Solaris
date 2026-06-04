package com.apsu.gamestream.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

class OpusEncoderSession(
    private val bitrate: Int = DEFAULT_BITRATE,
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val frames = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_FRAMES)
    private val chunkLock = Any()
    private val staging = ByteArray(FRAME_BYTES)
    private var stagingOffset = 0
    private var codec: MediaCodec? = null
    private var workerThread: Thread? = null
    private var droppedFrames = 0L

    @Volatile var codecName: String? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val codecInfo = selectOpusEncoder()
        codecName = codecInfo.name

        val mediaCodec = MediaCodec.createByCodecName(codecInfo.name)
        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
        }
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        codec = mediaCodec

        workerThread = thread(name = "apsu-opus-encoder", isDaemon = true) {
            runEncoder(mediaCodec)
        }
        onLog("Opus audio encoder ${codecInfo.name} started at ${bitrate / 1000} Kbps")
    }

    fun queuePcm(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return
        synchronized(chunkLock) {
            var offset = 0
            while (offset < pcm.size) {
                val copyLength = min(FRAME_BYTES - stagingOffset, pcm.size - offset)
                System.arraycopy(pcm, offset, staging, stagingOffset, copyLength)
                stagingOffset += copyLength
                offset += copyLength

                if (stagingOffset == FRAME_BYTES) {
                    offerFrame(staging.copyOf())
                    stagingOffset = 0
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        val worker = workerThread
        if (Thread.currentThread() != worker) {
            worker?.interrupt()
            runCatching { worker?.join(1_000) }
        }
        workerThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecName = null
        frames.clear()
        synchronized(chunkLock) {
            stagingOffset = 0
        }
    }

    private fun offerFrame(frame: ByteArray) {
        if (frames.offer(frame)) return
        frames.poll()
        if (frames.offer(frame)) {
            droppedFrames++
            if (droppedFrames == 1L || droppedFrames % DROPPED_FRAME_LOG_INTERVAL == 0L) {
                onLog("Dropped $droppedFrames stale PCM audio frames to keep latency low")
            }
        }
    }

    private fun runEncoder(mediaCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var nextPresentationTimeUs = 0L
        try {
            while (running.get() || frames.isNotEmpty()) {
                val frame = frames.poll(10, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    if (queueInputFrame(mediaCodec, frame, nextPresentationTimeUs)) {
                        nextPresentationTimeUs += FRAME_DURATION_US
                    }
                }
                drainOutput(mediaCodec, bufferInfo)
            }
            drainOutput(mediaCodec, bufferInfo)
        } catch (t: Throwable) {
            if (running.get()) {
                onError(t)
            }
        }
    }

    private fun queueInputFrame(
        mediaCodec: MediaCodec,
        frame: ByteArray,
        presentationTimeUs: Long,
    ): Boolean {
        while (running.get()) {
            val inputIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) {
                drainOutput(mediaCodec, MediaCodec.BufferInfo())
                continue
            }
            val inputBuffer = mediaCodec.getInputBuffer(inputIndex)
                ?: throw IllegalStateException("Opus encoder returned null input buffer")
            if (inputBuffer.capacity() < frame.size) {
                throw IllegalStateException("Opus input buffer too small: ${inputBuffer.capacity()} < ${frame.size}")
            }
            inputBuffer.clear()
            inputBuffer.put(frame)
            mediaCodec.queueInputBuffer(inputIndex, 0, frame.size, presentationTimeUs, 0)
            return true
        }
        return false
    }

    private fun drainOutput(mediaCodec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            when (val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onLog("Opus output format ${mediaCodec.outputFormat}")
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (outputIndex < 0) return
                    val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                    if (
                        outputBuffer != null &&
                        bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val packet = ByteArray(bufferInfo.size)
                        outputBuffer.get(packet)
                        onPacket(packet, PACKET_DURATION_MS)
                    }
                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
        const val PACKET_DURATION_MS = 5
        const val PCM_BYTES_PER_SAMPLE = 2
        const val FRAME_SAMPLES_PER_CHANNEL = SAMPLE_RATE * PACKET_DURATION_MS / 1_000
        const val FRAME_BYTES = FRAME_SAMPLES_PER_CHANNEL * CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val DEFAULT_BITRATE = 128_000
        private const val FRAME_DURATION_US = PACKET_DURATION_MS * 1_000L
        private const val MAX_QUEUED_FRAMES = 12
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 0L
        private const val DROPPED_FRAME_LOG_INTERVAL = 100L

        fun selectOpusEncoder(): MediaCodecInfo {
            val encoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { codecInfo ->
                    codecInfo.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
                }
                .sortedWith(
                    compareBy<MediaCodecInfo> { if (it.isSoftwareOnly) 1 else 0 }
                        .thenBy { it.name },
                )
                .toList()
            return encoders.firstOrNull()
                ?: throw IllegalStateException("No MediaCodec Opus encoder ($MIME) available")
        }
    }
}
