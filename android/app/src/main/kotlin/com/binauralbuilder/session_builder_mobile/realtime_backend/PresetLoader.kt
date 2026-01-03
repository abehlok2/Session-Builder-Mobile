package com.binauralbuilder.session_builder_mobile.realtime_backend

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.InputStreamReader

/**
 * Loads and manages presets from assets/presets.json All noise presets must be defined in the JSON
 * file - no defaults are used.
 */
object PresetLoader {
    private var presets: JsonObject? = null
    private val gson = Gson()
    private const val TAG = "PresetLoader"

    /**
     * Initialize the preset loader with Android context Must be called before using any preset
     * functions
     */
    fun initialize(context: Context) {
        try {
            val loader = io.flutter.FlutterInjector.instance().flutterLoader()
            val key = loader.getLookupKeyForAsset("assets/presets.json")
            val inputStream = context.assets.open(key)
            val reader = InputStreamReader(inputStream)
            presets = gson.fromJson(reader, JsonObject::class.java)
            reader.close()
            Log.i(TAG, "Presets loaded successfully from $key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load presets.json from assets", e)
            throw IllegalStateException("Failed to load presets.json: ${e.message}", e)
        }
    }

    /**
     * Get noise preset parameters for a given noise type Returns null if the preset is not found No
     * defaults are provided - all parameters must be in presets.json
     */
    fun getNoisePreset(noiseType: String): NoisePreset? {
        val presetsObj =
                presets
                        ?: run {
                            Log.e(TAG, "Presets not initialized. Call initialize() first.")
                            return null
                        }

        // Navigate to noise presets: presets.json -> "noise" -> noiseType
        val noiseSection =
                presetsObj.getAsJsonObject("noise")
                        ?: run {
                            Log.w(TAG, "No 'noise' section found in presets.json")
                            return null
                        }

        val presetObj =
                noiseSection.getAsJsonObject(noiseType)
                        ?: run {
                            Log.w(TAG, "No preset found for noise type: $noiseType")
                            return null
                        }

        return try {
            // Navigate to the nested "noise_parameters" object
            val noiseParams =
                    presetObj.getAsJsonObject("noise_parameters")
                            ?: throw IllegalArgumentException(
                                    "Missing 'noise_parameters' section in preset for $noiseType"
                            )

            val exponent =
                    noiseParams.get("exponent")?.asFloat
                            ?: throw IllegalArgumentException(
                                    "Missing 'exponent' in noise_parameters for $noiseType"
                            )
            val highExponent =
                    noiseParams.get("high_exponent")?.asFloat
                            ?: throw IllegalArgumentException(
                                    "Missing 'high_exponent' in noise_parameters for $noiseType"
                            )
            val distributionCurve =
                    noiseParams.get("distribution_curve")?.asFloat
                            ?: throw IllegalArgumentException(
                                    "Missing 'distribution_curve' in noise_parameters for $noiseType"
                            )
            val lowcut = noiseParams.get("lowcut")?.asFloat
            val highcut = noiseParams.get("highcut")?.asFloat
            val amplitude =
                    noiseParams.get("amplitude")?.asFloat
                            ?: throw IllegalArgumentException(
                                    "Missing 'amplitude' in noise_parameters for $noiseType"
                            )

            NoisePreset(
                    exponent = exponent,
                    highExponent = highExponent,
                    distributionCurve = distributionCurve,
                    lowcut = lowcut,
                    highcut = highcut,
                    amplitude = amplitude
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing preset for $noiseType", e)
            null
        }
    }

    /** Check if presets are loaded */
    fun isInitialized(): Boolean = presets != null
}
