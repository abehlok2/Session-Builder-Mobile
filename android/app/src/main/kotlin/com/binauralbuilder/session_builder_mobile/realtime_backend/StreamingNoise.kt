package com.binauralbuilder.session_builder_mobile.realtime_backend


import com.binauralbuilder.session_builder_mobile.realtime_backend.dsp.*
import com.binauralbuilder.session_builder_mobile.realtime_backend.dsp.SimpleFft
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import kotlin.math.*

// --- Constants ---
const val BLOCK_SIZE = 2048
const val HOP_SIZE = 1024
const val CROSSFADE_SAMPLES = 2048
const val RENORM_WINDOW = 16384
const val RENORM_HYSTERESIS_RATIO = 0.10f
const val GAIN_SMOOTHING_COEFF = 0.99995f
const val OLA_RMS_HYSTERESIS_RATIO = 0.15f
const val OLA_GAIN_SMOOTHING_COEFF = 0.998f
const val UNDERRUN_FADE_SAMPLES = 512

// --- Helper Functions ---

fun lfoValue(phase: Float, waveform: String): Float {
    return if (waveform.equals("triangle", ignoreCase = true)) {
        scipySawtoothTriangle(phase)
    } else {
        cosLut(phase)
    }
}

data class NoisePreset(
    val exponent: Float,
    val highExponent: Float,
    val distributionCurve: Float,
    val lowcut: Float?,
    val highcut: Float?,
    val amplitude: Float
)

fun presetForType(type: String): NoisePreset? {
    return when (type) {
        "pink" -> NoisePreset(1.0f, 1.0f, 1.0f, null, null, 1.0f)
        "brown" -> NoisePreset(2.0f, 2.0f, 1.0f, null, null, 1.0f)
        "red" -> NoisePreset(2.0f, 1.5f, 1.0f, null, null, 1.0f)
        "green" -> NoisePreset(0.0f, 0.0f, 1.0f, 100.0f, 8000.0f, 1.0f)
        "blue" -> NoisePreset(-1.0f, -1.0f, 1.0f, null, null, 1.0f)
        "purple" -> NoisePreset(-2.0f, -2.0f, 1.0f, null, null, 1.0f)
        "deep brown" -> NoisePreset(2.5f, 2.0f, 1.0f, null, null, 1.0f)
        "white" -> NoisePreset(0.0f, 0.0f, 1.0f, null, null, 1.0f)
        else -> null
    }
}

fun resolvedNoiseName(params: NoiseParams): String {
    val nameElement = params.noise_parameters["name"]
    if (nameElement != null && nameElement.isJsonPrimitive()) {
        val primitive = nameElement.asJsonPrimitive
        if (primitive.isString) {
            return primitive.asString
        }
    }
    return "pink"
}

// --- Biquad Logic ---

data class Coeffs(
    val b0: Double, val b1: Double, val b2: Double,
    val a1: Double, val a2: Double
)

class BiquadState64 {
    var z1: Double = 0.0
    var z2: Double = 0.0
}

