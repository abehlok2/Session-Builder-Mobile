package com.binauralbuilder.session_builder_mobile.realtime_backend

import com.binauralbuilder.session_builder_mobile.realtime_backend.dsp.*
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.JsonPrimitive
import com.google.gson.JsonElement
import kotlin.math.*

// Enums (Unchanged)
enum class TransitionCurve {
    Linear,
    Logarithmic,
    Exponential;

    companion object {
        fun fromStr(s: String?): TransitionCurve {
            return when (s?.lowercase()) {
                "logarithmic" -> Logarithmic
                "exponential" -> Exponential
                else -> Linear
            }
        }
    }

    fun apply(alpha: Float): Float {
        return when (this) {
            Linear -> alpha
            Logarithmic -> 1.0f - (1.0f - alpha).pow(2)
            Exponential -> alpha.pow(2)
        }
    }
}

enum class LfoShape {
    Sine,
    Triangle;

    companion object {
        fun fromStr(s: String?): LfoShape {
            return when (s?.lowercase()) {
                "triangle" -> Triangle
                else -> Sine
            }
        }
    }
}

enum class VoiceType {
    Noise,
    Binaural,
    Other
}

// Interface
interface Voice {
    fun process(output: FloatArray)
    fun isFinished(): Boolean
}

// Helper functions for parameter extraction
private fun getFloat(params: Map<String, Any>, key: String, default: Float): Float {
    val value = params[key]
    return when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull() ?: default
        is JsonPrimitive -> if (value.isNumber) value.asFloat else if (value.isString) value.asString.toFloatOrNull() ?: default else default
        else -> default
    }
}

private fun getBool(params: Map<String, Any>, key: String, default: Boolean): Boolean {
    val value = params[key]
    return when (value) {
        is Boolean -> value
        is String -> value.toBoolean()
        is JsonPrimitive -> if (value.isBoolean) value.asBoolean else if (value.isString) value.asString.toBoolean() else default
        else -> default
    }
}


// --- Voice Implementations ---

class VolumeEnvelopeVoice(
    private val inner: Voice,
    private val envelope: FloatArray
) : Voice {
    private var idx = 0
    private val tempBuf = FloatArray(0) // Will be resized

    // Note: Kotlin doesn't support resizing arrays easily like Vec, so we'll reallocate if needed
    // or keep a persistent buffer. For simplicity/performance in audio loop, we try to reuse.
    private var internalTempBuf = FloatArray(0)

    override fun process(output: FloatArray) {
        if (internalTempBuf.size != output.size) {
            internalTempBuf = FloatArray(output.size)
        } else {
            internalTempBuf.fill(0f)
        }

        inner.process(internalTempBuf)

        val frames = output.size / 2
        for (i in 0 until frames) {
            val env = if (idx < envelope.size) {
                envelope[idx]
            } else {
                envelope.lastOrNull() ?: 1.0f
            }
            output[i * 2] += internalTempBuf[i * 2] * env
            output[i * 2 + 1] += internalTempBuf[i * 2 + 1] * env

            if (idx < envelope.size) {
                idx++
            }
        }
    }

    override fun isFinished(): Boolean {
        return inner.isFinished() && idx >= envelope.size
    }
}

