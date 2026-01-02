package com.binauralbuilder.session_builder_mobile.realtime_backend

import android.util.Log

object MobileApi {
    private var engine: AudioEngine? = null

    // Called from Flutter/Android MethodChannel
    fun init() {
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
    
    fun release() {
        engine?.release()
        engine = null
    }
}
