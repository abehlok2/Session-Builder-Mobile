package com.binauralbuilder.session_builder_mobile.realtime_backend

import com.binauralbuilder.session_builder_mobile.realtime_backend.dsp.*
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
    fun getPhases(): Pair<Float, Float>? = null
    fun setPhases(phaseL: Float, phaseR: Float) {}
    fun normalizationPeak(): Float = 1.0f
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

private fun getString(params: Map<String, Any>, key: String, default: String): String {
    val value = params[key]
    return when (value) {
        is String -> value
        is JsonPrimitive -> if (value.isString) value.asString else default
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

    override fun getPhases(): Pair<Float, Float>? {
        return inner.getPhases()
    }

    override fun setPhases(phaseL: Float, phaseR: Float) {
        inner.setPhases(phaseL, phaseR)
    }

    override fun normalizationPeak(): Float {
        val innerPeak = inner.normalizationPeak()
        val envPeak = envelope.maxOrNull() ?: 1.0f
        return innerPeak * envPeak
    }
}

class BinauralBeatVoice(
    params: Map<String, Any>,
    duration: Float,
    private val sampleRate: Float
) : Voice {
    private val baseAmp = getFloat(params, "amp", 0.5f)
    private val ampL = getFloat(params, "ampL", baseAmp)
    private val ampR = getFloat(params, "ampR", baseAmp)
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
    private val freqOscShape = LfoShape.fromStr(getString(params, "freqOscShape", "sine"))
    
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
            val vibL = (freqOscRangeL * 0.5f) * when (freqOscShape) {
                LfoShape.Triangle -> skewedTrianglePhase(fract(phaseLVib), frequencyOscSkewL)
                LfoShape.Sine -> skewedSinePhase(fract(phaseLVib), frequencyOscSkewL)
            }
            val vibR = (freqOscRangeR * 0.5f) * when (freqOscShape) {
                LfoShape.Triangle -> skewedTrianglePhase(fract(phaseRVib), frequencyOscSkewR)
                LfoShape.Sine -> skewedSinePhase(fract(phaseRVib), frequencyOscSkewR)
            }
            
            var freqL = baseFreq + vibL
            var freqR = baseFreq + vibR
            
            if (!forceMono && beatFreq != 0.0f) {
                val offset = beatFreq / 2.0f
                if (leftHigh) {
                    freqL += offset
                    freqR -= offset
                } else {
                    freqL -= offset
                    freqR += offset
                }
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

    override fun getPhases(): Pair<Float, Float> = phaseL to phaseR

    override fun setPhases(phaseL: Float, phaseR: Float) {
        this.phaseL = phaseL
        this.phaseR = phaseR
    }
}


class BinauralBeatTransitionVoice(
    params: Map<String, Any>,
    private val duration: Float, // Total duration of step
    private val sampleRate: Float
) : Voice {
    private val startAmpL = getFloat(params, "startAmpL", getFloat(params, "ampL", 0.5f))
    private val endAmpL = getFloat(params, "endAmpL", startAmpL)
    private val startAmpR = getFloat(params, "startAmpR", getFloat(params, "ampR", 0.5f))
    private val endAmpR = getFloat(params, "endAmpR", startAmpR)
    private val startBaseFreq = getFloat(params, "startBaseFreq", getFloat(params, "baseFreq", 200f))
    private val endBaseFreq = getFloat(params, "endBaseFreq", startBaseFreq)
    private val startBeatFreq = getFloat(params, "startBeatFreq", getFloat(params, "beatFreq", 4.0f))
    private val endBeatFreq = getFloat(params, "endBeatFreq", startBeatFreq)
    private val startForceMono = getBool(params, "startForceMono", getBool(params, "forceMono", false))
    private val endForceMono = getBool(params, "endForceMono", startForceMono)
    private val leftHigh = getBool(params, "leftHigh", false)
    private val startStartPhaseL = getFloat(params, "startStartPhaseL", getFloat(params, "startPhaseL", 0f))
    private val endStartPhaseL = getFloat(params, "endStartPhaseL", startStartPhaseL)
    private val startStartPhaseR = getFloat(params, "startStartPhaseR", getFloat(params, "startPhaseR", 0f))
    private val endStartPhaseR = getFloat(params, "endStartPhaseR", startStartPhaseR)
    private val startPhaseOscFreq = getFloat(params, "startPhaseOscFreq", getFloat(params, "phaseOscFreq", 0f))
    private val endPhaseOscFreq = getFloat(params, "endPhaseOscFreq", startPhaseOscFreq)
    private val startPhaseOscRange = getFloat(params, "startPhaseOscRange", getFloat(params, "phaseOscRange", 0f))
    private val endPhaseOscRange = getFloat(params, "endPhaseOscRange", startPhaseOscRange)
    private val startAmpOscDepthL = getFloat(params, "startAmpOscDepthL", getFloat(params, "ampOscDepthL", 0f))
    private val endAmpOscDepthL = getFloat(params, "endAmpOscDepthL", startAmpOscDepthL)
    private val startAmpOscFreqL = getFloat(params, "startAmpOscFreqL", getFloat(params, "ampOscFreqL", 0f))
    private val endAmpOscFreqL = getFloat(params, "endAmpOscFreqL", startAmpOscFreqL)
    private val startAmpOscDepthR = getFloat(params, "startAmpOscDepthR", getFloat(params, "ampOscDepthR", 0f))
    private val endAmpOscDepthR = getFloat(params, "endAmpOscDepthR", startAmpOscDepthR)
    private val startAmpOscFreqR = getFloat(params, "startAmpOscFreqR", getFloat(params, "ampOscFreqR", 0f))
    private val endAmpOscFreqR = getFloat(params, "endAmpOscFreqR", startAmpOscFreqR)
    private val startAmpOscPhaseOffsetL = getFloat(params, "startAmpOscPhaseOffsetL", getFloat(params, "ampOscPhaseOffsetL", 0f))
    private val endAmpOscPhaseOffsetL = getFloat(params, "endAmpOscPhaseOffsetL", startAmpOscPhaseOffsetL)
    private val startAmpOscPhaseOffsetR = getFloat(params, "startAmpOscPhaseOffsetR", getFloat(params, "ampOscPhaseOffsetR", 0f))
    private val endAmpOscPhaseOffsetR = getFloat(params, "endAmpOscPhaseOffsetR", startAmpOscPhaseOffsetR)
    private val startFreqOscRangeL = getFloat(params, "startFreqOscRangeL", getFloat(params, "freqOscRangeL", 0f))
    private val endFreqOscRangeL = getFloat(params, "endFreqOscRangeL", startFreqOscRangeL)
    private val startFreqOscFreqL = getFloat(params, "startFreqOscFreqL", getFloat(params, "freqOscFreqL", 0f))
    private val endFreqOscFreqL = getFloat(params, "endFreqOscFreqL", startFreqOscFreqL)
    private val startFreqOscRangeR = getFloat(params, "startFreqOscRangeR", getFloat(params, "freqOscRangeR", 0f))
    private val endFreqOscRangeR = getFloat(params, "endFreqOscRangeR", startFreqOscRangeR)
    private val startFreqOscFreqR = getFloat(params, "startFreqOscFreqR", getFloat(params, "freqOscFreqR", 0f))
    private val endFreqOscFreqR = getFloat(params, "endFreqOscFreqR", startFreqOscFreqR)
    private val startFreqOscSkewL = getFloat(params, "startFreqOscSkewL", getFloat(params, "freqOscSkewL", 0f))
    private val endFreqOscSkewL = getFloat(params, "endFreqOscSkewL", startFreqOscSkewL)
    private val startFreqOscSkewR = getFloat(params, "startFreqOscSkewR", getFloat(params, "freqOscSkewR", 0f))
    private val endFreqOscSkewR = getFloat(params, "endFreqOscSkewR", startFreqOscSkewR)
    private val startFreqOscPhaseOffsetL = getFloat(params, "startFreqOscPhaseOffsetL", getFloat(params, "freqOscPhaseOffsetL", 0f))
    private val endFreqOscPhaseOffsetL = getFloat(params, "endFreqOscPhaseOffsetL", startFreqOscPhaseOffsetL)
    private val startFreqOscPhaseOffsetR = getFloat(params, "startFreqOscPhaseOffsetR", getFloat(params, "freqOscPhaseOffsetR", 0f))
    private val endFreqOscPhaseOffsetR = getFloat(params, "endFreqOscPhaseOffsetR", startFreqOscPhaseOffsetR)
    private val startAmpOscSkewL = getFloat(params, "startAmpOscSkewL", getFloat(params, "ampOscSkewL", 0f))
    private val endAmpOscSkewL = getFloat(params, "endAmpOscSkewL", startAmpOscSkewL)
    private val startAmpOscSkewR = getFloat(params, "startAmpOscSkewR", getFloat(params, "ampOscSkewR", 0f))
    private val endAmpOscSkewR = getFloat(params, "endAmpOscSkewR", startAmpOscSkewR)
    private val freqOscShape = LfoShape.fromStr(getString(params, "freqOscShape", "sine"))

    private val curve = TransitionCurve.fromStr(
        getString(params, "transition_curve", getString(params, "curve", "linear"))
    )
    private val initialOffset = getFloat(params, "initial_offset", getFloat(params, "initialOffset", 0f))
    private val postOffset = getFloat(params, "post_offset", getFloat(params, "postOffset", 0f))

    private var phaseL = startStartPhaseL
    private var phaseR = startStartPhaseR

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
            val forceMono = if (startForceMono == endForceMono) startForceMono else alpha >= 0.5f
            val phaseOscFreq = lerp(startPhaseOscFreq, endPhaseOscFreq, alpha)
            val phaseOscRange = lerp(startPhaseOscRange, endPhaseOscRange, alpha)
            val ampOscDepthL = lerp(startAmpOscDepthL, endAmpOscDepthL, alpha)
            val ampOscFreqL = lerp(startAmpOscFreqL, endAmpOscFreqL, alpha)
            val ampOscDepthR = lerp(startAmpOscDepthR, endAmpOscDepthR, alpha)
            val ampOscFreqR = lerp(startAmpOscFreqR, endAmpOscFreqR, alpha)
            val ampOscPhaseOffsetL = lerp(startAmpOscPhaseOffsetL, endAmpOscPhaseOffsetL, alpha)
            val ampOscPhaseOffsetR = lerp(startAmpOscPhaseOffsetR, endAmpOscPhaseOffsetR, alpha)
            val freqOscRangeL = lerp(startFreqOscRangeL, endFreqOscRangeL, alpha)
            val freqOscFreqL = lerp(startFreqOscFreqL, endFreqOscFreqL, alpha)
            val freqOscRangeR = lerp(startFreqOscRangeR, endFreqOscRangeR, alpha)
            val freqOscFreqR = lerp(startFreqOscFreqR, endFreqOscFreqR, alpha)
            val freqOscSkewL = lerp(startFreqOscSkewL, endFreqOscSkewL, alpha)
            val freqOscSkewR = lerp(startFreqOscSkewR, endFreqOscSkewR, alpha)
            val freqOscPhaseOffsetL = lerp(startFreqOscPhaseOffsetL, endFreqOscPhaseOffsetL, alpha)
            val freqOscPhaseOffsetR = lerp(startFreqOscPhaseOffsetR, endFreqOscPhaseOffsetR, alpha)
            val ampOscSkewL = lerp(startAmpOscSkewL, endAmpOscSkewL, alpha)
            val ampOscSkewR = lerp(startAmpOscSkewR, endAmpOscSkewR, alpha)

            val phaseLVib = freqOscFreqL * t + freqOscPhaseOffsetL / twoPi
            val phaseRVib = freqOscFreqR * t + freqOscPhaseOffsetR / twoPi
            val vibL = (freqOscRangeL * 0.5f) * when (freqOscShape) {
                LfoShape.Triangle -> skewedTrianglePhase(fract(phaseLVib), freqOscSkewL)
                LfoShape.Sine -> skewedSinePhase(fract(phaseLVib), freqOscSkewL)
            }
            val vibR = (freqOscRangeR * 0.5f) * when (freqOscShape) {
                LfoShape.Triangle -> skewedTrianglePhase(fract(phaseRVib), freqOscSkewR)
                LfoShape.Sine -> skewedSinePhase(fract(phaseRVib), freqOscSkewR)
            }

            val halfBeat = beatFreq * 0.5f
            val (freqBaseL, freqBaseR) = if (leftHigh) {
                baseFreq + halfBeat to baseFreq - halfBeat
            } else {
                baseFreq - halfBeat to baseFreq + halfBeat
            }
            var freqL = freqBaseL + vibL
            var freqR = freqBaseR + vibR

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

    override fun getPhases(): Pair<Float, Float> = phaseL to phaseR

    override fun setPhases(phaseL: Float, phaseR: Float) {
        this.phaseL = phaseL
        this.phaseR = phaseR
    }
}

class IsochronicToneVoice(
    params: Map<String, Any>,
    duration: Float,
    private val sampleRate: Float
) : Voice {
    private val baseAmp = getFloat(params, "amp", 0.5f)
    private val ampL = getFloat(params, "ampL", baseAmp)
    private val ampR = getFloat(params, "ampR", baseAmp)
    private val baseFreq = getFloat(params, "baseFreq", 200f)
    private val beatFreq = getFloat(params, "beatFreq", 10f)
    private val forceMono = getBool(params, "forceMono", false)
    private val startPhaseL = getFloat(params, "startPhaseL", 0f)
    private val startPhaseR = getFloat(params, "startPhaseR", 0f)
    private val rampPercent = getFloat(params, "rampPercent", 0.1f)
    private val gapPercent = getFloat(params, "gapPercent", 0f)
    private val pan = getFloat(params, "pan", 0f)
    private val panRangeMin = getFloat(params, "panRangeMin", pan).coerceIn(-1.0f, 1.0f)
    private val panRangeMax = getFloat(params, "panRangeMax", pan).coerceIn(-1.0f, 1.0f)
    private val panFreq = getFloat(params, "panFreq", 0f)
    private val panPhase = getFloat(params, "panPhase", 0f)
    
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
    
    private var phaseL = startPhaseL
    private var phaseR = startPhaseR
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

            if (forceMono) {
                freqL = max(0f, baseFreq)
                freqR = max(0f, baseFreq)
            } else {
                if (freqL < 0f) freqL = 0f
                if (freqR < 0f) freqR = 0f
            }
            
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
            
            val panMin = min(panRangeMin, panRangeMax)
            val panMax = max(panRangeMin, panRangeMax)
            val panCenter = (panMin + panMax) * 0.5f
            val panRange = (panMax - panMin) * 0.5f
            val currentPan = if (panFreq != 0f && panRange > 0f) {
                val panOsc = sinLut(twoPi * panFreq * t + panPhase)
                (panCenter + panRange * panOsc).coerceIn(-1.0f, 1.0f)
            } else {
                panCenter
            }

            if (abs(currentPan) > 1e-6f) {
                val mono = 0.5f * (sampleL + sampleR)
                val (pl, pr) = pan2(mono, currentPan)
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

    override fun getPhases(): Pair<Float, Float> = phaseL to phaseR

    override fun setPhases(phaseL: Float, phaseR: Float) {
        this.phaseL = phaseL
        this.phaseR = phaseR
    }
}

class IsochronicToneTransitionVoice(
      params: Map<String, Any>,
     private val duration: Float, 
     private val sampleRate: Float
) : Voice {
     private val baseAmp = getFloat(params, "amp", 0.5f)
     private val startAmpL = getFloat(params, "startAmpL", getFloat(params, "ampL", baseAmp))
     private val endAmpL = getFloat(params, "endAmpL", startAmpL)
     private val startAmpR = getFloat(params, "startAmpR", getFloat(params, "ampR", baseAmp))
     private val endAmpR = getFloat(params, "endAmpR", startAmpR)
     private val startBaseFreq = getFloat(params, "startBaseFreq", 200f)
     private val endBaseFreq = getFloat(params, "endBaseFreq", startBaseFreq)
     private val startBeatFreq = getFloat(params, "startBeatFreq", 4.0f)
     private val endBeatFreq = getFloat(params, "endBeatFreq", startBeatFreq)
     private val startForceMono = getBool(params, "startForceMono", getBool(params, "forceMono", false))
     private val endForceMono = getBool(params, "endForceMono", startForceMono)
     private val startStartPhaseL = getFloat(params, "startStartPhaseL", getFloat(params, "startPhaseL", 0f))
     private val endStartPhaseL = getFloat(params, "endStartPhaseL", startStartPhaseL)
     private val startStartPhaseR = getFloat(params, "startStartPhaseR", getFloat(params, "startPhaseR", 0f))
     private val endStartPhaseR = getFloat(params, "endStartPhaseR", startStartPhaseR)
     private val startRampPercent = getFloat(params, "startRampPercent", 0.2f)
     private val endRampPercent = getFloat(params, "endRampPercent", startRampPercent)
     private val startGapPercent = getFloat(params, "startGapPercent", 0.15f)
     private val endGapPercent = getFloat(params, "endGapPercent", startGapPercent)

     private val startAmpOscDepthL = getFloat(params, "startAmpOscDepthL", getFloat(params, "ampOscDepthL", 0f))
     private val endAmpOscDepthL = getFloat(params, "endAmpOscDepthL", startAmpOscDepthL)
     private val startAmpOscFreqL = getFloat(params, "startAmpOscFreqL", getFloat(params, "ampOscFreqL", 0f))
     private val endAmpOscFreqL = getFloat(params, "endAmpOscFreqL", startAmpOscFreqL)
     private val startAmpOscDepthR = getFloat(params, "startAmpOscDepthR", getFloat(params, "ampOscDepthR", 0f))
     private val endAmpOscDepthR = getFloat(params, "endAmpOscDepthR", startAmpOscDepthR)
     private val startAmpOscFreqR = getFloat(params, "startAmpOscFreqR", getFloat(params, "ampOscFreqR", 0f))
     private val endAmpOscFreqR = getFloat(params, "endAmpOscFreqR", startAmpOscFreqR)
     private val startAmpOscPhaseOffsetL = getFloat(params, "startAmpOscPhaseOffsetL", getFloat(params, "ampOscPhaseOffsetL", 0f))
     private val endAmpOscPhaseOffsetL = getFloat(params, "endAmpOscPhaseOffsetL", startAmpOscPhaseOffsetL)
     private val startAmpOscPhaseOffsetR = getFloat(params, "startAmpOscPhaseOffsetR", getFloat(params, "ampOscPhaseOffsetR", 0f))
     private val endAmpOscPhaseOffsetR = getFloat(params, "endAmpOscPhaseOffsetR", startAmpOscPhaseOffsetR)

     private val startFreqOscRangeL = getFloat(params, "startFreqOscRangeL", getFloat(params, "freqOscRangeL", 0f))
     private val endFreqOscRangeL = getFloat(params, "endFreqOscRangeL", startFreqOscRangeL)
     private val startFreqOscFreqL = getFloat(params, "startFreqOscFreqL", getFloat(params, "freqOscFreqL", 0f))
     private val endFreqOscFreqL = getFloat(params, "endFreqOscFreqL", startFreqOscFreqL)
     private val startFreqOscRangeR = getFloat(params, "startFreqOscRangeR", getFloat(params, "freqOscRangeR", 0f))
     private val endFreqOscRangeR = getFloat(params, "endFreqOscRangeR", startFreqOscRangeR)
     private val startFreqOscFreqR = getFloat(params, "startFreqOscFreqR", getFloat(params, "freqOscFreqR", 0f))
     private val endFreqOscFreqR = getFloat(params, "endFreqOscFreqR", startFreqOscFreqR)
     private val startFreqOscSkewL = getFloat(params, "startFreqOscSkewL", getFloat(params, "freqOscSkewL", 0f))
     private val endFreqOscSkewL = getFloat(params, "endFreqOscSkewL", startFreqOscSkewL)
     private val startFreqOscSkewR = getFloat(params, "startFreqOscSkewR", getFloat(params, "freqOscSkewR", 0f))
     private val endFreqOscSkewR = getFloat(params, "endFreqOscSkewR", startFreqOscSkewR)
     private val startFreqOscPhaseOffsetL = getFloat(params, "startFreqOscPhaseOffsetL", getFloat(params, "freqOscPhaseOffsetL", 0f))
     private val endFreqOscPhaseOffsetL = getFloat(params, "endFreqOscPhaseOffsetL", startFreqOscPhaseOffsetL)
     private val startFreqOscPhaseOffsetR = getFloat(params, "startFreqOscPhaseOffsetR", getFloat(params, "freqOscPhaseOffsetR", 0f))
     private val endFreqOscPhaseOffsetR = getFloat(params, "endFreqOscPhaseOffsetR", startFreqOscPhaseOffsetR)

     private val startAmpOscSkewL = getFloat(params, "startAmpOscSkewL", getFloat(params, "ampOscSkewL", 0f))
     private val endAmpOscSkewL = getFloat(params, "endAmpOscSkewL", startAmpOscSkewL)
     private val startAmpOscSkewR = getFloat(params, "startAmpOscSkewR", getFloat(params, "ampOscSkewR", 0f))
     private val endAmpOscSkewR = getFloat(params, "endAmpOscSkewR", startAmpOscSkewR)

     private val startPhaseOscFreq = getFloat(params, "startPhaseOscFreq", getFloat(params, "phaseOscFreq", 0f))
     private val endPhaseOscFreq = getFloat(params, "endPhaseOscFreq", startPhaseOscFreq)
     private val startPhaseOscRange = getFloat(params, "startPhaseOscRange", getFloat(params, "phaseOscRange", 0f))
     private val endPhaseOscRange = getFloat(params, "endPhaseOscRange", startPhaseOscRange)

     private val panBase = getFloat(params, "pan", 0f)
     private val startPanRangeMin = getFloat(params, "startPanRangeMin", getFloat(params, "panRangeMin", panBase)).coerceIn(-1.0f, 1.0f)
     private val endPanRangeMin = getFloat(params, "endPanRangeMin", startPanRangeMin).coerceIn(-1.0f, 1.0f)
     private val startPanRangeMax = getFloat(params, "startPanRangeMax", getFloat(params, "panRangeMax", panBase)).coerceIn(-1.0f, 1.0f)
     private val endPanRangeMax = getFloat(params, "endPanRangeMax", startPanRangeMax).coerceIn(-1.0f, 1.0f)
     private val startPanFreq = getFloat(params, "startPanFreq", getFloat(params, "panFreq", 0f))
     private val endPanFreq = getFloat(params, "endPanFreq", startPanFreq)
     private val startPanPhase = getFloat(params, "startPanPhase", getFloat(params, "panPhase", 0f))
     private val endPanPhase = getFloat(params, "endPanPhase", startPanPhase)

     private val curve = TransitionCurve.fromStr(
         getString(params, "transition_curve", getString(params, "curve", "linear"))
     )
     private val initialOffset = getFloat(params, "initial_offset", getFloat(params, "initialOffset", 0f))
     private val postOffset = getFloat(params, "post_offset", getFloat(params, "postOffset", 0f))

     private var phaseL = startStartPhaseL
     private var phaseR = startStartPhaseR
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
            val forceMono = if (startForceMono == endForceMono) startForceMono else alpha >= 0.5f
            val rampPercent = lerp(startRampPercent, endRampPercent, alpha)
            val gapPercent = lerp(startGapPercent, endGapPercent, alpha)
            
            val ampOscDepthL = lerp(startAmpOscDepthL, endAmpOscDepthL, alpha)
            val ampOscFreqL = lerp(startAmpOscFreqL, endAmpOscFreqL, alpha)
            val ampOscDepthR = lerp(startAmpOscDepthR, endAmpOscDepthR, alpha)
            val ampOscFreqR = lerp(startAmpOscFreqR, endAmpOscFreqR, alpha)
            val ampOscPhaseOffsetL = lerp(startAmpOscPhaseOffsetL, endAmpOscPhaseOffsetL, alpha)
            val ampOscPhaseOffsetR = lerp(startAmpOscPhaseOffsetR, endAmpOscPhaseOffsetR, alpha)
            
            val freqOscRangeL = lerp(startFreqOscRangeL, endFreqOscRangeL, alpha)
            val freqOscFreqL = lerp(startFreqOscFreqL, endFreqOscFreqL, alpha)
            val freqOscRangeR = lerp(startFreqOscRangeR, endFreqOscRangeR, alpha)
            val freqOscFreqR = lerp(startFreqOscFreqR, endFreqOscFreqR, alpha)
            val freqOscSkewL = lerp(startFreqOscSkewL, endFreqOscSkewL, alpha)
            val freqOscSkewR = lerp(startFreqOscSkewR, endFreqOscSkewR, alpha)
            val freqOscPhaseOffsetL = lerp(startFreqOscPhaseOffsetL, endFreqOscPhaseOffsetL, alpha)
            val freqOscPhaseOffsetR = lerp(startFreqOscPhaseOffsetR, endFreqOscPhaseOffsetR, alpha)
            val ampOscSkewL = lerp(startAmpOscSkewL, endAmpOscSkewL, alpha)
            val ampOscSkewR = lerp(startAmpOscSkewR, endAmpOscSkewR, alpha)
            val phaseOscFreq = lerp(startPhaseOscFreq, endPhaseOscFreq, alpha)
            val phaseOscRange = lerp(startPhaseOscRange, endPhaseOscRange, alpha)
            val panRangeMin = lerp(startPanRangeMin, endPanRangeMin, alpha).coerceIn(-1.0f, 1.0f)
            val panRangeMax = lerp(startPanRangeMax, endPanRangeMax, alpha).coerceIn(-1.0f, 1.0f)
            val panFreq = lerp(startPanFreq, endPanFreq, alpha)
            val panPhase = lerp(startPanPhase, endPanPhase, alpha)
            
            // Freq Oscillation
            val phaseLVib = freqOscFreqL * t + freqOscPhaseOffsetL / twoPi
            val phaseRVib = freqOscFreqR * t + freqOscPhaseOffsetR / twoPi
            val vibL = (freqOscRangeL * 0.5f) * skewedSinePhase(fract(phaseLVib), freqOscSkewL)
            val vibR = (freqOscRangeR * 0.5f) * skewedSinePhase(fract(phaseRVib), freqOscSkewR)
            
            var freqL = baseFreq + vibL
            var freqR = baseFreq + vibR

            if (forceMono) {
                freqL = max(0f, baseFreq)
                freqR = max(0f, baseFreq)
            } else {
                if (freqL < 0f) freqL = 0f
                if (freqR < 0f) freqR = 0f
            }
            
            val cycleLen = if (beatFreq > 0f) 1.0f / beatFreq else 0f
            val tInCycle = beatPhase * cycleLen
            val isoEnv = trapezoidEnvelope(tInCycle, cycleLen, rampPercent, gapPercent)
            
            phaseL += twoPi * freqL * dt
            phaseL %= twoPi
            phaseR += twoPi * freqR * dt
            phaseR %= twoPi
            
            beatPhase += beatFreq * dt
            beatPhase %= 1.0f

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
            
            var sampleL = sinLut(phL) * ampL * isoEnv * envL
            var sampleR = sinLut(phR) * ampR * isoEnv * envR

            val panMin = min(panRangeMin, panRangeMax)
            val panMax = max(panRangeMin, panRangeMax)
            val panCenter = (panMin + panMax) * 0.5f
            val panRange = (panMax - panMin) * 0.5f
            val currentPan = if (panFreq != 0f && panRange > 0f) {
                val panOsc = sinLut(twoPi * panFreq * t + panPhase)
                (panCenter + panRange * panOsc).coerceIn(-1.0f, 1.0f)
            } else {
                panCenter
            }
            
            if (abs(currentPan) > 1e-6f) {
                val mono = 0.5f * (sampleL + sampleR)
                val (pl, pr) = pan2(mono, currentPan)
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
     private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
     private fun fract(x: Float): Float = x - floor(x)

     override fun getPhases(): Pair<Float, Float> = phaseL to phaseR

     override fun setPhases(phaseL: Float, phaseR: Float) {
         this.phaseL = phaseL
         this.phaseR = phaseR
     }
}

class NoiseSweptNotchVoice(
    params: Map<String, Any>,
    duration: Float,
    sampleRate: Float
) : Voice {
    private val amp = getFloat(params, "amp", 1.0f)
    private val noiseParams = NoiseParams.fromMap(params)
    private val calibrationFrames = min((duration * sampleRate).toInt(), (sampleRate.toInt() * 10)).coerceAtLeast(1)
    private val noiseState = StreamingNoise.newWithCalibratedPeak(noiseParams, sampleRate, calibrationFrames)
    private val generator = noiseState.first
    private val cachedPeak = noiseState.second
    private var remainingSamples = (duration * sampleRate).toInt()
    
    override fun process(output: FloatArray) {
        val needed = output.size
        // We only generate what fits in output or remaining samples
        val toGenerate = min(needed / 2, remainingSamples) // Frames
        if (toGenerate == 0) return

        val temp = FloatArray(toGenerate * 2)
        generator.generate(temp)
        for (i in 0 until toGenerate * 2) {
            output[i] += temp[i] * amp
        }
        
        remainingSamples -= toGenerate
    }

    override fun isFinished(): Boolean = remainingSamples <= 0

    override fun normalizationPeak(): Float = cachedPeak
}

class NoiseSweptNotchTransitionVoice(
    params: Map<String, Any>,
    duration: Float,
    sampleRate: Float
) : Voice {
    private val amp = getFloat(params, "amp", 1.0f)
    private val noiseParams = NoiseParams.fromMap(params)
    private val calibrationFrames = min((duration * sampleRate).toInt(), (sampleRate.toInt() * 10)).coerceAtLeast(1)
    private val noiseState = StreamingNoise.newWithCalibratedPeak(noiseParams, sampleRate, calibrationFrames)
    private val generator = noiseState.first
    private val cachedPeak = noiseState.second
    private var remainingSamples = (duration * sampleRate).toInt()
    
    override fun process(output: FloatArray) {
        val needed = output.size
        val toGenerate = min(needed / 2, remainingSamples)
        if (toGenerate == 0) return

        val temp = FloatArray(toGenerate * 2)
        generator.generate(temp)
        for (i in 0 until toGenerate * 2) {
            output[i] += temp[i] * amp
        }
        
        remainingSamples -= toGenerate
    }

    override fun isFinished(): Boolean = remainingSamples <= 0

    override fun normalizationPeak(): Float = cachedPeak
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
            null -> null
            else -> null
        }
        
        if (voice == null) return null
        
        val volEnv = data.volumeEnvelope
        if (volEnv != null) {
            val envVec = buildVolumeEnvelope(volEnv, duration, sampleRate.toInt())
            voice = VolumeEnvelopeVoice(voice, envVec)
        }
        
        val voiceTypeName = data.voiceType?.lowercase() ?: "binaural"
        val voiceType = when (voiceTypeName) {
            "noise" -> VoiceType.Noise
            "binaural" -> VoiceType.Binaural
            else -> VoiceType.Other
        }
        
        return StepVoice(
            kind = voice,
            voiceType = voiceType,
            normalizationPeak = voice.normalizationPeak()
        )
    }
}

// Wrapper for StepVoice to match Rust structure usage
data class StepVoice(
    val kind: Voice,
    val voiceType: VoiceType,
    val normalizationPeak: Float
)