fun notchCoeffsF64(freq: Double, q: Double, sampleRate: Double): Coeffs {
    val w0 = 2.0 * PI * freq / sampleRate
    val cosW0 = cos(w0)
    val sinW0 = sin(w0)
    val alpha = sinW0 / (2.0 * q)

    val b0 = 1.0
    val b1 = -2.0 * cosW0
    val b2 = 1.0
    val a0 = 1.0 + alpha
    val a1 = -2.0 * cosW0
    val a2 = 1.0 - alpha

    return Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

fun biquadTimeVaryingBlock(
    block: FloatArray,
    freqSeries: FloatArray,
    qSeries: FloatArray,
    cascCounts: IntArray,
    state: Array<BiquadState64>,
    sampleRate: Double
) {
    val n = block.size
    val maxStage = state.size
    for (i in 0 until n) {
        var casc = cascCounts[i].coerceIn(1, maxStage)
        val freq = freqSeries[i].toDouble()
        if (freq <= 0.0 || freq >= sampleRate * 0.49) continue

        val q = max(qSeries[i].toDouble(), 1e-6)
        val coeffs = notchCoeffsF64(freq, q, sampleRate)

        var sample = block[i].toDouble()
        for (stage in 0 until casc) {
            val st = state[stage]
            val out = sample * coeffs.b0 + st.z1
            st.z1 = sample * coeffs.b1 - out * coeffs.a1 + st.z2
            st.z2 = sample * coeffs.b2 - out * coeffs.a2
            sample = out
        }
        block[i] = sample.toFloat()
    }
}

data class FilterCoeffs(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float
)

class BiquadFilter(private val coeffs: FilterCoeffs) {
    private var z1 = 0f
    private var z2 = 0f

    fun run(input: Float): Float {
        val out = input * coeffs.b0 + z1
        z1 = input * coeffs.b1 - out * coeffs.a1 + z2
        z2 = input * coeffs.b2 - out * coeffs.a2
        return out
    }
}

fun butterworthLowPass(freq: Float, sampleRate: Float): FilterCoeffs {
    val w0 = 2.0f * PI.toFloat() * freq / sampleRate
    val cosW0 = cos(w0)
    val sinW0 = sin(w0)
    val q = (1.0 / sqrt(2.0)).toFloat()
    val alpha = sinW0 / (2.0f * q)

    val b0 = (1.0f - cosW0) / 2.0f
    val b1 = 1.0f - cosW0
    val b2 = (1.0f - cosW0) / 2.0f
    val a0 = 1.0f + alpha
    val a1 = -2.0f * cosW0
    val a2 = 1.0f - alpha

    return FilterCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

fun butterworthHighPass(freq: Float, sampleRate: Float): FilterCoeffs {
    val w0 = 2.0f * PI.toFloat() * freq / sampleRate
    val cosW0 = cos(w0)
    val sinW0 = sin(w0)
    val q = (1.0 / sqrt(2.0)).toFloat()
    val alpha = sinW0 / (2.0f * q)

    val b0 = (1.0f + cosW0) / 2.0f
    val b1 = -(1.0f + cosW0)
    val b2 = (1.0f + cosW0) / 2.0f
    val a0 = 1.0f + alpha
    val a1 = -2.0f * cosW0
    val a2 = 1.0f - alpha

    return FilterCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

// --- Worker & Requests ---

class NoiseGenRequest(val buffer: FloatArray)
class NoiseGenResponse(val buffer: FloatArray, val targetRms: Float?)

class AsyncNoiseWorker(
    val size: Int,
    val exponent: Float,
    val highExponent: Float,
    val distributionCurve: Float,
    val sampleRate: Float,
    val random: java.util.Random
) {
    private val fft = SimpleFft(size)
    private val fftReal = FloatArray(size)
    private val fftImag = FloatArray(size)
    var targetRms: Float? = null

    fun regenerateInto(target: FloatArray): Float? {
        // Fill scratch with white noise
        for (i in 0 until size) {
            fftReal[i] = random.nextGaussian().toFloat()
            fftImag[i] = 0f
        }

        fft.realForward(fftReal, fftImag)

        val nyquist = sampleRate / 2.0f
        val minF = sampleRate / size.toFloat()

        // DC
        fftReal[0] = 0f; fftImag[0] = 0f

        for (i in 1..size / 2) {
            val freq = i * sampleRate / size.toFloat()
            if (freq <= 0f) continue

            val logMin = ln(minF)
            val logMax = ln(nyquist)
            val logF = ln(freq)

            val denom = max(logMax - logMin, 1e-12f)
            var logNorm = (logF - logMin) / denom
            logNorm = logNorm.coerceIn(0.0f, 1.0f)
            
            val interp = logNorm.pow(distributionCurve)
            val currentExp = exponent + (highExponent - exponent) * interp
            val scale = freq.pow(-currentExp / 2.0f)

            fftReal[i] *= scale
            fftImag[i] *= scale
            // Conjugate symmetry handled by realInverse logic usually, 
            // but SimpleFft.realInverse expects packed format or full complex?
            // SimpleFft handles R2C symmetry implicitly or expects full array? 
            // Our SimpleFft is a full complex FFT. We need to manually enforce symmetry for real output?
            // Actually, for pure real noise generation, we can just randomize phase or symmetric magnitude?
            // Wait, Rust used `rustfft` C2C. We generated complex noise.
            // If we want real output, we should enforce symmetry: X[N-k] = X[k]*
            if (i < size / 2 && i > 0) {
                 val conjIdx = size - i
                 fftReal[conjIdx] = fftReal[i]
                 fftImag[conjIdx] = -fftImag[i]
            }
        }
        // Nyquist element (if size even)
        if (size % 2 == 0) {
            val idx = size / 2
            // Must be real
             fftImag[idx] = 0f
        }

        fft.realInverse(fftReal, fftImag)

        // Copy to target
        for (i in 0 until size) {
            target[i] = fftReal[i]
        }

        // RMS Locking
        var sumSq = 0f
        for (x in target) sumSq += x * x
        val currentRms = sqrt(sumSq / target.size)

        var finalRms = targetRms
        if (currentRms > 1e-9f) {
            if (targetRms != null) {
                val gain = targetRms!! / currentRms
                for (i in target.indices) target[i] = (target[i] * gain).coerceIn(-1.0f, 1.0f)
            } else {
                // First buffer: Peak Norm + Set Target
                var maxVal = 0f
                for (x in target) maxVal = max(maxVal, abs(x))
                if (maxVal > 1e-9f) {
                    for (i in target.indices) target[i] /= maxVal
                    var sumSqNorm = 0f
                    for (x in target) sumSqNorm += x * x
                    finalRms = sqrt(sumSqNorm / target.size)
                }
            }
        }
        return finalRms
    }
}

class FftNoiseGenerator(
    val params: NoiseParams,
    val sampleRate: Float,
    val scope: CoroutineScope
) {
    var buffer: FloatArray
    var nextBufferStorage: FloatArray
    var nextBufferReady: Boolean = false
    var cursor: Int = 0
    var size: Int

    private val workerChannel = Channel<NoiseGenRequest>(2)
    private val responseChannel = Channel<NoiseGenResponse>(2)
    private var workerRequested = false

    private var lpFilters: List<BiquadFilter>? = null
    private var hpFilters: List<BiquadFilter>? = null

    var renormGain = 1.0f
    var smoothedGain = 1.0f
    var renormInitialized = false
    var preRmsAccum = 0f
    var postRmsAccum = 0f
    var rmsSamples = 0
    var isUnmodulated = true
    var underrunRecovering = false
    var underrunFadePos = 0
    var baseAmplitude: Float = params.amplitude ?: 1.0f

    init {
        val noiseLabel = resolvedNoiseName(params).lowercase()
        val preset = presetForType(noiseLabel)
        val exponent = params.exponent ?: preset?.exponent ?: 0.0f
        val highExponent = params.high_exponent ?: preset?.highExponent ?: exponent
        val distributionCurve = (params.distribution_curve ?: preset?.distributionCurve ?: 1.0f).coerceAtLeast(1e-6f)
        val lowcut = params.lowcut ?: preset?.lowcut
        val highcut = params.highcut ?: preset?.highcut
        baseAmplitude = params.amplitude ?: preset?.amplitude ?: 1.0f
        val seed = (params.seed ?: 1L).coerceAtLeast(0L)

        val requested = (params.duration_seconds.coerceAtLeast(0.0f) * sampleRate).toInt()
        val defaultSize = 1 shl 15
        size = if (requested in 1 until defaultSize) requested else defaultSize
        if (size < 8) {
            size = 8
        }
        val actualSize = if (size % 2 != 0) size + 1 else size
        size = actualSize

        val nyquist = sampleRate / 2.0f
        if (lowcut != null && lowcut > 0f && lowcut < nyquist) {
            val coeffs = butterworthHighPass(lowcut, sampleRate)
            lpFilters = listOf(BiquadFilter(coeffs), BiquadFilter(coeffs))
        }
        if (highcut != null && highcut > 0f && highcut < nyquist) {
            val coeffs = butterworthLowPass(highcut, sampleRate)
            hpFilters = listOf(BiquadFilter(coeffs), BiquadFilter(coeffs))
        }

        val worker = AsyncNoiseWorker(
            actualSize,
            exponent,
            highExponent,
            distributionCurve,
            sampleRate,
            java.util.Random(seed)
        )
        
        // Launch worker
        scope.launch(Dispatchers.Default) {
             for (req in workerChannel) {
                 val newRms = try {
                     worker.regenerateInto(req.buffer)
                 } catch (e: Exception) {
                     e.printStackTrace()
                     null
                 }
                 // Logic for persistent target RMS in worker?
                 // In Kotlin, worker is valid in closure.
                 // We can update worker.targetRms
                 if (worker.targetRms == null && newRms != null) {
                     worker.targetRms = newRms
                 }
                 responseChannel.send(NoiseGenResponse(req.buffer, worker.targetRms))
             }
        }

        // Initial Buffers (Synchronous generation for startup)
        val buf1 = FloatArray(actualSize)
        val rms1 = worker.regenerateInto(buf1)
        worker.targetRms = rms1
        buffer = buf1

        val buf2 = FloatArray(actualSize)
        worker.regenerateInto(buf2) // Worker keeps RMS
        nextBufferStorage = buf2
        nextBufferReady = true
        cursor = 0
    }

    fun next(): Float {
        val crossfadeLen = min(buffer.size, CROSSFADE_SAMPLES)
        
        // Trigger worker
        if (!nextBufferReady && !workerRequested) {
            if (cursor >= size / 2) {
                // Recycle? We need to allocate or recycle. 
                // For simplicity, allocate new or reuse a pool. 
                // We'll create new request with empty buffer (or recycled if we managed it).
                // Here we just allocate to be safe, GC handles it. Optimizable later.
                val buf = FloatArray(size) 
                if (workerChannel.trySend(NoiseGenRequest(buf)).isSuccess) {
                    workerRequested = true
                }
            }
        }

        // Check response
        if (workerRequested) {
            val res = responseChannel.tryReceive().getOrNull()
            if (res != null) {
                nextBufferStorage = res.buffer
                nextBufferReady = true
                workerRequested = false
            }
        }

        // Buffer Swap
        if (cursor >= buffer.size) {
            if (nextBufferReady) {
                // Crossfade skip
                val skip = crossfadeLen.coerceAtMost(nextBufferStorage.size)
                // Swap
                val tmp = buffer
                buffer = nextBufferStorage
                nextBufferStorage = tmp // Recycle conceptually
                
                cursor = skip
                nextBufferReady = false
                underrunRecovering = false
                underrunFadePos = 0
            } else {
                // Underrun
                cursor = 0
                underrunRecovering = true
                underrunFadePos = 0
            }
        }

        var sample = buffer[cursor]
        
        // Crossfade logic
        if (nextBufferReady) {
            val start = buffer.size - crossfadeLen
            if (cursor >= start) {
                val idx = cursor - start
                val t = idx.toFloat() / crossfadeLen
                val fadeOut = 0.5f * (1.0f + cos(PI.toFloat() * t))
                val fadeIn = 1.0f - fadeOut
                val nextSamp = if (idx < nextBufferStorage.size) nextBufferStorage[idx] else 0f
                sample = sample * fadeOut + nextSamp * fadeIn
            }
        }

        // Underrun Fade
        if (underrunRecovering) {
            if (underrunFadePos < UNDERRUN_FADE_SAMPLES) {
                val t = underrunFadePos.toFloat() / UNDERRUN_FADE_SAMPLES
                val fadeIn = 0.5f * (1.0f - cos(PI.toFloat() * t))
                val fadeOut = 1.0f - fadeIn
                
                val tailIdx = (buffer.size - UNDERRUN_FADE_SAMPLES + underrunFadePos).coerceIn(0, buffer.size - 1)
                val tailSamp = buffer[tailIdx]
                sample = tailSamp * fadeOut + sample * fadeIn
                underrunFadePos++
            } else {
                underrunRecovering = false
                underrunFadePos = 0
            }
        }
        
        cursor++

        val preFilterSample = sample
        lpFilters?.forEach { sample = it.run(sample) }
        hpFilters?.forEach { sample = it.run(sample) }
        sample = applyPostFilterRenorm(preFilterSample, sample)

        return sample * baseAmplitude
    }

    private fun applyPostFilterRenorm(pre: Float, post: Float): Float {
        preRmsAccum += pre * pre
        postRmsAccum += post * post
        rmsSamples++

        if (rmsSamples >= RENORM_WINDOW) {
            val preRms = sqrt(preRmsAccum / rmsSamples.toFloat())
            val postRms = sqrt(postRmsAccum / rmsSamples.toFloat())

            if (preRms > 1e-6f && postRms > 1e-6f) {
                val targetGain = (preRms / postRms).coerceIn(0.25f, 16.0f)
                if (isUnmodulated) {
                    if (!renormInitialized) {
                        renormGain = targetGain
                        smoothedGain = targetGain
                        renormInitialized = true
                    }
                } else {
                    val ratioDiff = abs(targetGain - renormGain) / renormGain
                    if (ratioDiff > RENORM_HYSTERESIS_RATIO) {
                        if (!renormInitialized) {
                            renormGain = targetGain
                            smoothedGain = targetGain
                            renormInitialized = true
                        } else {
                            renormGain = 0.8f * renormGain + 0.2f * targetGain
                        }
                    }
                }
            } else if (!renormInitialized) {
                renormGain = 1.0f
                smoothedGain = 1.0f
                renormInitialized = true
            }

            preRmsAccum = 0f
            postRmsAccum = 0f
            rmsSamples = 0
        }

        smoothedGain = GAIN_SMOOTHING_COEFF * smoothedGain + (1.0f - GAIN_SMOOTHING_COEFF) * renormGain
        return post * smoothedGain
    }
}

class OlaState {
    val inputRing = FloatArray(BLOCK_SIZE)
    var inputWritePos = 0
    var inputSamplesBuffered = 0
    
    val outAccL = FloatArray(BLOCK_SIZE * 2)
    val outAccR = FloatArray(BLOCK_SIZE * 2)
    val winAcc = FloatArray(BLOCK_SIZE * 2)
    
    var accReadPos = 0
    var accWritePos = 0
    var samplesReady = 0
    var absoluteBlockStart = 0
    
    val window = FloatArray(BLOCK_SIZE) { i ->
         0.5f - 0.5f * cos(2.0f * PI.toFloat() * i / (BLOCK_SIZE - 1)).toFloat()
    }
    
    // Scratch buffers
    val blockL = FloatArray(BLOCK_SIZE)
    val blockR = FloatArray(BLOCK_SIZE)
    
    // Series buffers
    val tVals = FloatArray(BLOCK_SIZE)
    val lfoMainL = FloatArray(BLOCK_SIZE)
    val lfoMainR = FloatArray(BLOCK_SIZE)
    val lfoExtraL = FloatArray(BLOCK_SIZE)
    val lfoExtraR = FloatArray(BLOCK_SIZE)
    val minSeries = FloatArray(BLOCK_SIZE)
    val maxSeries = FloatArray(BLOCK_SIZE)
    val qSeries = FloatArray(BLOCK_SIZE)
    val cascSeries = IntArray(BLOCK_SIZE)
    val notchFreqL = FloatArray(BLOCK_SIZE)
    val notchFreqR = FloatArray(BLOCK_SIZE)
    val notchFreqLExtra = FloatArray(BLOCK_SIZE)
    val notchFreqRExtra = FloatArray(BLOCK_SIZE)
    val cascSeriesClamped = IntArray(BLOCK_SIZE)
    
    var smoothedGainL = 1.0f
    var smoothedGainR = 1.0f
}

data class SweepParams(
    val startMin: Float, val endMin: Float,
    val startMax: Float, val endMax: Float,
    val startQ: Float, val endQ: Float,
    val startCasc: Int, val endCasc: Int
)

class SweepRuntime(var maxCasc: Int) {
    val lMain = Array(maxCasc) { BiquadState64() }
    val rMain = Array(maxCasc) { BiquadState64() }
    val lExtra = Array(maxCasc) { BiquadState64() }
    val rExtra = Array(maxCasc) { BiquadState64() }
}

class StreamingNoise(
    val params: NoiseParams,
    val sampleRate: Float
) : Closeable {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val fftGen = FftNoiseGenerator(params, sampleRate, scope)
    private val ola = OlaState()
    private var sweepParams: List<SweepParams> = emptyList()
    private var sweepRuntime: List<SweepRuntime> = emptyList()
    private val durationSamples = (params.duration_seconds * sampleRate).toInt()
    
    // State
    private var totalSamplesOutput = 0
    private var lfoFreq = 0f
    private var startLfoFreq = 0f
    private var endLfoFreq = 0f
    private var startLfoPhaseOffset = 0f
    private var endLfoPhaseOffset = 0f
    private var startIntraOffset = 0f
    private var endIntraOffset = 0f
    private var initialOffset = params.initial_offset
    private var transition = params.transition
    private var lfoWaveform = params.lfo_waveform

    init {
        sweepParams = buildSweepParams(params)
        sweepRuntime = sweepParams.map { SweepRuntime(max(it.startCasc, it.endCasc).coerceAtLeast(1)) }
        updateRealtimeParams(params)
        if (params.sweeps.isEmpty()) {
            repeat(RENORM_WINDOW) {
                fftGen.next()
            }
        }
    }

    private fun buildSweepParams(p: NoiseParams): List<SweepParams> {
        return p.sweeps.map { s ->
            val startMin = if (s.start_min > 0f) s.start_min else 1000f
            val endMin = if (s.end_min > 0f) s.end_min else startMin
            val startMax = if (s.start_max > 0f) max(s.start_max, startMin + 1.0f) else startMin + 9000f
            val endMax = if (s.end_max > 0f) max(s.end_max, endMin + 1.0f) else startMax
            val startQ = if (s.start_q > 0f) s.start_q else 25.0f
            val endQ = if (s.end_q > 0f) s.end_q else startQ
            val startCasc = if (s.start_casc > 0) s.start_casc else 10
            val endCasc = if (s.end_casc > 0) s.end_casc else startCasc
            SweepParams(startMin, endMin, startMax, endMax, startQ, endQ, startCasc, endCasc)
        }
    }

    fun updateRealtimeParams(p: NoiseParams): Boolean {
        if (p.sweeps.size != sweepParams.size) {
            return false
        }

        val lfoBase = if (p.transition) {
            p.start_lfo_freq
        } else if (p.lfo_freq != 0f) {
            p.lfo_freq
        } else {
            1.0f / 12.0f
        }

        val updatedSweeps = buildSweepParams(p)
        sweepRuntime.forEachIndexed { index, runtime ->
            val sweep = updatedSweeps[index]
            val maxCasc = max(sweep.startCasc, sweep.endCasc).coerceAtLeast(1)
            if (maxCasc > runtime.maxCasc) {
                return false
            }
            runtime.maxCasc = maxCasc
        }

        sweepParams = updatedSweeps
        transition = p.transition
        lfoWaveform = p.lfo_waveform
        lfoFreq = lfoBase
        startLfoFreq = if (p.start_lfo_freq > 0f) p.start_lfo_freq else lfoBase
        endLfoFreq = if (p.end_lfo_freq > 0f) p.end_lfo_freq else lfoBase
        startLfoPhaseOffset = p.start_lfo_phase_offset_deg * (PI.toFloat() / 180.0f)
        endLfoPhaseOffset = p.end_lfo_phase_offset_deg * (PI.toFloat() / 180.0f)
        startIntraOffset = p.start_intra_phase_offset_deg * (PI.toFloat() / 180.0f)
        endIntraOffset = p.end_intra_phase_offset_deg * (PI.toFloat() / 180.0f)
        initialOffset = p.initial_offset
        fftGen.baseAmplitude = p.amplitude ?: 1.0f
        return true
    }

    fun generate(out: FloatArray) {
        val frames = out.size / 2
        var framesWritten = 0
        val accSize = ola.outAccL.size
        
        while (framesWritten < frames) {
            if (ola.samplesReady > 0) {
                val readPos = ola.accReadPos
                val winVal = ola.winAcc[readPos]
                
                val l = if (winVal > 1e-8f) ola.outAccL[readPos] / winVal else 0f
                val r = if (winVal > 1e-8f) ola.outAccR[readPos] / winVal else 0f
                
                out[framesWritten * 2] = l
                out[framesWritten * 2 + 1] = r
                
                // Clear
                ola.outAccL[readPos] = 0f
                ola.outAccR[readPos] = 0f
                ola.winAcc[readPos] = 0f
                
                ola.accReadPos = (readPos + 1) % accSize
                ola.samplesReady--
                totalSamplesOutput++
                framesWritten++
            } else {
                // Refill input
                while (ola.inputSamplesBuffered < BLOCK_SIZE) {
                    ola.inputRing[ola.inputWritePos] = fftGen.next()
                    ola.inputWritePos = (ola.inputWritePos + 1) % BLOCK_SIZE
                    ola.inputSamplesBuffered++
                }
                processOlaBlock()
                ola.inputSamplesBuffered = BLOCK_SIZE - HOP_SIZE
            }
        }
    }

    fun skipSamples(samples: Int) {
        if (samples <= 0) return
        val scratch = FloatArray(samples * 2)
        generate(scratch)
    }
    
    // --- Transition & LFO Helpers ---

    private fun transitionFraction(sampleIdx: Long): Float {
        if (!transition || durationSamples <= 0) return 0f
        val durationSamplesLong = durationSamples.toLong()
        if (durationSamplesLong == 0L) return 0f
        return (sampleIdx.toFloat() / durationSamplesLong).coerceIn(0f, 1f)
    }

    private fun interpolateLfoFreq(t: Float): Float {
        if (!transition) return lfoFreq
        return startLfoFreq + (endLfoFreq - startLfoFreq) * t
    }

    private fun interpolatePhaseOffset(t: Float): Float {
        if (!transition) return startLfoPhaseOffset
        return startLfoPhaseOffset + (endLfoPhaseOffset - startLfoPhaseOffset) * t
    }

    private fun interpolateIntraOffset(t: Float): Float {
        if (!transition) return startIntraOffset
        return startIntraOffset + (endIntraOffset - startIntraOffset) * t
    }

    private fun computeLfoPhase(sampleIdx: Long, lfoFreq: Float, extraPhaseOffset: Float): Float {
        val t = sampleIdx.toDouble() / sampleRate + initialOffset
        return (2.0 * PI * lfoFreq * t + extraPhaseOffset).toFloat()
    }

    private fun processOlaBlock() {
        val blockStart = ola.absoluteBlockStart.toLong()
        val doExtra = abs(startIntraOffset) > 1e-6f || abs(endIntraOffset) > 1e-6f

        // 1. LFO Calculation
        for (i in 0 until BLOCK_SIZE) {
            val absIdx = blockStart + i
            val t = transitionFraction(absIdx)
            ola.tVals[i] = t

            val currentLfoFreq = interpolateLfoFreq(t)
            val phaseOffset = interpolatePhaseOffset(t)
            val intraOffset = interpolateIntraOffset(t)

            val lPhase = computeLfoPhase(absIdx, currentLfoFreq, 0f)
            val rPhase = computeLfoPhase(absIdx, currentLfoFreq, phaseOffset)

            ola.lfoMainL[i] = lfoValue(lPhase, lfoWaveform)
            ola.lfoMainR[i] = lfoValue(rPhase, lfoWaveform)

            if (doExtra) {
                ola.lfoExtraL[i] = lfoValue(lPhase + intraOffset, lfoWaveform)
                ola.lfoExtraR[i] = lfoValue(rPhase + intraOffset, lfoWaveform)
            }
        }

        // 2. Copy Input (Ring buffer)
        var sumSqIn = 0f
        for (i in 0 until BLOCK_SIZE) {
            val idx = (ola.inputWritePos + BLOCK_SIZE - ola.inputSamplesBuffered + i) % BLOCK_SIZE
            val samp = ola.inputRing[idx]
            ola.blockL[i] = samp
            ola.blockR[i] = samp
            sumSqIn += samp * samp
        }
        val rmsIn = sqrt(sumSqIn / BLOCK_SIZE.toFloat())

        // 3. Filtering
        for ((si, sp) in sweepParams.withIndex()) {
            val rt = sweepRuntime[si]
            
            // Calc Series parameters for this block
            for (i in 0 until BLOCK_SIZE) {
                 val t = ola.tVals[i]
                 ola.minSeries[i] = sp.startMin + (sp.endMin - sp.startMin) * t
                 ola.maxSeries[i] = sp.startMax + (sp.endMax - sp.startMax) * t
                 ola.qSeries[i] = sp.startQ + (sp.endQ - sp.startQ) * t
                 
                 val cascF = sp.startCasc.toFloat() + (sp.endCasc.toFloat() - sp.startCasc.toFloat()) * t
                 ola.cascSeries[i] = max(cascF.roundToInt(), 1)
            }
            
            // Compute Notch Frequencies
            for (i in 0 until BLOCK_SIZE) {
                val centerFreq = (ola.minSeries[i] + ola.maxSeries[i]) * 0.5f
                val freqRange = (ola.maxSeries[i] - ola.minSeries[i]) * 0.5f
                
                ola.notchFreqL[i] = centerFreq + freqRange * ola.lfoMainL[i]
                ola.notchFreqR[i] = centerFreq + freqRange * ola.lfoMainR[i]
                
                if (doExtra) {
                    ola.notchFreqLExtra[i] = centerFreq + freqRange * ola.lfoExtraL[i]
                    ola.notchFreqRExtra[i] = centerFreq + freqRange * ola.lfoExtraR[i]
                }
            }
             
            for (i in 0 until BLOCK_SIZE) {
                ola.cascSeriesClamped[i] = ola.cascSeries[i].coerceIn(1, rt.maxCasc)
            }

            // Biquad apply
            biquadTimeVaryingBlock(ola.blockL, ola.notchFreqL, ola.qSeries, ola.cascSeriesClamped, rt.lMain, sampleRate.toDouble())
            biquadTimeVaryingBlock(ola.blockR, ola.notchFreqR, ola.qSeries, ola.cascSeriesClamped, rt.rMain, sampleRate.toDouble())
            
            if (doExtra) {
                 biquadTimeVaryingBlock(ola.blockL, ola.notchFreqLExtra, ola.qSeries, ola.cascSeriesClamped, rt.lExtra, sampleRate.toDouble())
                 biquadTimeVaryingBlock(ola.blockR, ola.notchFreqRExtra, ola.qSeries, ola.cascSeriesClamped, rt.rExtra, sampleRate.toDouble())
            }
        }

        if (sweepParams.isNotEmpty() && rmsIn > 1e-8f) {
            var sumSqL = 0f
            var sumSqR = 0f
            for (i in 0 until BLOCK_SIZE) {
                sumSqL += ola.blockL[i] * ola.blockL[i]
                sumSqR += ola.blockR[i] * ola.blockR[i]
            }
            val rmsL = sqrt(sumSqL / BLOCK_SIZE.toFloat())
            val rmsR = sqrt(sumSqR / BLOCK_SIZE.toFloat())

            val rawTargetL = if (rmsL > 1e-8f) {
                (rmsIn / rmsL).coerceIn(0.25f, 16.0f)
            } else {
                ola.smoothedGainL
            }
            val rawTargetR = if (rmsR > 1e-8f) {
                (rmsIn / rmsR).coerceIn(0.25f, 16.0f)
            } else {
                ola.smoothedGainR
            }

            val ratioDiffL = abs(rawTargetL - ola.smoothedGainL) / max(ola.smoothedGainL, 0.01f)
            val ratioDiffR = abs(rawTargetR - ola.smoothedGainR) / max(ola.smoothedGainR, 0.01f)

            val targetGainL = if (ratioDiffL > OLA_RMS_HYSTERESIS_RATIO) rawTargetL else ola.smoothedGainL
            val targetGainR = if (ratioDiffR > OLA_RMS_HYSTERESIS_RATIO) rawTargetR else ola.smoothedGainR

            val smoothCoeff = OLA_GAIN_SMOOTHING_COEFF
            val oneMinusCoeff = 1.0f - smoothCoeff

            for (i in 0 until BLOCK_SIZE) {
                ola.smoothedGainL = smoothCoeff * ola.smoothedGainL + oneMinusCoeff * targetGainL
                ola.blockL[i] *= ola.smoothedGainL
                ola.smoothedGainR = smoothCoeff * ola.smoothedGainR + oneMinusCoeff * targetGainR
                ola.blockR[i] *= ola.smoothedGainR
            }
        }

        // 4. Window & Accumulate
        for (i in 0 until BLOCK_SIZE) {
            val w = ola.window[i]
            ola.blockL[i] *= w
            ola.blockR[i] *= w
            
            val accIdx = (ola.accWritePos + i) % (BLOCK_SIZE * 2)
            ola.outAccL[accIdx] += ola.blockL[i]
            ola.outAccR[accIdx] += ola.blockR[i]
            ola.winAcc[accIdx] += w
        }
        
        ola.accWritePos = (ola.accWritePos + HOP_SIZE) % (BLOCK_SIZE * 2)
        ola.samplesReady += HOP_SIZE
        ola.absoluteBlockStart += HOP_SIZE
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        fun newWithCalibratedPeak(
            params: NoiseParams,
            sampleRate: Float,
            calibrationFrames: Int
        ): Pair<StreamingNoise, Float> {
            val frames = max(calibrationFrames, 1)
            val generator = StreamingNoise(params, sampleRate)
            val scratch = FloatArray(frames * 2)
            generator.generate(scratch)

            val absVals = scratch.map { abs(it) }.sorted()
            val idx = floor(absVals.size.toDouble() * 0.999).toInt().coerceIn(0, absVals.size - 1)
            val peak = max(absVals[idx], 1e-9f)
            return generator to peak
        }
    }
}