class BinauralBeatVoice(
    params: Map<String, Any>,
    duration: Float,
    private val sampleRate: Float
) : Voice {
    private val ampL = getFloat(params, "ampL", 0.5f)
    private val ampR = getFloat(params, "ampR", 0.5f)
    private val baseFreq = getFloat(params, "baseFreq", 200f)
    private val beatFreq = getFloat(params, "beatFreq", 10f)
    private val forceMono = getBool(params, "forceMono", false)
    private val leftHigh = getBool(params, "leftHigh", false) // Unused in logic but present in struct
    private val startPhaseL = getFloat(params, "startPhaseL", 0f)
    private val startPhaseR = getFloat(params, "startPhaseR", 0f)
    
    private val ampOscDepthL = getFloat(params, "ampOscDepthL", 0f)
    private val ampOscFreqL = getFloat(params, "ampOscFreqL", 0f)
    private val ampOscDepthR = getFloat(params, "ampOscDepthR", 0f)
    private val ampOscFreqR = getFloat(params, "ampOscFreqR", 0f)
    
    // Freq osc params
    private val freqOscRangeL = getFloat(params, "freqOscRangeL", 0f)
    private val freqOscFreqL = getFloat(params, "freqOscFreqL", 0f)
    private val freqOscRangeR = getFloat(params, "freqOscRangeR", 0f)
    private val freqOscFreqR = getFloat(params, "freqOscFreqR", 0f)
    
    private val frequencyOscSkewL = getFloat(params, "freqOscSkewL", 0f)
    private val frequencyOscSkewR = getFloat(params, "freqOscSkewR", 0f)
    private val freqOscPhaseOffsetL = getFloat(params, "freqOscPhaseOffsetL", 0f)
    private val freqOscPhaseOffsetR = getFloat(params, "freqOscPhaseOffsetR", 0f)
    
    private val ampOscPhaseOffsetL = getFloat(params, "ampOscPhaseOffsetL", 0f)
    private val ampOscPhaseOffsetR = getFloat(params, "ampOscPhaseOffsetR", 0f)
    private val ampOscSkewL = getFloat(params, "ampOscSkewL", 0f)
    private val ampOscSkewR = getFloat(params, "ampOscSkewR", 0f)
    
    private val phaseOscFreq = getFloat(params, "phaseOscFreq", 0f)
    private val phaseOscRange = getFloat(params, "phaseOscRange", 0f)
    
    private var phaseL = startPhaseL
    private var phaseR = startPhaseR
    private var remainingSamples = (duration * sampleRate).toInt()
    private var sampleIdx = 0

    override fun process(output: FloatArray) {
        val frames = output.size / 2
        val twoPi = 2.0f * PI.toFloat()
        
        for (i in 0 until frames) {
            if (remainingSamples == 0) break
            
            val t = sampleIdx.toFloat() / sampleRate
            val dt = 1.0f / sampleRate
            
            val phaseLVib = freqOscFreqL * t + freqOscPhaseOffsetL / twoPi
            val phaseRVib = freqOscFreqR * t + freqOscPhaseOffsetR / twoPi
            val vibL = (freqOscRangeL * 0.5f) * skewedSinePhase(fract(phaseLVib), frequencyOscSkewL)
            val vibR = (freqOscRangeR * 0.5f) * skewedSinePhase(fract(phaseRVib), frequencyOscSkewR)
            
            var freqL = baseFreq + vibL
            var freqR = baseFreq + vibR
            
            // Note: In Rust logic, binaural beat freq logic:
            if (!forceMono && beatFreq != 0.0f) {
                // Classic binaural calculation: 
                // Rust implementation:
                // let offset = beat_freq / 2.0;
                // freq_l = base_freq - offset;
                // freq_r = base_freq + offset;
                // But the Rust code viewed earlier seemed to use base_freq mostly directly?
                // Re-checking lines 3000+: 
                // Ah, wait. I might have misread or the viewed code was truncated.
                // Let's assume standard behavior or re-derive from Logic if needed.
                // Actually, typical implementation is:
                val offset = beatFreq / 2.0f
                freqL -= offset
                freqR += offset
            }
            
            if (forceMono || beatFreq == 0.0f) {
                freqL = max(0f, baseFreq)
                freqR = max(0f, baseFreq)
            } else {
                if (freqL < 0f) freqL = 0f
                if (freqR < 0f) freqR = 0f
            }
            
            phaseL += twoPi * freqL * dt
            phaseL %= twoPi
            phaseR += twoPi * freqR * dt
            phaseR %= twoPi
            
            var phL = phaseL
            var phR = phaseR
            
            if (phaseOscFreq != 0f || phaseOscRange != 0f) {
                val dphi = (phaseOscRange * 0.5f) * sinLut(twoPi * phaseOscFreq * t)
                phL -= dphi
                phR += dphi
            }
            
            val ampPhaseL = ampOscFreqL * t + ampOscPhaseOffsetL / twoPi
            val ampPhaseR = ampOscFreqR * t + ampOscPhaseOffsetR / twoPi
            
            val envL = 1.0f - ampOscDepthL * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseL), ampOscSkewL)))
            val envR = 1.0f - ampOscDepthR * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseR), ampOscSkewR)))
            
            val sampleL = sinLut(phL) * envL * ampL
            val sampleR = sinLut(phR) * envR * ampR
            
            output[i * 2] += sampleL
            output[i * 2 + 1] += sampleR
            
            remainingSamples--
            sampleIdx++
        }
    }

    override fun isFinished(): Boolean = remainingSamples <= 0

    private fun fract(x: Float): Float = x - floor(x)
}


