package com.localaux.soundshare.audio

class NativeEngine {
    external fun startSender(ip: String, port: Int): Boolean
    external fun startReceiver(port: Int): Boolean
    external fun stop()

    companion object {
        init { System.loadLibrary("soundshare_engine") }
    }
}