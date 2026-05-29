package com.apsu.gamestream.model

enum class ServerState {
    IDLE,
    WAITING_FOR_PROJECTION,
    READY,
    STREAMING,
    ERROR,
}