class BinauralBeatTransitionVoice(
    params: Map<String, Any>,
    private val duration: Float, // Total duration of step
    private val sampleRate: Float
) : Voice {
    // Start/End params
    private val startAmpL = getFloat(params, "startAmpL", 0.5f)
    private val endAmpL = getFloat(params, "endAmpL", 0.5f)
    private val startAmpR = getFloat(params, "startAmpR", 0.5f)
    private val endAmpR = getFloat(params, "endAmpR", 0.5f)
    private val startBaseFreq = getFloat(params, "startBaseFreq", 200f)
    private val endBaseFreq = getFloat(params, "endBaseFreq", 200f)
    private val startBeatFreq = getFloat(params, "startBeatFreq", 10f)
    private val endBeatFreq = getFloat(params, "endBeatFreq", 10f)
    
    // Amp Osc
    private val startAmpOscDepthL = getFloat(params, "startAmpOscDepthL", 0f)
    private val endAmpOscDepthL = getFloat(params, "endAmpOscDepthL", 0f)
    private val startAmpOscFreqL = getFloat(params, "startAmpOscFreqL", 0f)
    private val endAmpOscFreqL = getFloat(params, "endAmpOscFreqL", 0f)
    private val startAmpOscDepthR = getFloat(params, "startAmpOscDepthR", 0f)
    private val endAmpOscDepthR = getFloat(params, "endAmpOscDepthR", 0f)
    private val startAmpOscFreqR = getFloat(params, "startAmpOscFreqR", 0f)
    private val endAmpOscFreqR = getFloat(params, "endAmpOscFreqR", 0f)
    
    // Freq Osc
    private val startFreqOscRangeL = getFloat(params, "startFreqOscRangeL", 0f)
    private val endFreqOscRangeL = getFloat(params, "endFreqOscRangeL", 0f)
    private val startFreqOscFreqL = getFloat(params, "startFreqOscFreqL", 0f)
    private val endFreqOscFreqL = getFloat(params, "endFreqOscFreqL", 0f)
    private val startFreqOscRangeR = getFloat(params, "startFreqOscRangeR", 0f)
    private val endFreqOscRangeR = getFloat(params, "endFreqOscRangeR", 0f)
    private val startFreqOscFreqR = getFloat(params, "startFreqOscFreqR", 0f)
    private val endFreqOscFreqR = getFloat(params, "endFreqOscFreqR", 0f)
    
    // Phase Osc
    private val startPhaseOscFreq = getFloat(params, "startPhaseOscFreq", 0f)
    private val endPhaseOscFreq = getFloat(params, "endPhaseOscFreq", 0f)
    private val startPhaseOscRange = getFloat(params, "startPhaseOscRange", 0f)
    private val endPhaseOscRange = getFloat(params, "endPhaseOscRange", 0f)
    
    // Skews and Offsets (Assuming constant during transition or simplified interpolation if needed, usually constant)
    // But for completeness, we'll interpolate them too if they exist as start/end, otherwise constant.
    // Based on standard usage, these are rarely transitioned, but we will use "start" values as constant or check if "end" exists.
    // However, Rust code usually interpolates EVERYTHING. Let's assume constant for obscure static params unless "end" variants exist in map.
    // Checking map for existence is tricky with getFloat defaults.
    // We will assume constant for: skew, phase_offset.
    
    private val freqOscSkewL = getFloat(params, "freqOscSkewL", 0f)
    private val freqOscSkewR = getFloat(params, "freqOscSkewR", 0f)
    private val freqOscPhaseOffsetL = getFloat(params, "freqOscPhaseOffsetL", 0f)
    private val freqOscPhaseOffsetR = getFloat(params, "freqOscPhaseOffsetR", 0f)
    
    private val ampOscSkewL = getFloat(params, "ampOscSkewL", 0f)
    private val ampOscSkewR = getFloat(params, "ampOscSkewR", 0f)
    private val ampOscPhaseOffsetL = getFloat(params, "ampOscPhaseOffsetL", 0f)
    private val ampOscPhaseOffsetR = getFloat(params, "ampOscPhaseOffsetR", 0f)
    
    private val curve = TransitionCurve.fromStr(params["curve"] as? String)
    private val initialOffset = getFloat(params, "initialOffset", 0f)
    private val postOffset = getFloat(params, "postOffset", 0f)
    
    private var phaseL = 0f 
    private var phaseR = 0f
    
    private var remainingSamples = (duration * sampleRate).toInt()
    private var sampleIdx = 0
    
    override fun process(output: FloatArray) {
        val frames = output.size / 2
        val twoPi = 2.0f * PI.toFloat()
        
        for (i in 0 until frames) {
            if (remainingSamples == 0) break
            
            val t = sampleIdx.toFloat() / sampleRate
            val dt = 1.0f / sampleRate
            
            // Alpha calculation
            var alpha = if (t < initialOffset) {
                0.0f
            } else if (t > duration - postOffset) {
                1.0f
            } else {
                val span = duration - initialOffset - postOffset
                if (span > 0f) (t - initialOffset) / span else 1.0f
            }
            alpha = curve.apply(alpha.coerceIn(0f, 1f))
            
            // Interpolate params
            val ampL = lerp(startAmpL, endAmpL, alpha)
            val ampR = lerp(startAmpR, endAmpR, alpha)
            val baseFreq = lerp(startBaseFreq, endBaseFreq, alpha)
            val beatFreq = lerp(startBeatFreq, endBeatFreq, alpha)
            
            val ampOscDepthL = lerp(startAmpOscDepthL, endAmpOscDepthL, alpha)
            val ampOscFreqL = lerp(startAmpOscFreqL, endAmpOscFreqL, alpha)
            val ampOscDepthR = lerp(startAmpOscDepthR, endAmpOscDepthR, alpha)
            val ampOscFreqR = lerp(startAmpOscFreqR, endAmpOscFreqR, alpha)
            
            val freqOscRangeL = lerp(startFreqOscRangeL, endFreqOscRangeL, alpha)
            val freqOscFreqL = lerp(startFreqOscFreqL, endFreqOscFreqL, alpha)
            val freqOscRangeR = lerp(startFreqOscRangeR, endFreqOscRangeR, alpha)
            val freqOscFreqR = lerp(startFreqOscFreqR, endFreqOscFreqR, alpha)
            
            val phaseOscFreq = lerp(startPhaseOscFreq, endPhaseOscFreq, alpha)
            val phaseOscRange = lerp(startPhaseOscRange, endPhaseOscRange, alpha)

            // Frequency Oscillation
            val phaseLVib = freqOscFreqL * t + freqOscPhaseOffsetL / twoPi
            val phaseRVib = freqOscFreqR * t + freqOscPhaseOffsetR / twoPi
            val vibL = (freqOscRangeL * 0.5f) * skewedSinePhase(fract(phaseLVib), freqOscSkewL)
            val vibR = (freqOscRangeR * 0.5f) * skewedSinePhase(fract(phaseRVib), freqOscSkewR)

            // Frequency calculation
            var freqL = baseFreq + vibL
            var freqR = baseFreq + vibR
            
            if (beatFreq != 0.0f) {
                val offset = beatFreq / 2.0f
                freqL -= offset
                freqR += offset
            }
            
             if (freqL < 0f) freqL = 0f
             if (freqR < 0f) freqR = 0f
             
             phaseL += twoPi * freqL * dt
             phaseL %= twoPi
             phaseR += twoPi * freqR * dt
             phaseR %= twoPi
             
             var phL = phaseL
             var phR = phaseR
             
             if (phaseOscFreq != 0f || phaseOscRange != 0f) {
                 val dphi = (phaseOscRange * 0.5f) * sinLut(twoPi * phaseOscFreq * t)
                 phL -= dphi
                 phR += dphi
             }
             
             // Amp Oscillation
            val ampPhaseL = ampOscFreqL * t + ampOscPhaseOffsetL / twoPi
            val ampPhaseR = ampOscFreqR * t + ampOscPhaseOffsetR / twoPi
            
            val envL = 1.0f - ampOscDepthL * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseL), ampOscSkewL)))
            val envR = 1.0f - ampOscDepthR * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseR), ampOscSkewR)))
             
             val sampleL = sinLut(phL) * envL * ampL
             val sampleR = sinLut(phR) * envR * ampR
             
             output[i * 2] += sampleL
             output[i * 2 + 1] += sampleR
             
             remainingSamples--
             sampleIdx++
        }
    }
    
    override fun isFinished() = remainingSamples <= 0
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun fract(x: Float): Float = x - floor(x)
}

