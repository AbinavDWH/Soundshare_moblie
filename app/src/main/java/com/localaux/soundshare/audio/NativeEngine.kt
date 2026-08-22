package com.localaux.soundshare.audio

class NativeEngine {
    external fun startSender(ip: String, port: Int, codec: Int, bitrateKbps: Int, useMic: Boolean): Boolean
    external fun feedAudio(samples: ShortArray, count: Int): Int
    external fun startReceiver(port: Int, bufferMs: Int): Boolean
    external fun stop()

    companion object {
        init { System.loadLibrary("soundshare_engine") }
    }
}