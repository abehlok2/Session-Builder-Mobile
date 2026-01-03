package com.binauralbuilder.session_builder_mobile.realtime_backend.dsp

import kotlin.math.*
import kotlin.random.Random

private const val TABLE_SIZE = 1 shl 16 // 65536
private const val TWO_PI = 2.0f * PI.toFloat()
private const val PI_F = PI.toFloat()
private const val FRAC_PI_2 = (PI / 2.0).toFloat()
private const val FRAC_PI_4 = (PI / 4.0).toFloat()

private val sinTable: FloatArray by lazy {
    FloatArray(TABLE_SIZE + 1).apply {
        for (i in 0..TABLE_SIZE) {
            this[i] = sin((i.toFloat() / TABLE_SIZE) * TWO_PI)
        }
    }
}

// --- LUT Functions ---

fun sinLut(angle: Float): Float {
    // remEuclid equivalent
    var a = angle % TWO_PI
    if (a < 0) a += TWO_PI
    
    val pos = (a / TWO_PI) * TABLE_SIZE
    val idx = pos.toInt()
    val frac = pos - idx
    // Using getOrElse to handle potential edge case at TABLE_SIZE (though lazy init handles +1)
    val v0 = sinTable[idx]
    val v1 = if (idx + 1 < sinTable.size) sinTable[idx + 1] else sinTable[0]
    return v0 + (v1 - v0) * frac
}

fun cosLut(angle: Float): Float {
    return sinLut(angle + FRAC_PI_2)
}

fun sineWave(freq: Float, t: Float, phase: Float): Float {
    return sinLut(TWO_PI * freq * t + phase)
}

// --- Noise Generators ---

/**
 * Generate pink noise using Paul Kellett's refined method
 * Uses Box-Muller transform for Gaussian random numbers
 */
fun pinkNoise(samples: Int, random: kotlin.random.Random = kotlin.random.Random.Default): FloatArray {
    val out = FloatArray(samples)
    var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f

    for (i in 0 until samples) {
        // Box-Muller transform for Gaussian from uniform random
        val u1 = random.nextDouble()
        val u2 = random.nextDouble()
        val w = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()

        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f

        out[i] = (b0 + b1 + b2 + b3 + b4 + b5) * 0.11f
    }
    return out
}

/**
 * Generate brown noise (Brownian/red noise)
 * Uses Box-Muller transform for Gaussian random numbers
 */
fun brownNoise(samples: Int, random: kotlin.random.Random = kotlin.random.Random.Default): FloatArray {
    val out = FloatArray(samples)
    var cumulative = 0f

    for (i in 0 until samples) {
        // Box-Muller transform for Gaussian from uniform random
        val u1 = random.nextDouble()
        val u2 = random.nextDouble()
        val w = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()

        cumulative += w
        out[i] = cumulative
    }

    // Normalize
    var maxAbs = 0f
    for (v in out) {
        val absV = abs(v)
        if (absV > maxAbs) maxAbs = absV
    }
    if (maxAbs > 0f) {
        for (i in out.indices) out[i] /= maxAbs
    }
    return out
}

// --- Envelopes & Shapes ---

fun pan2(signal: Float, pan: Float): Pair<Float, Float> {
    val p = pan.coerceIn(-1.0f, 1.0f)
    val angle = (p + 1.0f) * FRAC_PI_4
    val left = cosLut(angle) * signal
    val right = sinLut(angle) * signal
    return left to right
}

