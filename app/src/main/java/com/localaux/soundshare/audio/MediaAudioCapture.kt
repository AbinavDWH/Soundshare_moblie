package com.localaux.soundshare.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** Captures internal media audio (Android 10+, §12). Needs user consent + RECORD_AUDIO. */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaAudioCapture(
    private val context: Context,
    private val projectionData: Intent,
    private val onChunk: (ShortArray, Int) -> Unit
) {
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("WrongConstant")
    fun start(): Boolean {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        val p = mpm.getMediaProjection(-1, projectionData) ?: return false
        p.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        projection = p

        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) { stop(); return false }
        record = rec
        rec.startRecording()
        running = true
        
        // FIX 1: Assign the Thread object, then start it using .also { it.start() }
        thread = Thread {
            val buf = ShortArray(960)
            while (running) {
                // FIX 2: read() requires 3 arguments: (array, offset, size)
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf, n) else if (n < 0) break
            }
        }.also { it.start() }
        
        return true
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) { }
        thread?.join(500)
        record?.release(); record = null
        projection?.stop(); projection = null
    }
}