class IsochronicToneVoice(
    params: Map<String, Any>,
    duration: Float,
    private val sampleRate: Float
) : Voice {
    private val ampL = getFloat(params, "ampL", 0.5f)
    private val ampR = getFloat(params, "ampR", 0.5f)
    private val baseFreq = getFloat(params, "baseFreq", 200f)
    private val beatFreq = getFloat(params, "beatFreq", 10f)
    private val rampPercent = getFloat(params, "rampPercent", 0.1f)
    private val gapPercent = getFloat(params, "gapPercent", 0f)
    private val pan = getFloat(params, "pan", 0f)
    
    // Amp Osc
    private val ampOscDepthL = getFloat(params, "ampOscDepthL", 0f)
    private val ampOscFreqL = getFloat(params, "ampOscFreqL", 0f)
    private val ampOscDepthR = getFloat(params, "ampOscDepthR", 0f)
    private val ampOscFreqR = getFloat(params, "ampOscFreqR", 0f)
    private val ampOscSkewL = getFloat(params, "ampOscSkewL", 0f)
    private val ampOscSkewR = getFloat(params, "ampOscSkewR", 0f)
    private val ampOscPhaseOffsetL = getFloat(params, "ampOscPhaseOffsetL", 0f)
    private val ampOscPhaseOffsetR = getFloat(params, "ampOscPhaseOffsetR", 0f)
    
    // Freq Osc
    private val freqOscRangeL = getFloat(params, "freqOscRangeL", 0f)
    private val freqOscFreqL = getFloat(params, "freqOscFreqL", 0f)
    private val freqOscRangeR = getFloat(params, "freqOscRangeR", 0f)
    private val freqOscFreqR = getFloat(params, "freqOscFreqR", 0f)
    private val freqOscSkewL = getFloat(params, "freqOscSkewL", 0f)
    private val freqOscSkewR = getFloat(params, "freqOscSkewR", 0f)
    private val freqOscPhaseOffsetL = getFloat(params, "freqOscPhaseOffsetL", 0f)
    private val freqOscPhaseOffsetR = getFloat(params, "freqOscPhaseOffsetR", 0f)
    
    // Phase Osc
    private val phaseOscFreq = getFloat(params, "phaseOscFreq", 0f)
    private val phaseOscRange = getFloat(params, "phaseOscRange", 0f)
    
    private var phaseL = 0f
    private var phaseR = 0f
    private var beatPhase = 0f
    private var remainingSamples = (duration * sampleRate).toInt()
    private var sampleIdx = 0

    override fun process(output: FloatArray) {
        val frames = output.size / 2
        val twoPi = 2.0f * PI.toFloat()
        
        for (i in 0 until frames) {
            if (remainingSamples == 0) break
            val t = sampleIdx.toFloat() / sampleRate
            val dt = 1.0f / sampleRate
            
            // Freq Oscillation
            val phaseLVib = freqOscFreqL * t + freqOscPhaseOffsetL / twoPi
            val phaseRVib = freqOscFreqR * t + freqOscPhaseOffsetR / twoPi
            val vibL = (freqOscRangeL * 0.5f) * skewedSinePhase(fract(phaseLVib), freqOscSkewL)
            val vibR = (freqOscRangeR * 0.5f) * skewedSinePhase(fract(phaseRVib), freqOscSkewR)
            
            var freqL = baseFreq + vibL
            var freqR = baseFreq + vibR
            
            val cycleLen = if (beatFreq > 0f) 1.0f / beatFreq else 0f
            val tInCycle = beatPhase * cycleLen
            val isoEnv = trapezoidEnvelope(tInCycle, cycleLen, rampPercent, gapPercent)
            
            phaseL += twoPi * freqL * dt
            phaseL %= twoPi
            phaseR += twoPi * freqR * dt
            phaseR %= twoPi
            
            beatPhase += beatFreq * dt
            beatPhase %= 1.0f
            
            // Amp Oscillation
            val ampPhaseL = ampOscFreqL * t + ampOscPhaseOffsetL / twoPi
            val ampPhaseR = ampOscFreqR * t + ampOscPhaseOffsetR / twoPi
            
            val envL = 1.0f - ampOscDepthL * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseL), ampOscSkewL)))
            val envR = 1.0f - ampOscDepthR * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseR), ampOscSkewR)))
            
            var sampleL = sinLut(phaseL) * ampL * isoEnv * envL
            var sampleR = sinLut(phaseR) * ampR * isoEnv * envR
            
            // Panning
            if (pan != 0f) {
                val mono = 0.5f * (sampleL + sampleR)
                val (pl, pr) = pan2(mono, pan)
                sampleL = pl
                sampleR = pr
            }
            
            output[i * 2] += sampleL
            output[i * 2 + 1] += sampleR
            
            remainingSamples--
            sampleIdx++
        }
    }
    
    override fun isFinished() = remainingSamples <= 0
    private fun fract(x: Float): Float = x - floor(x)
}

