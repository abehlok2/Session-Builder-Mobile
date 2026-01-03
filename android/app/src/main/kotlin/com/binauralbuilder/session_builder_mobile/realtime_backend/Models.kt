package com.binauralbuilder.session_builder_mobile.realtime_backend

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.File

// --- Constants ---
const val MAX_INDIVIDUAL_GAIN = 0.60f
const val BINAURAL_MIX_SCALING = 1.0f
const val NOISE_MIX_SCALING = 1.0f

// --- Noise Params (from noise_params.rs) ---

data class NoiseSweep(
        val start_min: Float = 0f,
        val end_min: Float = 0f,
        val start_max: Float = 0f,
        val end_max: Float = 0f,
        val start_q: Float = 0f,
        val end_q: Float = 0f,
        val start_casc: Int = 0,
        val end_casc: Int = 0
)

data class NoiseColorParams(
        val exponent: Float? = null,
        @SerializedName("high_exponent") val highExponent: Float? = null,
        @SerializedName("distribution_curve") val distributionCurve: Float? = null,
        val lowcut: Float? = null,
        val highcut: Float? = null,
        val amplitude: Float? = null,
        val seed: Long? = null,
        val name: String? = null
)

data class NoiseParams(
        val duration_seconds: Float = 0f,
        val sample_rate: Long = 0, // u32 in Rust, Long in Kotlin roughly
        val lfo_waveform: String = "",
        val transition: Boolean = false,
        val lfo_freq: Float = 0f,
        val start_lfo_freq: Float = 0f,
        val end_lfo_freq: Float = 0f,
        val sweeps: List<NoiseSweep> = emptyList(),
        @SerializedName("noise_parameters", alternate = ["color_params"])
        val noise_parameters: NoiseColorParams? = null,
        val start_lfo_phase_offset_deg: Float = 0f,
        val end_lfo_phase_offset_deg: Float = 0f,
        val start_intra_phase_offset_deg: Float = 0f,
        val end_intra_phase_offset_deg: Float = 0f,
        val initial_offset: Float = 0f,
        val post_offset: Float = 0f,
        val input_audio_path: String = "",
        val start_time: Float = 0f,
        val fade_in: Float = 0f,
        val fade_out: Float = 0f,
        val amp_envelope: List<FloatArray> = emptyList(), // [f32; 2] -> FloatArray
        val static_notches: List<JsonElement> = emptyList()
) {
    companion object {
        fun fromMap(map: Map<String, Any>): NoiseParams {
            val gson = com.google.gson.Gson()
            return gson.fromJson(gson.toJsonTree(map), NoiseParams::class.java)
        }
    }
}

// --- Models (from models.rs) ---

data class VolumeEnvelope(
        @SerializedName("type") val envelopeType: String,
        val params: Map<String, Double>
)

data class VoiceData(
        @SerializedName("synthFunctionName", alternate = ["synth_function"])
        val synthFunctionName: String?,
        @SerializedName("parameters") val params: Map<String, JsonElement> = emptyMap(),
        @SerializedName("volumeEnvelope") val volumeEnvelope: List<List<Float>>? = null,
        @SerializedName("isTransition", alternate = ["is_transition"])
        val isTransition: Boolean = false,
        val description: String = "",
        @SerializedName("voice_type") val voiceType: String? = "binaural"
) {
    init {
        if (voiceType == null) {
            Log.w(
                    "VoiceData",
                    "Null voice_type for synthFunctionName=$synthFunctionName description=$description params=$params"
            )
        }
    }
}

data class StepData(
        @SerializedName("Duration", alternate = ["durationSeconds", "stepDuration"])
        val duration: Double,
        val description: String = "",
        val start: Double? = null,
        val voices: List<VoiceData>,
        @SerializedName("binaural_volume") val binauralVolume: Float = MAX_INDIVIDUAL_GAIN,
        @SerializedName("noise_volume") val noiseVolume: Float = MAX_INDIVIDUAL_GAIN,
        @SerializedName("normalization_level") val normalizationLevel: Float = 0.95f
)

data class GlobalSettings(
        @SerializedName("sampleRate", alternate = ["sample_rate"]) val sampleRate: Long,
        @SerializedName("crossfadeDuration", alternate = ["crossfade_duration"])
        val crossfadeDuration: Double = 3.0,
        @SerializedName("crossfadeCurve", alternate = ["crossfade_curve"])
        val crossfadeCurve: String = "linear",
        @SerializedName("outputFilename") val outputFilename: String? = null,
        @SerializedName("normalizationLevel", alternate = ["normalization_level"])
        val normalizationLevel: Float = 0.95f
)

data class ClipData(
        @SerializedName("path", alternate = ["file"])
        var filePath: String, // Var for path resolution
        @SerializedName("start_time") val start: Double = 0.0,
        @SerializedName("gain", alternate = ["amp"]) val amp: Float = 1.0f
)

data class BackgroundNoiseData(
        @SerializedName("file", alternate = ["file_path", "params_path", "noise_file"])
        var filePath: String, // Var for path resolution
        @SerializedName("gain", alternate = ["amp"]) val amp: Float = 1.0f,
        val params: NoiseParams? = null,
        @SerializedName("start_time_seconds") val startTime: Double = 0.0,
        @SerializedName("fade_in") val fadeIn: Double = 0.0,
        @SerializedName("fade_out") val fadeOut: Double = 0.0,
        @SerializedName("amp_envelope") val ampEnvelope: List<FloatArray> = emptyList()
)

data class TrackData(
        @SerializedName("globalSettings", alternate = ["global", "global_settings"])
        val globalSettings: GlobalSettings,
        @SerializedName("progression", alternate = ["steps"]) val steps: List<StepData>,
        @SerializedName("overlay_clips") val clips: MutableList<ClipData> = mutableListOf(),
        @SerializedName("noise", alternate = ["background_noise"])
        var backgroundNoise: BackgroundNoiseData? = null
) {
    fun resolveRelativePaths(base: File) {
        backgroundNoise?.let { noise ->
            if (noise.filePath.isNotEmpty()) {
                val p = File(noise.filePath)
                if (!p.isAbsolute) {
                    noise.filePath = File(base, noise.filePath).absolutePath
                }
            }
        }
        clips.forEach { clip ->
            if (clip.filePath.isNotEmpty()) {
                val p = File(clip.filePath)
                if (!p.isAbsolute) {
                    clip.filePath = File(base, clip.filePath).absolutePath
                }
            }
        }
    }
}
