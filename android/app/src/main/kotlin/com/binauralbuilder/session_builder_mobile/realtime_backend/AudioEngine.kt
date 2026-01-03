package com.binauralbuilder.session_builder_mobile.realtime_backend

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioEngine {
    private var audioTrack: AudioTrack? = null
    private var scheduler: TrackScheduler? = null
    private var playbackThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val gson = Gson()

    // Sample rate is determined from track data, not hardcoded
    private var sampleRate: Int = 44100  // Will be set from track data

    fun loadTrack(json: String) {
        try {
            val trackData = gson.fromJson(json, TrackData::class.java)

            // Resolve relative paths relative to the app's external files directory
            val context = MobileApi.appContext
            if (context != null) {
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                trackData.resolveRelativePaths(baseDir)
            }

            // Use sample rate from track data instead of hardcoded value
            val trackSampleRate = trackData.globalSettings.sampleRate.toInt()
            sampleRate = trackSampleRate

            synchronized(this) {
                if (scheduler == null) {
                    scheduler = TrackScheduler(trackData, trackSampleRate.toFloat())
                } else {
                    scheduler?.updateTrack(trackData)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Failed to load track", e)
        }
    }

    fun play() {
        if (isRunning.get()) {
            isPaused.set(false)
            scheduler?.paused = false
            return
        }

        startAudio()
    }

    fun pause() {
        isPaused.set(true)
        scheduler?.paused = true
    }

    fun stop() {
        isRunning.set(false)
        try {
            playbackThread?.join(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        playbackThread = null

        audioTrack?.stop()
        audioTrack?.flush()
        // Don't release if we want to reuse, but stop implies full stop.
        // We can keep track valid.
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun seek(timeSeconds: Float) {
        synchronized(this) {
            scheduler?.seekTo(timeSeconds)
        }
    }

    private fun startAudio() {
        if (audioTrack == null) {
            // Calculate buffer size based on actual sample rate from track data
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).let { min ->
                if (min > 0) min * 4 else 8192 // Ensure enough buffer
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        }

        audioTrack?.play()
        isRunning.set(true)
        isPaused.set(false)
        scheduler?.paused = false

        playbackThread = thread(start = true, name = "AudioEngineThread") {
            val bufferSamples = 2048 // Per channel? No, total float count usually
            val buffer = FloatArray(bufferSamples) // Stereo frame count = 1024

            while (isRunning.get()) {
                if (isPaused.get()) {
                    Thread.sleep(10)
                    continue
                }

                synchronized(this) {
                    val sched = scheduler
                    if (sched != null) {
                        sched.processBlock(buffer)
                    } else {
                        buffer.fill(0f)
                    }
                }

                // Write to AudioTrack
                // BLOCKING CALL
                val written = audioTrack?.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    ?: 0
                if (written < 0) {
                    Log.e("AudioEngine", "AudioTrack write error: $written")
                    break
                }
            }
        }
    }

    fun setMasterGain(gain: Float) {
        synchronized(this) {
            scheduler?.masterGain = gain
        }
    }

    fun getCurrentPosition(): Float {
        return (scheduler?.absoluteSample ?: 0).toFloat() / sampleRate
    }

    fun getElapsedSamples(): Long {
        return scheduler?.absoluteSample ?: 0
    }

    fun getCurrentStep(): Int {
        return scheduler?.currentStepIndex ?: 0
    }

    fun isPaused(): Boolean {
        return isPaused.get()
    }

    fun isPlaying(): Boolean {
        return isRunning.get() && !isPaused.get()
    }

    fun getSampleRate(): Int {
        return sampleRate
    }
}
