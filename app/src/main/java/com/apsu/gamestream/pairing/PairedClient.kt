package com.apsu.gamestream.pairing

data class PairedClient(
    val uuid: String,
    val name: String,
    val certificatePem: String,
    val pairedAtEpochMillis: Long,
)
