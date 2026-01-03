package com.binauralbuilder.session_builder_mobile.realtime_backend

import android.content.Context
import android.util.Log

data class PlaybackStatus(
    val positionSeconds: Float,
    val currentStep: Int,
    val isPaused: Boolean,
    val sampleRate: Int
)

object MobileApi {
    private var engine: AudioEngine? = null
    private var appContext: Context? = null

    // Called from Flutter/Android MethodChannel
    fun init(context: Context) {
        appContext = context.applicationContext

        // Initialize PresetLoader with context
        if (!PresetLoader.isInitialized()) {
            PresetLoader.initialize(appContext!!)
        }

        if (engine == null) {
            engine = AudioEngine()
        }
    }

    fun loadTrack(json: String) {
        engine?.loadTrack(json)
    }

    fun play() {
        engine?.play()
    }

    fun pause() {
        engine?.pause()
    }

    fun setPaused(paused: Boolean) {
        if (paused) pause() else play()
    }

    fun stop() {
        engine?.stop()
    }

    fun seekTo(seconds: Float) {
        engine?.seek(seconds)
    }

    fun updateTrack(json: String) {
        engine?.loadTrack(json)
    }

    fun setMasterGain(gain: Float) {
        engine?.setMasterGain(gain)
    }

    fun getCurrentPosition(): Float {
        return engine?.getCurrentPosition() ?: 0f
    }

    fun getPlaybackStatus(): PlaybackStatus? {
        val engineInstance = engine ?: return null
        return PlaybackStatus(
            positionSeconds = engineInstance.getCurrentPosition(),
            currentStep = engineInstance.getCurrentStep(),
            isPaused = engineInstance.isPaused(),
            sampleRate = engineInstance.getSampleRate()
        )
    }

    fun getElapsedSamples(): Long {
        return engine?.getElapsedSamples() ?: 0L
    }

    fun getCurrentStep(): Int {
        return engine?.getCurrentStep() ?: 0
    }

    fun isPaused(): Boolean {
        return engine?.isPaused() ?: true
    }

    fun isAudioPlaying(): Boolean {
        return engine?.isPlaying() ?: false
    }

    fun getSampleRate(): Int {
        return engine?.getSampleRate() ?: 0
    }

    fun release() {
        engine?.release()
        engine = null
    }
}
