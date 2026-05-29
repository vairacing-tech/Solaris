package com.apsu.gamestream.nativebridge

object NativeBridge {
    init {
        System.loadLibrary("gamestream_native")
    }

    external fun version(): String
}