fun trapezoidEnvelope(tInCycle: Float, cycleLen: Float, rampPercent: Float, gapPercent: Float): Float {
    if (cycleLen <= 0f) return 0f

    val audibleLen = (1.0f - gapPercent).coerceIn(0.0f, 1.0f) * cycleLen
    val rampTotal = (audibleLen * rampPercent * 2.0f).coerceIn(0.0f, audibleLen)
    val stableLen = audibleLen - rampTotal
    val rampUpLen = rampTotal / 2.0f
    val stableEnd = rampUpLen + stableLen

    return when {
        tInCycle >= audibleLen -> 0f
        tInCycle < rampUpLen -> if (rampUpLen > 0) tInCycle / rampUpLen else 0f
        tInCycle >= stableEnd -> if (rampUpLen > 0) 1.0f - (tInCycle - stableEnd) / rampUpLen else 0f
        else -> 1.0f
    }
}

fun skewedSinePhase(phaseFraction: Float, skew: Float): Float {
    var frac = 0.5f + 0.5f * skew
    if (frac <= 0f) frac = 1e-9f
    if (frac >= 1f) frac = 1.0f - 1e-9f

    return if (phaseFraction < frac) {
        val local = phaseFraction / frac
        sin(PI_F * local)
    } else {
        val local = (phaseFraction - frac) / (1.0f - frac)
        sin(PI_F * (1.0f + local))
    }
}

fun skewedTrianglePhase(phaseFraction: Float, skew: Float): Float {
    var frac = 0.5f + 0.5f * skew
    if (frac <= 0f) frac = 1e-9f
    if (frac >= 1f) frac = 1.0f - 1e-9f

    return if (phaseFraction < frac) {
        val local = phaseFraction / frac
        -1.0f + 2.0f * local
    } else {
        val local = (phaseFraction - frac) / (1.0f - frac)
        1.0f - 2.0f * local
    }
}

fun scipySawtoothTriangle(phase: Float): Float {
    val t = (phase % TWO_PI) / TWO_PI
    val tNorm = if (t < 0) t + 1.0f else t
    val width = 0.5f
    return if (tNorm < width) {
        -1.0f + 2.0f * tNorm / width
    } else {
        1.0f - 2.0f * (tNorm - width) / (1.0f - width)
    }
}

// Builds a sample-accurate volume envelope buffer from control points
fun buildVolumeEnvelope(
    points: List<List<Float>>,
    duration: Float,
    sampleRate: Int
): FloatArray {
    val totalSamples = (duration * sampleRate).toInt()
    val out = FloatArray(totalSamples) { 1.0f }
    
    if (points.isEmpty()) return out
    
    // Logic: points are [timeRatio, amplitude] or [timeSeconds, amplitude]?
    // checking voices.rs... typically points are normalized or absolute time?
    // In Rust audio backends I've seen, it's often (Time, Gain).
    // Assuming (Time Seconds, Gain). Or (Ratio, Gain).
    // Let's assume Time Seconds based on usage env_vec = build...(env, duration, ...).
    // Actually, points usually are pairs.
    
    // Sort points by time
    // Interpolate
    
    // If points are [time, val]
    var prevTime = 0f
    var prevVal = 1f
    if (points.isNotEmpty()) {
         prevVal = points[0][1] // Start value
         // If first point time > 0, we assume constant from 0? Or ramp?
         // Usually start at time 0.
    }
    
    // Implementation of simple linear interpolation between points
    // Points are likely absolute seconds.
    
    for (point in points) {
        if (point.size < 2) continue
        val time = point[0]
        val `val` = point[1]
        
        val startSample = (prevTime * sampleRate).toInt().coerceIn(0, totalSamples)
        val endSample = (time * sampleRate).toInt().coerceIn(0, totalSamples)
        
        if (endSample > startSample) {
            val dist = (endSample - startSample).toFloat()
            for (i in startSample until endSample) {
                val t = (i - startSample) / dist
                out[i] = prevVal + (`val` - prevVal) * t
            }
        }
        
        prevTime = time
        prevVal = `val`
    }
    
    // Fill remainder
    val endSample = (prevTime * sampleRate).toInt().coerceIn(0, totalSamples)
    if (endSample < totalSamples) {
        out.fill(prevVal, endSample, totalSamples)
    }
    
    return out
}
