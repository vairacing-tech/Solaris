package com.apsu.gamestream.service

import android.content.Intent
import com.apsu.gamestream.model.CodecPreference
import com.apsu.gamestream.model.StreamConfig

object StreamConfigExtras {
    private const val EXTRA_CODEC = "codec"
    private const val EXTRA_WIDTH = "width"
    private const val EXTRA_HEIGHT = "height"
    private const val EXTRA_FPS = "fps"
    private const val EXTRA_BITRATE = "bitrate"
    private const val EXTRA_AUDIO = "audio"

    fun put(intent: Intent, config: StreamConfig): Intent = intent.apply {
        putExtra(EXTRA_CODEC, config.codecPreference.name)
        putExtra(EXTRA_WIDTH, config.width)
        putExtra(EXTRA_HEIGHT, config.height)
        putExtra(EXTRA_FPS, config.fps)
        putExtra(EXTRA_BITRATE, config.bitrate)
        putExtra(EXTRA_AUDIO, config.audioEnabled)
    }

    fun from(intent: Intent): StreamConfig = StreamConfig(
        codecPreference = CodecPreference.fromWire(intent.getStringExtra(EXTRA_CODEC)),
        width = intent.getIntExtra(EXTRA_WIDTH, 1920),
        height = intent.getIntExtra(EXTRA_HEIGHT, 1080),
        fps = intent.getIntExtra(EXTRA_FPS, 60),
        bitrate = intent.getIntExtra(EXTRA_BITRATE, 16_000_000),
        audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, true),
    )
}