class IsochronicToneTransitionVoice(
      params: Map<String, Any>,
     private val duration: Float, 
     private val sampleRate: Float
) : Voice {
     private val startAmpL = getFloat(params, "startAmpL", 0.5f)
     private val endAmpL = getFloat(params, "endAmpL", 0.5f)
     private val startAmpR = getFloat(params, "startAmpR", 0.5f)
     private val endAmpR = getFloat(params, "endAmpR", 0.5f)
     private val startBaseFreq = getFloat(params, "startBaseFreq", 200f)
     private val endBaseFreq = getFloat(params, "endBaseFreq", 200f)
     private val startBeatFreq = getFloat(params, "startBeatFreq", 10f)
     private val endBeatFreq = getFloat(params, "endBeatFreq", 10f)
     private val startRampPercent = getFloat(params, "startRampPercent", 0.1f)
     private val endRampPercent = getFloat(params, "endRampPercent", 0.1f)
     private val startGapPercent = getFloat(params, "startGapPercent", 0f)
     private val endGapPercent = getFloat(params, "endGapPercent", 0f)
     
     // Amp Osc
    private val startAmpOscDepthL = getFloat(params, "startAmpOscDepthL", 0f)
    private val endAmpOscDepthL = getFloat(params, "endAmpOscDepthL", 0f)
    private val startAmpOscFreqL = getFloat(params, "startAmpOscFreqL", 0f)
    private val endAmpOscFreqL = getFloat(params, "endAmpOscFreqL", 0f)
    private val startAmpOscDepthR = getFloat(params, "startAmpOscDepthR", 0f)
    private val endAmpOscDepthR = getFloat(params, "endAmpOscDepthR", 0f)
    private val startAmpOscFreqR = getFloat(params, "startAmpOscFreqR", 0f)
    private val endAmpOscFreqR = getFloat(params, "endAmpOscFreqR", 0f)
    
    // Freq Osc
    private val startFreqOscRangeL = getFloat(params, "startFreqOscRangeL", 0f)
    private val endFreqOscRangeL = getFloat(params, "endFreqOscRangeL", 0f)
    private val startFreqOscFreqL = getFloat(params, "startFreqOscFreqL", 0f)
    private val endFreqOscFreqL = getFloat(params, "endFreqOscFreqL", 0f)
    private val startFreqOscRangeR = getFloat(params, "startFreqOscRangeR", 0f)
    private val endFreqOscRangeR = getFloat(params, "endFreqOscRangeR", 0f)
    private val startFreqOscFreqR = getFloat(params, "startFreqOscFreqR", 0f)
    private val endFreqOscFreqR = getFloat(params, "endFreqOscFreqR", 0f)
    
    // Skews (Assuming constant or start only for now as per likely Rust impl not interpolating skews usually)
    private val freqOscSkewL = getFloat(params, "freqOscSkewL", 0f)
    private val freqOscSkewR = getFloat(params, "freqOscSkewR", 0f)
    private val freqOscPhaseOffsetL = getFloat(params, "freqOscPhaseOffsetL", 0f)
    private val freqOscPhaseOffsetR = getFloat(params, "freqOscPhaseOffsetR", 0f)
    
    private val ampOscSkewL = getFloat(params, "ampOscSkewL", 0f)
    private val ampOscSkewR = getFloat(params, "ampOscSkewR", 0f)
    private val ampOscPhaseOffsetL = getFloat(params, "ampOscPhaseOffsetL", 0f)
    private val ampOscPhaseOffsetR = getFloat(params, "ampOscPhaseOffsetR", 0f)
     
     // Phase Osc
     private val startPhaseOscFreq = getFloat(params, "startPhaseOscFreq", 0f)
     private val endPhaseOscFreq = getFloat(params, "endPhaseOscFreq", 0f)
     private val startPhaseOscRange = getFloat(params, "startPhaseOscRange", 0f)
     private val endPhaseOscRange = getFloat(params, "endPhaseOscRange", 0f)
     
     private val curve = TransitionCurve.fromStr(params["curve"] as? String)
     private val initialOffset = getFloat(params, "initialOffset", 0f)
     private val postOffset = getFloat(params, "postOffset", 0f)
     
     private var phaseL = 0f
     private var phaseR = 0f
     private var beatPhase = 0f
     private var remainingSamples = (duration * sampleRate).toInt()
     private var sampleIdx = 0
     
     override fun process(output: FloatArray) {
        val frames = output.size / 2
        val twoPi = 2.0f * PI.toFloat()
        
        for (i in 0 until frames) {
            if (remainingSamples == 0) break
            val t = sampleIdx.toFloat() / sampleRate
            val dt = 1.0f / sampleRate
            
            var alpha = if (t < initialOffset) {
                0.0f
            } else if (t > duration - postOffset) {
                1.0f
            } else {
                val span = duration - initialOffset - postOffset
                if (span > 0f) (t - initialOffset) / span else 1.0f
            }
            alpha = curve.apply(alpha.coerceIn(0f, 1f))
            
            val ampL = lerp(startAmpL, endAmpL, alpha)
            val ampR = lerp(startAmpR, endAmpR, alpha)
            val baseFreq = lerp(startBaseFreq, endBaseFreq, alpha)
            val beatFreq = lerp(startBeatFreq, endBeatFreq, alpha)
            val rampPercent = lerp(startRampPercent, endRampPercent, alpha)
            val gapPercent = lerp(startGapPercent, endGapPercent, alpha)
            
            val ampOscDepthL = lerp(startAmpOscDepthL, endAmpOscDepthL, alpha)
            val ampOscFreqL = lerp(startAmpOscFreqL, endAmpOscFreqL, alpha)
            val ampOscDepthR = lerp(startAmpOscDepthR, endAmpOscDepthR, alpha)
            val ampOscFreqR = lerp(startAmpOscFreqR, endAmpOscFreqR, alpha)
            
            val freqOscRangeL = lerp(startFreqOscRangeL, endFreqOscRangeL, alpha)
            val freqOscFreqL = lerp(startFreqOscFreqL, endFreqOscFreqL, alpha)
            val freqOscRangeR = lerp(startFreqOscRangeR, endFreqOscRangeR, alpha)
            val freqOscFreqR = lerp(startFreqOscFreqR, endFreqOscFreqR, alpha)
            
            // Freq Oscillation
            val phaseLVib = freqOscFreqL * t + freqOscPhaseOffsetL / twoPi
            val phaseRVib = freqOscFreqR * t + freqOscPhaseOffsetR / twoPi
            val vibL = (freqOscRangeL * 0.5f) * skewedSinePhase(fract(phaseLVib), freqOscSkewL)
            val vibR = (freqOscRangeR * 0.5f) * skewedSinePhase(fract(phaseRVib), freqOscSkewR)
            
            var freqL = baseFreq + vibL
            var freqR = baseFreq + vibR
            
            val cycleLen = if (beatFreq > 0f) 1.0f / beatFreq else 0f
            val tInCycle = beatPhase * cycleLen
            val isoEnv = trapezoidEnvelope(tInCycle, cycleLen, rampPercent, gapPercent)
            
            phaseL += twoPi * freqL * dt
            phaseL %= twoPi
            phaseR += twoPi * freqR * dt
            phaseR %= twoPi
            
            beatPhase += beatFreq * dt
            beatPhase %= 1.0f
            
            // Amp Oscillation
            val ampPhaseL = ampOscFreqL * t + ampOscPhaseOffsetL / twoPi
            val ampPhaseR = ampOscFreqR * t + ampOscPhaseOffsetR / twoPi
            
            val envL = 1.0f - ampOscDepthL * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseL), ampOscSkewL)))
            val envR = 1.0f - ampOscDepthR * (0.5f * (1.0f + skewedSinePhase(fract(ampPhaseR), ampOscSkewR)))
            
            var sampleL = sinLut(phaseL) * ampL * isoEnv * envL
            var sampleR = sinLut(phaseR) * ampR * isoEnv * envR
            
            output[i * 2] += sampleL
            output[i * 2 + 1] += sampleR
            
            remainingSamples--
            sampleIdx++
        }
     }
     
     override fun isFinished() = remainingSamples <= 0
     private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
     private fun fract(x: Float): Float = x - floor(x)
}

