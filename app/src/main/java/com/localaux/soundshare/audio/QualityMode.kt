package com.localaux.soundshare.audio

/** Design doc §9 quality modes */
enum class QualityMode(
    val label: String,
    val codec: Int,          // 0 = Opus, 1 = PCM (§8 payload type)
    val bitrateKbps: Int,
    val bufferMs: Int        // recommended default buffer for the slider
) {
    POWER_SAVING("Power Saving · Opus 64k", 0, 64, 500),
    BALANCED("Balanced · Opus 128k", 0, 128, 300),
    LOW_LATENCY("Low Latency · Opus 64k", 0, 64, 100),
    LAN_PCM("Hi-Res LAN · PCM", 1, 0, 300000);

    companion object {
        fun defaultFor(isLowEnd: Boolean) = if (isLowEnd) POWER_SAVING else BALANCED
    }
}