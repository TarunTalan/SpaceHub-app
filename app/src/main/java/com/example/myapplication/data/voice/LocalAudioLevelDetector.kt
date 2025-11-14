package com.example.myapplication.data.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

/**
 * Samples microphone audio via AudioRecord and emits an approximate RMS level.
 * Simple and effective for detecting when the local user is speaking.
 */
class LocalAudioLevelDetector(
    private val sampleRate: Int = 16000,
    private val bufferMs: Int = 200,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val TAG = "LocalAudioLevelDetector"
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    fun start(onLevel: (Float) -> Unit) {
        stop()
        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, sampleRate * bufferMs / 1000 * 2)
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            recorder?.startRecording()
            val buffer = ShortArray(bufSize / 2)
            job = scope.launch {
                while (isActive) {
                    val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0L
                        for (i in 0 until read) {
                            val v = buffer[i].toInt()
                            sum += (v * v).toLong()
                        }
                        val rms = kotlin.math.sqrt(sum.toDouble() / read).toFloat()
                        // normalize rms to 0-32767
                        onLevel(rms)
                    }
                    delay(bufferMs.toLong())
                }
            }
            Log.d(TAG, "Started audio level detector")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start audio detector: ${e.message}")
            stop()
        }
    }

    fun stop() {
        try {
            job?.cancel()
            job = null
            recorder?.stop()
            recorder?.release()
            recorder = null
        } catch (_: Exception) {}
    }
}