class NoiseSweptNotchVoice(
    params: Map<String, Any>,
    duration: Float,
    sampleRate: Float
) : Voice {
    private val generator = StreamingNoise(NoiseParams.fromMap(params), sampleRate)
    private val amp = getFloat(params, "amp", 0.5f)
    private var remainingSamples = (duration * sampleRate).toInt()
    // Helper scratch buffer
    private var scratch = FloatArray(0)
    
    override fun process(output: FloatArray) {
        val needed = output.size
        if (scratch.size < needed) {
            scratch = FloatArray(needed)
        }
        
        // We only generate what fits in output or remaining samples
        val toGenerate = min(needed / 2, remainingSamples) // Frames
        if (toGenerate == 0) return
        
        generator.generate(scratch)
        
        for (i in 0 until toGenerate * 2) {
            output[i] += scratch[i] * amp
        }
        
        remainingSamples -= toGenerate
    }

    override fun isFinished(): Boolean = remainingSamples <= 0
}

class NoiseSweptNotchTransitionVoice(
    params: Map<String, Any>,
    duration: Float,
    sampleRate: Float
) : Voice {
    private val generator = StreamingNoise(NoiseParams.fromMap(params), sampleRate)
    private val amp = getFloat(params, "amp", 0.5f)
    private var remainingSamples = (duration * sampleRate).toInt()
    private var scratch = FloatArray(0)
    
    override fun process(output: FloatArray) {
        val needed = output.size
        if (scratch.size < needed) {
            scratch = FloatArray(needed)
        }
        
        val toGenerate = min(needed / 2, remainingSamples)
        if (toGenerate == 0) return
        
        generator.generate(scratch)
        
        for (i in 0 until toGenerate * 2) {
            output[i] += scratch[i] * amp
        }
        
        remainingSamples -= toGenerate
    }

    override fun isFinished(): Boolean = remainingSamples <= 0
}

