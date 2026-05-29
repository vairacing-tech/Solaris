package com.apsu.gamestream.model

data class ResolutionPreset(
    val label: String,
    val width: Int,
    val height: Int,
) {
    companion object {
        val DEFAULTS = listOf(
            ResolutionPreset("720p", 1280, 720),
            ResolutionPreset("1080p", 1920, 1080),
            ResolutionPreset("1440p", 2560, 1440),
        )
    }
}
