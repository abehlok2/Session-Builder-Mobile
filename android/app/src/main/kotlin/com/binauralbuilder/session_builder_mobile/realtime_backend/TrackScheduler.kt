package com.binauralbuilder.session_builder_mobile.realtime_backend

import com.binauralbuilder.session_builder_mobile.realtime_backend.dsp.*
import kotlin.math.*

enum class CrossfadeCurve {
    Linear,
    EqualPower;

    fun gains(ratio: Float): Pair<Float, Float> {
        return when (this) {
            Linear -> Pair(1.0f - ratio, ratio)
            EqualPower -> {
                val theta = ratio * (PI.toFloat() / 2.0f)
                Pair(cos(theta), sin(theta)) // Using standard math for simplicity, or could use LUT
            }
        }
    }
}

private fun stepsHaveContinuousVoices(a: StepData, b: StepData): Boolean {
    if (a.voices.size != b.voices.size) return false
    return a.voices.zip(b.voices).all { (va, vb) ->
        va.synthFunctionName == vb.synthFunctionName &&
            va.params == vb.params &&
            va.isTransition == vb.isTransition &&
            va.voiceType.equals(vb.voiceType, ignoreCase = true)
    }
}

class TrackScheduler(
    var track: TrackData,
    val sampleRate: Float
) {
    var currentSample: Long = 0
    var absoluteSample: Long = 0
    var currentStepIndex: Int = 0
    
    private val activeVoices = ArrayList<StepVoice>()
    private val nextVoices = ArrayList<StepVoice>()
    
    private var crossfadeSamples: Int = 0
    private var currentCrossfadeSamples: Int = 0
    private var crossfadeCurve: CrossfadeCurve = CrossfadeCurve.Linear
    private var crossfadeEnvelope = FloatArray(0)
    
    private var crossfadePrev = FloatArray(0)
    private var crossfadeNext = FloatArray(0)
    private var crossfadeActive = false
    private var nextStepSample = 0
    
    var paused = false
    
    // Gains
    var voiceGain = 1.0f
    var noiseGain = 1.0f
    var masterGain = 1.0f
    
    // Background Noise
    private var backgroundNoise: BackgroundNoise? = null
    
    // Scratch buffers
    private var scratch = FloatArray(4096)
    private var noiseScratch = FloatArray(4096)
    private var voiceTemp = FloatArray(4096)
    
    // Phase continuity
    private val accumulatedPhases = ArrayList<Pair<Float, Float>>()

    init {
        // Initialize with default start
        updateTrack(track)
    }

    fun updateTrack(newTrack: TrackData) {
        val oldNoiseCfg = track.backgroundNoise
        val newNoiseCfg = newTrack.backgroundNoise
        val canReuseNoise = backgroundNoise != null && noiseConfigsCompatible(oldNoiseCfg, newNoiseCfg)
        this.track = newTrack
        
        // Update noise
        if (newNoiseCfg != null && newNoiseCfg.params != null) {
            val params = newNoiseCfg.params
            var effectiveParams = params.copy(
                start_time = newNoiseCfg.startTime.toFloat(),
                fade_in = newNoiseCfg.fadeIn.toFloat(),
                fade_out = newNoiseCfg.fadeOut.toFloat()
            )
            if (newNoiseCfg.ampEnvelope.isNotEmpty()) {
                effectiveParams = effectiveParams.copy(amp_envelope = newNoiseCfg.ampEnvelope)
            }

            val startSample = (effectiveParams.start_time * sampleRate).toInt()
            val fadeInSamples = (effectiveParams.fade_in * sampleRate).toInt()
            val fadeOutSamples = (effectiveParams.fade_out * sampleRate).toInt()
            val durationSamples = if (effectiveParams.duration_seconds > 0f) {
                (effectiveParams.duration_seconds * sampleRate).toInt()
            } else {
                null
            }
            val envPoints = effectiveParams.amp_envelope.map { pair ->
                val t = pair.getOrNull(0)?.coerceAtLeast(0f) ?: 0f
                val a = pair.getOrNull(1) ?: 1.0f
                (t * sampleRate).toInt() to a
            }
            val gain = newNoiseCfg.amp * noiseGain

            if (canReuseNoise && backgroundNoise != null) {
                backgroundNoise?.gain = gain
                val updated = backgroundNoise?.updateRealtimeParams(effectiveParams) ?: false
                if (!updated) {
                    backgroundNoise = BackgroundNoise(
                        StreamingNoise(effectiveParams, sampleRate),
                        gain,
                        startSample,
                        fadeInSamples,
                        fadeOutSamples,
                        envPoints,
                        durationSamples,
                        effectiveParams
                    )
                }
            } else {
                backgroundNoise = BackgroundNoise(
                    StreamingNoise(effectiveParams, sampleRate),
                    gain,
                    startSample,
                    fadeInSamples,
                    fadeOutSamples,
                    envPoints,
                    durationSamples,
                    effectiveParams
                )
            }
        } else {
            backgroundNoise = null
        }
        
        // Update crossfade settings
        crossfadeSamples = (newTrack.globalSettings.crossfadeDuration * sampleRate).toInt()
        val curveStr = newTrack.globalSettings.crossfadeCurve
        crossfadeCurve = if (curveStr == "equal_power") CrossfadeCurve.EqualPower else CrossfadeCurve.Linear
        
        // Track updates preserve current playback position unless explicitly seeked elsewhere.
    }
    
    fun seekTo(timeSeconds: Float) {
        val samples = (timeSeconds * sampleRate).toLong()
        absoluteSample = samples
        
        // Find current step
        var remaining = samples
        currentStepIndex = 0
        currentSample = 0 // Relative to step start
        
        for ((idx, step) in track.steps.withIndex()) {
            val stepSamples = (step.duration * sampleRate).toLong()
            if (remaining < stepSamples) {
                currentStepIndex = idx
                currentSample = remaining
                break
            }
            remaining -= stepSamples
            // If end of steps, clamp to last? Or finish.
            if (idx == track.steps.lastIndex) {
                 currentStepIndex = idx
                 currentSample = stepSamples // Finished
            }
        }
        
        activeVoices.clear()
        nextVoices.clear()
        crossfadeActive = false
        
        // Handle Noise seek
        backgroundNoise?.let { noise ->
             noise.playbackSample = 0
             noise.started = false
             val startSample = noise.startSample
             if (absoluteSample > startSample) {
                 val local = absoluteSample - startSample
                 val skip = noise.durationSamples?.let { min(local.toInt(), it) } ?: local.toInt()
                 if (skip > 0) {
                     noise.generator.skipSamples(skip)
                     noise.playbackSample = skip
                     noise.started = true
                 }
             }
        }
    }

    fun processBlock(buffer: FloatArray) {
        buffer.fill(0f)
        if (paused) return
        if (currentStepIndex >= track.steps.size) return

        val frameCount = buffer.size / 2
        
        // Initialize voices if empty
        if (activeVoices.isEmpty() && !crossfadeActive) {
            val step = track.steps[currentStepIndex]
            val voices = createVoicesForStep(step)
            applyPhasesToVoices(accumulatedPhases, voices)
            activeVoices.addAll(voices)
        }

        // Check for crossfade start
        if (!crossfadeActive && crossfadeSamples > 0 && currentStepIndex + 1 < track.steps.size) {
            val step = track.steps[currentStepIndex]
            val stepSamples = (step.duration * sampleRate).toLong()
            val fadeLen = min(crossfadeSamples.toLong(), stepSamples)
            
            if (currentSample >= stepSamples - fadeLen) {
                // Start crossfade
                val nextStep = track.steps[currentStepIndex + 1]
                if (!stepsHaveContinuousVoices(step, nextStep)) {
                    accumulatedPhases = ArrayList(extractPhasesFromVoices(activeVoices))
                    val nextVoicesList = createVoicesForStep(nextStep)
                    applyPhasesToVoices(accumulatedPhases, nextVoicesList)
                    nextVoices.clear()
                    nextVoices.addAll(nextVoicesList)

                    crossfadeActive = true
                    nextStepSample = 0
                    val nextStepSamples = (nextStep.duration * sampleRate).toLong()
                    currentCrossfadeSamples = min(fadeLen, min(stepSamples, nextStepSamples)).toInt()

                    if (currentCrossfadeSamples <= 1) {
                        crossfadeEnvelope = FloatArray(currentCrossfadeSamples)
                    } else {
                        crossfadeEnvelope = FloatArray(currentCrossfadeSamples) { i ->
                            i.toFloat() / (currentCrossfadeSamples - 1).toFloat()
                        }
                    }
                }
            }
        }

        if (crossfadeActive) {
            crossfadePrev = ensureLayout(crossfadePrev, buffer.size)
            crossfadeNext = ensureLayout(crossfadeNext, buffer.size)
            
            val prevBuf = crossfadePrev
            val nextBuf = crossfadeNext
            prevBuf.fill(0f)
            nextBuf.fill(0f)
            
            val step = track.steps[currentStepIndex]
            renderStepAudio(activeVoices, step, prevBuf)
            
            val nextStepIndexVal = min(currentStepIndex + 1, track.steps.size - 1)
            val nextStep = track.steps[nextStepIndexVal]
            renderStepAudio(nextVoices, nextStep, nextBuf)
            
            for (i in 0 until frameCount) {
                val idx = i * 2
                val progress = nextStepSample + i
                
                if (progress < currentCrossfadeSamples) {
                    val ratio = if (progress < crossfadeEnvelope.size) crossfadeEnvelope[progress] else 1.0f
                    val (gOut, gIn) = crossfadeCurve.gains(ratio)
                    buffer[idx] += prevBuf[idx] * gOut + nextBuf[idx] * gIn
                    buffer[idx+1] += prevBuf[idx+1] * gOut + nextBuf[idx+1] * gIn
                } else {
                    buffer[idx] += nextBuf[idx]
                    buffer[idx+1] += nextBuf[idx+1]
                }
            }
            
            currentSample += frameCount
            nextStepSample += frameCount
            
            activeVoices.removeAll { it.kind.isFinished() }
            nextVoices.removeAll { it.kind.isFinished() }
            
            if (nextStepSample >= currentCrossfadeSamples) {
                accumulatedPhases = ArrayList(extractPhasesFromVoices(nextVoices))
                currentStepIndex++
                currentSample = nextStepSample.toLong()
                nextStepSample = 0
                activeVoices.clear()
                activeVoices.addAll(nextVoices)
                nextVoices.clear()
                crossfadeActive = false
            }
            
        } else {
            // Normal playback
            val step = track.steps[currentStepIndex]
            renderStepAudio(activeVoices, step, buffer)
            
            activeVoices.removeAll { it.kind.isFinished() }
            currentSample += frameCount
            
            val stepSamples = (step.duration * sampleRate).toLong()
            if (currentSample >= stepSamples) {
                accumulatedPhases = ArrayList(extractPhasesFromVoices(activeVoices))
                currentStepIndex++
                currentSample = 0
                activeVoices.clear()
            }
        }
        
        if (voiceGain != 1.0f) {
            for (i in buffer.indices) {
                buffer[i] *= voiceGain
            }
        }

        if (noiseScratch.size < buffer.size) {
            noiseScratch = FloatArray(buffer.size)
        }
        backgroundNoise?.mixInto(buffer, noiseScratch, absoluteSample.toInt())

        if (masterGain != 1.0f) {
            for (i in buffer.indices) {
                buffer[i] *= masterGain
            }
        }

        absoluteSample += frameCount
    }

    private fun ensureLayout(arr: FloatArray, size: Int): FloatArray {
       if (arr.size < size) return FloatArray(size) // Realloc logic
       return arr
    }

    private fun renderStepAudio(voices: List<StepVoice>, step: StepData, out: FloatArray) {
        val len = out.size
        
        if (scratch.size < len) {
            scratch = FloatArray(len)
        }
        if (noiseScratch.size < len) {
            noiseScratch = FloatArray(len)
        }
        if (voiceTemp.size < len) {
            voiceTemp = FloatArray(len)
        }
        val binauralBuf = scratch
        val noiseBuf = noiseScratch
        binauralBuf.fill(0f, 0, len)
        noiseBuf.fill(0f, 0, len)
        
        var binauralCount = 0
        var noiseCount = 0
        var binauralPeak = 0f
        var noisePeak = 0f
        
        val temp = voiceTemp
        
        for (voice in voices) {
            temp.fill(0f, 0, len)
            voice.kind.process(temp)
            
            if (voice.voiceType == VoiceType.Noise) {
                noiseCount++
                noisePeak = max(noisePeak, voice.normalizationPeak)
                for (i in 0 until len) noiseBuf[i] += temp[i]
            } else {
                binauralCount++
                binauralPeak = max(binauralPeak, voice.normalizationPeak)
                for (i in 0 until len) binauralBuf[i] += temp[i]
            }
        }
        
        // Normalization & Gain
        applyGainStage(binauralBuf, step.normalizationLevel, step.binauralVolume * BINAURAL_MIX_SCALING, binauralCount > 0, binauralPeak, len)
        applyGainStage(noiseBuf, step.normalizationLevel, step.noiseVolume * NOISE_MIX_SCALING, noiseCount > 0, noisePeak, len)
        
        for (i in 0 until len) {
            out[i] += binauralBuf[i] + noiseBuf[i]
        }
    }
    
    private fun applyGainStage(
        buf: FloatArray, 
        normTarget: Float, 
        volume: Float, 
        hasContent: Boolean, 
        peak: Float,
        len: Int
    ) {
        if (!hasContent) {
            buf.fill(0f, 0, len)
            return
        }
        
        val normGain = if (peak > 1e-9f && normTarget > 0f) {
            min(normTarget / peak, 1.0f)
        } else {
            1.0f
        }
        val clampedVolume = volume.coerceIn(0.0f, MAX_INDIVIDUAL_GAIN)
        val totalGain = normGain * clampedVolume
        
        if (abs(totalGain - 1.0f) > 1e-6f) {
            for (i in 0 until len) {
                buf[i] *= totalGain
            }
        }
    }

    private fun createVoicesForStep(step: StepData): List<StepVoice> {
        val list = ArrayList<StepVoice>()
        for (vData in step.voices) {
            VoiceFactory.createVoice(vData, step.duration.toFloat(), sampleRate)?.let {
                list.add(it)
            }
        }
        return list
    }

    private fun extractPhasesFromVoices(voices: List<StepVoice>): List<Pair<Float, Float>> {
        return voices.mapNotNull { it.kind.getPhases() }
    }

    private fun applyPhasesToVoices(phases: List<Pair<Float, Float>>, voices: List<StepVoice>) {
        var phaseIdx = 0
        for (voice in voices) {
            if (voice.kind.getPhases() != null && phaseIdx < phases.size) {
                val (phaseL, phaseR) = phases[phaseIdx]
                voice.kind.setPhases(phaseL, phaseR)
                phaseIdx++
            }
        }
    }

    private fun noiseConfigsCompatible(
        previous: BackgroundNoiseData?,
        updated: BackgroundNoiseData?
    ): Boolean {
        if (previous == null && updated == null) return true
        if (previous == null || updated == null) return false
        if (previous.filePath != updated.filePath) return false
        if (previous.startTime != updated.startTime) return false
        if (previous.fadeIn != updated.fadeIn) return false
        if (previous.fadeOut != updated.fadeOut) return false
        if (previous.ampEnvelope.size != updated.ampEnvelope.size) return false
        for (idx in previous.ampEnvelope.indices) {
            val before = previous.ampEnvelope[idx]
            val after = updated.ampEnvelope[idx]
            if (before.size < 2 || after.size < 2) return false
            if (before[0] != after[0] || before[1] != after[1]) return false
        }
        return previous.params == updated.params
    }
    
    class BackgroundNoise(
        var generator: StreamingNoise,
        var gain: Float,
        var startSample: Int,
        var fadeInSamples: Int,
        var fadeOutSamples: Int,
        var ampEnvelope: List<Pair<Int, Float>>,
        var durationSamples: Int?,
        var params: NoiseParams
    ) {
        var started = false
        var playbackSample = 0
        
        fun mixInto(buffer: FloatArray, scratch: FloatArray, globalStartSample: Int) {
            val frames = buffer.size / 2
            if (frames == 0) return

            durationSamples?.let { if (playbackSample >= it) return }
            
            val startOffset = if (!started && globalStartSample < startSample) {
                max(0, startSample - globalStartSample)
            } else 0
            
            if (startOffset >= frames) return
            
            var usableFrames = frames - startOffset
            durationSamples?.let { usableFrames = min(usableFrames, it - playbackSample) }
            if (usableFrames <= 0) return

            val mixFrames = startOffset + usableFrames
            val requiredSamples = mixFrames * 2
            if (scratch.size < requiredSamples) return

            if (startOffset > 0) {
                scratch.fill(0f, 0, startOffset * 2)
            }
            val temp = FloatArray(usableFrames * 2)
            generator.generate(temp)
            System.arraycopy(temp, 0, scratch, startOffset * 2, temp.size)

            for (i in 0 until usableFrames) {
                val env = getEnvelopeAt(playbackSample + i) * gain
                val idx = (startOffset + i) * 2
                buffer[idx] += scratch[idx] * env
                buffer[idx + 1] += scratch[idx + 1] * env
            }

            playbackSample += usableFrames
            started = true
        }

        private fun getEnvelopeAt(sample: Int): Float {
            var amp = 1.0f
            if (fadeInSamples > 0 && sample < fadeInSamples) {
                amp *= (sample.toFloat() / fadeInSamples).coerceIn(0f, 1f)
            }
            durationSamples?.let { dur ->
                if (fadeOutSamples > 0 && sample >= dur - fadeOutSamples) {
                    val pos = sample - (dur - fadeOutSamples)
                    val denom = max(fadeOutSamples, 1).toFloat()
                    amp *= (1.0f - pos / denom).coerceIn(0f, 1f)
                }
            }

            if (ampEnvelope.isNotEmpty()) {
                var prev = ampEnvelope[0]
                for ((t, a) in ampEnvelope) {
                    if (sample < t) {
                        val span = max(t - prev.first, 1)
                        val frac = (sample - prev.first).toFloat() / span.toFloat()
                        return amp * (prev.second + (a - prev.second) * frac)
                    }
                    prev = t to a
                }
                amp *= prev.second
            }
            return amp
        }

        fun updateRealtimeParams(newParams: NoiseParams): Boolean {
            if (generator.updateRealtimeParams(newParams)) {
                params = newParams
                return true
            }
            return false
        }
    }
}