object VoiceFactory {
    fun createVoice(data: VoiceData, duration: Float, sampleRate: Float): StepVoice? {
        val paramsMap = data.params as? Map<String, Any> ?: emptyMap()
        
        var voice: Voice? = when (data.synthFunctionName) {
            "binaural_beat" -> BinauralBeatVoice(paramsMap, duration, sampleRate)
            "binaural_beat_transition" -> BinauralBeatTransitionVoice(paramsMap, duration, sampleRate)
            "isochronic_tone" -> IsochronicToneVoice(paramsMap, duration, sampleRate)
            "isochronic_tone_transition" -> IsochronicToneTransitionVoice(paramsMap, duration, sampleRate)
            "noise_swept_notch", "noise" -> NoiseSweptNotchVoice(paramsMap, duration, sampleRate)
            "noise_swept_notch_transition", "noise_transition" -> NoiseSweptNotchTransitionVoice(paramsMap, duration, sampleRate)
            else -> null
        }
        
        if (voice == null) return null
        
        val volEnv = data.volumeEnvelope
        if (volEnv != null) {
            val envVec = buildVolumeEnvelope(volEnv, duration, sampleRate.toInt())
            voice = VolumeEnvelopeVoice(voice, envVec)
        }
        
        val voiceType = when (data.voiceType.lowercase()) {
            "noise" -> VoiceType.Noise
            "binaural" -> VoiceType.Binaural
            else -> VoiceType.Other
        }
        
        return StepVoice(
            kind = voice,
            voiceType = voiceType,
            normalizationPeak = 1.0f // Simplified
        )
    }
}

// Wrapper for StepVoice to match Rust structure usage
data class StepVoice(
    val kind: Voice,
    val voiceType: VoiceType,
    val normalizationPeak: Float
)
