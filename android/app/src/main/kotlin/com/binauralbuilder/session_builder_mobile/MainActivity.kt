package com.binauralbuilder.session_builder_mobile

import com.ryanheise.audioservice.AudioServiceActivity

import com.binauralbuilder.session_builder_mobile.realtime_backend.MobileApi
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : AudioServiceActivity() {
    private val CHANNEL = "com.binauralbuilder.session_builder_mobile/audio"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "init" -> {
                    MobileApi.init()
                    result.success(null)
                }
                "loadTrack" -> {
                    val json = call.argument<String>("json")
                    if (json != null) {
                        MobileApi.loadTrack(json)
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "JSON argument missing", null)
                    }
                }
                "updateTrack" -> {
                    val json = call.argument<String>("json")
                    if (json != null) {
                        MobileApi.updateTrack(json)
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "JSON argument missing", null)
                    }
                }
                "play" -> {
                    MobileApi.play()
                    result.success(null)
                }
                "pause" -> {
                    MobileApi.pause()
                    result.success(null)
                }
                "stop" -> {
                    MobileApi.stop()
                    result.success(null)
                }
                "seekTo" -> {
                    val time = call.argument<Double>("time")
                    if (time != null) {
                        MobileApi.seekTo(time.toFloat())
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "Time argument missing", null)
                    }
                }
                "setMasterGain" -> {
                    val gain = call.argument<Double>("gain")
                    if (gain != null) {
                        MobileApi.setMasterGain(gain.toFloat())
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "Gain argument missing", null)
                    }
                }
                "getCurrentPosition" -> {
                    val pos = MobileApi.getCurrentPosition()
                    result.success(pos.toDouble())
                }
                "getPlaybackPosition" -> {
                    val pos = MobileApi.getCurrentPosition()
                    result.success(pos.toDouble())
                }
                "getElapsedSamples" -> {
                    val samples = MobileApi.getElapsedSamples()
                    result.success(samples)
                }
                "getCurrentStep" -> {
                    val step = MobileApi.getCurrentStep()
                    result.success(step)
                }
                "getIsPaused" -> {
                    result.success(MobileApi.isPaused())
                }
                "isAudioPlaying" -> {
                    result.success(MobileApi.isAudioPlaying())
                }
                "getSampleRate" -> {
                    result.success(MobileApi.getSampleRate())
                }
                "getPlaybackStatus" -> {
                    val status = MobileApi.getPlaybackStatus()
                    if (status == null) {
                        result.success(null)
                    } else {
                        result.success(
                            mapOf(
                                "positionSeconds" to status.positionSeconds,
                                "currentStep" to status.currentStep,
                                "isPaused" to status.isPaused,
                                "sampleRate" to status.sampleRate
                            )
                        )
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
