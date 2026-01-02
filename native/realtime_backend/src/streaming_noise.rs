use crate::noise_params::NoiseParams;
use biquad::{Biquad, Coefficients, DirectForm2Transposed, ToHertz, Type, Q_BUTTERWORTH_F32};
use crossbeam::channel::{bounded, Receiver, Sender, TryRecvError};
use rand::rngs::StdRng;
use rand::SeedableRng;
use rand_distr::{Distribution, Normal};
use rustfft::{num_complex::Complex, Fft, FftPlanner};
use serde_json::Value;
use std::cmp::Ordering;
use std::sync::Arc;
use std::thread;

// --- Constants for Python-compat OLA mode ---
// Reduced from 4096 to 2048 to lower CPU load and latency on mobile devices.
// This improves real-time performance while maintaining acceptable audio quality.
const BLOCK_SIZE: usize = 2048;
const HOP_SIZE: usize = BLOCK_SIZE / 2; // 1024, 50% overlap

// --- Crossfade length for FFT noise regeneration ---
// Reduced from 4096 to 2048 to match the smaller block size.
// Still provides smooth transitions between buffers.
const CROSSFADE_SAMPLES: usize = 2048;

// --- Renormalization window for post-filter RMS tracking ---
// Increased from 4096 to reduce frequency of gain recalculations and improve
// stability for steady-state noise (now ~372ms at 44.1kHz instead of ~186ms)
// Larger window = more stable gain but slower response to filter changes.
const RENORM_WINDOW: usize = 16384;

// --- Hysteresis threshold for gain adjustments ---
// Only apply gain correction if the target differs by more than this ratio from current.
// This prevents continuous micro-adjustments from RMS variations in steady-state noise.
// Increased from 0.05 to 0.10 for more stability on mobile devices.
const RENORM_HYSTERESIS_RATIO: f32 = 0.10;

// --- Per-sample gain smoothing coefficient ---
// This coefficient determines how quickly gain changes are applied per-sample.
// A value of 0.99995 creates a very smooth transition over ~20000 samples (~453ms at 44.1kHz)
// to prevent clicking from abrupt gain changes. Higher values = smoother but slower response.
// Increased from 0.9998 for more stable volume on mobile devices.
const GAIN_SMOOTHING_COEFF: f32 = 0.99995;

// --- OLA-specific RMS compensation parameters ---
// These are tuned for the overlap-add processing which operates at block rate.

// Hysteresis threshold for OLA gain adjustments.
// Only apply gain correction if the target differs by more than this ratio.
// This prevents continuous micro-adjustments from block-to-block RMS variations
// as the swept notch filter moves, which was causing volume instability.
// More conservative than FftNoiseGenerator's 0.10 since OLA updates per-block.
const OLA_RMS_HYSTERESIS_RATIO: f32 = 0.15;

// Per-sample gain smoothing coefficient for OLA processing.
// Faster than GAIN_SMOOTHING_COEFF because OLA needs to settle within a few blocks.
// With 0.998, gain settles ~95% within ~1500 samples (~34ms at 44.1kHz).
// This allows the gain to reach target before the next block's RMS calculation.
const OLA_GAIN_SMOOTHING_COEFF: f32 = 0.998;

// --- Helper Functions ---

/// Scipy-compatible sawtooth with width=0.5 (triangle wave)
/// Python: signal.sawtooth(phase, width=0.5)
fn scipy_sawtooth_triangle(phase: f32) -> f32 {
    let t = phase.rem_euclid(2.0 * std::f32::consts::PI) / (2.0 * std::f32::consts::PI);
    let width = 0.5f32;
    if t < width {
        -1.0 + 2.0 * t / width
    } else {
        1.0 - 2.0 * (t - width) / (1.0 - width)
    }
}

fn resolved_noise_name(params: &NoiseParams) -> String {
    if let Some(Value::String(name)) = params.noise_parameters.get("name") {
        return name.clone();
    }

    "pink".to_string()
}

/// LFO value computation matching Python's behavior
/// Python "sine" uses cosine: np.cos(2 * np.pi * lfo_freq * t + phase_offset)
/// Python "triangle" uses scipy.signal.sawtooth(phase, width=0.5)
fn lfo_value(phase: f32, waveform: &str) -> f32 {
    if waveform.eq_ignore_ascii_case("triangle") {
        scipy_sawtooth_triangle(phase)
    } else {
        // "sine" in Python actually uses cosine
        crate::dsp::trig::cos_lut(phase)
    }
}

// --- Notch Filter Logic ---

#[derive(Clone)]
struct Coeffs {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
}

#[derive(Clone, Copy)]
struct BiquadState64 {
    z1: f64,
    z2: f64,
}

impl BiquadState64 {
    fn new() -> Self {
        Self { z1: 0.0, z2: 0.0 }
    }
}

/// Compute notch coefficients in f64 (matching SciPy's float64 path).
/// IMPORTANT: We keep the coefficients in f64 all the way through filtering.
/// With large cascade counts, doing this in f32 can accumulate enough numeric
/// error to cause huge peak spikes (or broad attenuation), which then makes
/// peak-based normalization collapse the perceived loudness.
fn notch_coeffs_f64(freq: f64, q: f64, sample_rate: f64) -> Coeffs {
    let w0 = 2.0 * std::f64::consts::PI * freq / sample_rate;
    let cos_w0 = w0.cos();
    let sin_w0 = w0.sin();
    let alpha = sin_w0 / (2.0 * q);

    // SciPy iirnotch (biquad form):
    // b = [1, -2cos(w0), 1]
    // a = [1+alpha, -2cos(w0), 1-alpha]
    let b0 = 1.0;
    let b1 = -2.0 * cos_w0;
    let b2 = 1.0;
    let a0 = 1.0 + alpha;
    let a1 = -2.0 * cos_w0;
    let a2 = 1.0 - alpha;

    Coeffs {
        b0: b0 / a0,
        b1: b1 / a0,
        b2: b2 / a0,
        a1: a1 / a0,
        a2: a2 / a0,
    }
}

/// Apply a biquad to a block with persistent state.
///
/// IMPORTANT: In the original implementation, we reset state to zero on every call.
/// With large cascade counts, doing this in f32 can accumulate enough numeric
/// error to cause huge peak spikes (or broad attenuation), which then makes
/// peak-based normalization collapse the perceived loudness.
fn biquad_block(block: &mut [f64], coeffs: &Coeffs, st: &mut BiquadState64) {
    // Direct Form II Transposed (biquad), in f64.
    let mut z1 = st.z1;
    let mut z2 = st.z2;

    for sample in block.iter_mut() {
        let input = *sample;
        let out = input * coeffs.b0 + z1;
        z1 = input * coeffs.b1 - out * coeffs.a1 + z2;
        z2 = input * coeffs.b2 - out * coeffs.a2;
        *sample = out;
    }

    st.z1 = z1;
    st.z2 = z2;
}

/// Apply a biquad with time-varying coefficients per sample while keeping state continuous.
fn biquad_time_varying_block(
    block: &mut [f32],
    freq_series: &[f32],
    q_series: &[f32],
    casc_counts: &[usize],
    state: &mut [BiquadState64],
    sample_rate: f64,
) {
    let n = block.len();
    let max_stage = state.len();
    for i in 0..n {
        let mut casc = casc_counts[i];
        if casc < 1 {
            casc = 1;
        } else if casc > max_stage {
            casc = max_stage;
        }

        let freq = freq_series[i] as f64;
        if !freq.is_finite() || freq <= 0.0 || freq >= sample_rate * 0.49 {
            continue;
        }
        let q = (q_series[i] as f64).max(1e-6);
        let coeffs = notch_coeffs_f64(freq, q, sample_rate);

        let mut sample = block[i] as f64;
        for stage in 0..casc {
            let st = &mut state[stage];
            let out = sample * coeffs.b0 + st.z1;
            st.z1 = sample * coeffs.b1 - out * coeffs.a1 + st.z2;
            st.z2 = sample * coeffs.b2 - out * coeffs.a2;
            sample = out;
        }
        block[i] = sample as f32;
    }
}

// --- FFT Based Noise Generator (Matches Python's ColoredNoiseGenerator) ---

struct NoiseGenRequest {
    buffer: Vec<f32>,
}

struct NoiseGenResponse {
    buffer: Vec<f32>,
    target_rms: Option<f32>,
}

struct AsyncNoiseWorker {
    rx: Receiver<NoiseGenRequest>,
    tx: Sender<NoiseGenResponse>,

    // Generation state moved from FftNoiseGenerator
    size: usize,
    exponent: f32,
    high_exponent: f32,
    distribution_curve: f32,
    sample_rate: f32,
    fft_forward: Arc<dyn Fft<f32>>,
    fft_inverse: Arc<dyn Fft<f32>>,
    rng: StdRng,
    normal: Normal<f32>,

    // Scratch buffer for FFT
    fft_scratch: Vec<Complex<f32>>,

    // Persist target_rms here to support the locking strategy
    target_rms: Option<f32>,
}

impl AsyncNoiseWorker {
    fn run(mut self) {
        // Wait for requests
        while let Ok(mut req) = self.rx.recv() {
            // Wrap regeneration in panic handler to prevent worker thread crashes
            // from killing the entire audio pipeline
            if let Err(e) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                self.regenerate_into(&mut req.buffer);
            })) {
                log::error!("FFT worker panic: {:?}", e);
                // Fill buffer with zeros to avoid garbage audio
                req.buffer.fill(0.0);
            }
            let _ = self.tx.send(NoiseGenResponse {
                buffer: req.buffer,
                target_rms: self.target_rms,
            });
        }
        log::error!("FFT worker channel closed - thread exiting");
    }

    fn regenerate_into(&mut self, target: &mut Vec<f32>) {
        // Ensure target buffer is correctly sized
        if target.len() != self.size {
            target.resize(self.size, 0.0);
        }

        // Fill scratch buffer with white noise (reuse pre-allocated buffer)
        for i in 0..self.size {
            self.fft_scratch[i] = Complex::new(self.normal.sample(&mut self.rng), 0.0);
        }

        self.fft_forward.process(&mut self.fft_scratch);

        let nyquist = self.sample_rate / 2.0;
        let min_f = self.sample_rate / (self.size as f32);

        if !self.fft_scratch.is_empty() {
            self.fft_scratch[0] = Complex::new(0.0, 0.0);
        }

        for i in 1..=self.size / 2 {
            let freq = i as f32 * self.sample_rate / self.size as f32;
            if freq <= 0.0 {
                continue;
            }

            let log_min = min_f.ln();
            let log_max = nyquist.ln();
            let log_f = freq.ln();

            let denom = (log_max - log_min).max(1e-12);
            let mut log_norm = (log_f - log_min) / denom;
            log_norm = log_norm.clamp(0.0, 1.0);

            let interp = log_norm.powf(self.distribution_curve);
            let current_exp = self.exponent + (self.high_exponent - self.exponent) * interp;

            let scale = freq.powf(-current_exp / 2.0);

            self.fft_scratch[i] *= scale;
            if i < self.size / 2 {
                self.fft_scratch[self.size - i] = self.fft_scratch[i].conj();
            }
        }

        self.fft_inverse.process(&mut self.fft_scratch);

        // Copy FFT result to target buffer
        let size_f = self.size as f32;
        for i in 0..self.size {
            target[i] = self.fft_scratch[i].re / size_f;
        }

        // Apply RMS locking logic (same as original)
        let mut sum_sq = 0.0;
        for x in target.iter() {
            sum_sq += x * x;
        }
        let current_rms = (sum_sq / target.len() as f32).sqrt();

        if current_rms > 1e-9 {
            if let Some(target_rms) = self.target_rms {
                let gain = target_rms / current_rms;
                for x in target.iter_mut() {
                    *x = (*x * gain).clamp(-1.0, 1.0);
                }
            } else {
                // First buffer case: Peak Norm + Set Target
                let max_val = target.iter().fold(0.0f32, |acc, &v| acc.max(v.abs()));
                if max_val > 1e-9 {
                    for x in target.iter_mut() {
                        *x /= max_val;
                    }
                    let mut sum_sq_norm = 0.0;
                    for x in target.iter() {
                        sum_sq_norm += x * x;
                    }
                    let final_rms = (sum_sq_norm / target.len() as f32).sqrt();
                    self.target_rms = Some(final_rms);
                }
            }
        }
    }
}

// --- Underrun recovery fade length ---
// Number of samples to fade in/out when recovering from an underrun.
// This prevents clicks when looping the buffer due to async worker being too slow.
const UNDERRUN_FADE_SAMPLES: usize = 512;

struct FftNoiseGenerator {
    buffer: Vec<f32>,
    // Pre-allocated storage for the next buffer (used during crossfade)
    next_buffer_storage: Vec<f32>,
    // Flag indicating if next_buffer_storage contains valid data ready for crossfade
    next_buffer_ready: bool,
    cursor: usize,
    size: usize,

    // Worker handles
    worker_tx: Sender<NoiseGenRequest>,
    worker_rx: Receiver<NoiseGenResponse>,
    worker_requested: bool,

    // Post-processing filters (keep in audio thread as they are lightweight IIR)
    lp_filters: Option<Vec<DirectForm2Transposed<f32>>>,
    hp_filters: Option<Vec<DirectForm2Transposed<f32>>>,
    base_amplitude: f32,

    // Renorm state
    renorm_gain: f32,
    smoothed_gain: f32,
    renorm_initialized: bool,
    pre_rms_accum: f32,
    post_rms_accum: f32,
    rms_samples: usize,
    is_unmodulated: bool,

    // Underrun recovery state
    underrun_recovering: bool,
    underrun_fade_pos: usize,
}

impl FftNoiseGenerator {
    fn preset_for_type(nt: &str) -> Option<(f32, f32, f32, Option<f32>, Option<f32>, f32)> {
        match nt {
            "pink" => Some((1.0, 1.0, 1.0, None, None, 1.0)),
            "brown" => Some((2.0, 2.0, 1.0, None, None, 1.0)),
            "red" => Some((2.0, 1.5, 1.0, None, None, 1.0)),
            "green" => Some((0.0, 0.0, 1.0, Some(100.0), Some(8000.0), 1.0)),
            "blue" => Some((-1.0, -1.0, 1.0, None, None, 1.0)),
            "purple" => Some((-2.0, -2.0, 1.0, None, None, 1.0)),
            "deep brown" => Some((2.5, 2.0, 1.0, None, None, 1.0)),
            "white" => Some((0.0, 0.0, 1.0, None, None, 1.0)),
            _ => None,
        }
    }

    fn new(params: &NoiseParams, sample_rate: f32) -> Self {
        let noise_label = resolved_noise_name(params);
        let nt = noise_label.to_lowercase();
        let preset = Self::preset_for_type(nt.as_str());

        let exponent = params
            .exponent
            .or_else(|| preset.map(|p| p.0))
            .unwrap_or(0.0);
        let high_exponent = params
            .high_exponent
            .or_else(|| preset.map(|p| p.1))
            .unwrap_or(exponent);
        let distribution_curve = params
            .distribution_curve
            .or_else(|| preset.map(|p| p.2))
            .unwrap_or(1.0)
            .max(1e-6);
        let lowcut = params.lowcut.or_else(|| preset.and_then(|p| p.3));
        let highcut = params.highcut.or_else(|| preset.and_then(|p| p.4));
        let amplitude = params
            .amplitude
            .or_else(|| preset.map(|p| p.5))
            .unwrap_or(1.0);
        let seed = params.seed.unwrap_or(1).max(0) as u64;

        // Limit the FFT buffer size to ~0.74s max to prevent CPU stalls on mobile devices.
        // Smaller buffers mean faster generation times, reducing the chance of underruns.
        // This ensures "chunked streaming" behavior where we regenerate new noise blocks repeatedly.
        let requested = (params.duration_seconds.max(0.0) * sample_rate) as usize;
        let default_size = 1 << 15; // ~0.74s at 44.1k (reduced from 1<<17 for mobile performance)
                                    // Use requested size ONLY if it's smaller than default (e.g. for very short precise clips)
                                    // Otherwise use default chunk size.
        let mut size = if requested > 0 && requested < default_size {
            requested
        } else {
            default_size
        };
        if size < 8 {
            size = 8;
        }
        if size % 2 != 0 {
            size += 1;
        }

        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(size);
        let fft_inverse = planner.plan_fft_inverse(size);

        let rng = StdRng::seed_from_u64(seed);
        let normal = Normal::new(0.0, 1.0).unwrap();

        let nyquist = sample_rate / 2.0;

        let mut lp_filters = None;
        if let Some(fc) = lowcut {
            if fc > 0.0 && fc < nyquist {
                if let Some(c) = Coefficients::<f32>::from_params(
                    Type::HighPass,
                    sample_rate.hz(),
                    fc.hz(),
                    Q_BUTTERWORTH_F32,
                )
                .ok()
                {
                    lp_filters = Some(vec![DirectForm2Transposed::<f32>::new(c); 2]);
                }
            }
        }

        let mut hp_filters = None;
        if let Some(fc) = highcut {
            if fc > 0.0 && fc < nyquist {
                if let Some(c) = Coefficients::<f32>::from_params(
                    Type::LowPass,
                    sample_rate.hz(),
                    fc.hz(),
                    Q_BUTTERWORTH_F32,
                )
                .ok()
                {
                    hp_filters = Some(vec![DirectForm2Transposed::<f32>::new(c); 2]);
                }
            }
        }

        // Spawn Worker with capacity 2 for double-buffering
        // This allows one buffer to be in-flight while another is ready,
        // providing more headroom for CPU scheduling variability on mobile devices.
        let (req_tx, req_rx) = bounded::<NoiseGenRequest>(2);
        let (res_tx, res_rx) = bounded::<NoiseGenResponse>(2);

        let worker = AsyncNoiseWorker {
            rx: req_rx,
            tx: res_tx,
            size,
            exponent,
            high_exponent,
            distribution_curve,
            sample_rate,
            fft_forward,
            fft_inverse,
            rng,
            normal,
            fft_scratch: vec![Complex::new(0.0, 0.0); size],
            target_rms: None,
        };

        thread::spawn(move || worker.run());

        // We need an initial buffer to play immediately.
        // We'll generate it synchronously locally *once* before starting (or block waiting for worker).
        // Since we are in the constructor (likely UI thread or loader), blocking briefly is better than
        // silence. But we don't have the worker's code here anymore!
        // TRICK: We can send a request to the worker and block-wait for the response right here!

        let initial_buffer = vec![0.0; size];
        let _ = req_tx.send(NoiseGenRequest {
            buffer: initial_buffer,
        });

        // Block wait for initial buffer
        let initial_res = res_rx.recv().expect("Worker died immediately");

        // Request and wait for second buffer to ensure pipeline is primed.
        // This prevents underruns during initial playback on slow mobile devices.
        let second_buffer = vec![0.0; size];
        let _ = req_tx.send(NoiseGenRequest { buffer: second_buffer });
        let second_res = res_rx.recv().expect("Worker died on second buffer");

        let mut gen = Self {
            buffer: initial_res.buffer,
            // Pre-fill next buffer storage with the second buffer for immediate availability
            next_buffer_storage: second_res.buffer,
            next_buffer_ready: true,  // Mark as ready since we have a valid second buffer
            cursor: 0,
            size,
            worker_tx: req_tx,
            worker_rx: res_rx,
            worker_requested: false,

            lp_filters,
            hp_filters,
            base_amplitude: amplitude,

            renorm_gain: 1.0,
            smoothed_gain: 1.0,
            renorm_initialized: false,
            pre_rms_accum: 0.0,
            post_rms_accum: 0.0,
            rms_samples: 0,
            // IMPORTANT: FftNoiseGenerator's LP/HP filters are always static.
            // The swept notch filters are handled by OLA processing, which has its
            // own gain compensation. Setting is_unmodulated=true ensures the base
            // noise generator uses static calibration, preventing two independent
            // RMS tracking systems from fighting each other and causing volume
            // instability.
            is_unmodulated: true,

            // Underrun recovery state
            underrun_recovering: false,
            underrun_fade_pos: 0,
        };

        // Note: Pipeline is now pre-filled with two buffers (buffer + next_buffer_storage)
        // so we don't need additional prewarm. The 50% early trigger in next() will
        // request the third buffer with plenty of time before it's needed.

        gen
    }

    fn crossfade_len(&self) -> usize {
        self.buffer.len().min(CROSSFADE_SAMPLES)
    }

    fn next(&mut self) -> f32 {
        let crossfade_len = self.crossfade_len();

        // Trigger async regeneration at 50% point (giving 2x the buffer time for slow workers)
        // Earlier trigger provides more headroom on mobile devices with variable CPU scheduling.
        // Changed from 5% to 50% to prevent underruns on slower mobile devices.
        if !self.next_buffer_ready && !self.worker_requested {
            let early_trigger = self.size / 2;  // Trigger at 50% instead of near the end
            if self.cursor >= early_trigger {
                // Swap out the old next buffer to send to worker for recycling
                let mut buffer_to_recycle = std::mem::take(&mut self.next_buffer_storage);
                // Ensure it's sized correctly (though it should be)
                if buffer_to_recycle.len() != self.size {
                    buffer_to_recycle.resize(self.size, 0.0);
                }

                if let Ok(_) = self.worker_tx.try_send(NoiseGenRequest {
                    buffer: buffer_to_recycle,
                }) {
                    self.worker_requested = true;
                } else {
                    // Log potentially full channel
                    // self.next_buffer_storage = vec![0.0; self.size];
                }
            }
        }

        // Check for response if we requested
        if self.worker_requested {
            match self.worker_rx.try_recv() {
                Ok(response) => {
                    self.next_buffer_storage = response.buffer;
                    self.next_buffer_ready = true;
                    self.worker_requested = false;
                }
                Err(TryRecvError::Empty) => {
                    // Still waiting
                }
                Err(TryRecvError::Disconnected) => {
                    self.worker_requested = false;
                }
            }
        }

        // --- Buffer Switching Logic ---
        if self.cursor >= self.buffer.len() {
            // If next buffer ready, swap
            if self.next_buffer_ready {
                let consumed_from_next = crossfade_len;
                let skip = consumed_from_next.min(self.next_buffer_storage.len());
                std::mem::swap(&mut self.buffer, &mut self.next_buffer_storage);
                self.cursor = skip;
                self.next_buffer_ready = false;
                // Clear underrun recovery state since we have a fresh buffer
                self.underrun_recovering = false;
                self.underrun_fade_pos = 0;
                // recycle old buffer next time
            } else {
                // UNDERRUN! The worker was too slow.
                // Loop the buffer with smooth fade to avoid clicks.
                self.cursor = 0;
                self.underrun_recovering = true;
                self.underrun_fade_pos = 0;
            }
        }

        let mut sample = if self.next_buffer_ready {
            let crossfade_start = self.buffer.len().saturating_sub(crossfade_len);
            if self.cursor >= crossfade_start
                && crossfade_len > 0
                && !self.next_buffer_storage.is_empty()
            {
                let idx = self.cursor - crossfade_start;
                let t = idx as f32 / crossfade_len as f32;
                let fade_out = 0.5 * (1.0 + (std::f32::consts::PI * t).cos());
                let fade_in = 1.0 - fade_out;
                let next_sample = self.next_buffer_storage.get(idx).copied().unwrap_or(0.0);
                self.buffer[self.cursor] * fade_out + next_sample * fade_in
            } else {
                self.buffer[self.cursor]
            }
        } else {
            self.buffer[self.cursor]
        };

        // Apply underrun recovery fade-in to prevent clicks after buffer loop
        if self.underrun_recovering {
            if self.underrun_fade_pos < UNDERRUN_FADE_SAMPLES {
                let pos = self.underrun_fade_pos;

                let t = pos as f32 / UNDERRUN_FADE_SAMPLES as f32;
                let fade_in = 0.5 * (1.0 - (std::f32::consts::PI * t).cos());
                let fade_out = 1.0 - fade_in;

                // tail sample from the previous end of buffer
                let tail_base = self.buffer.len().saturating_sub(UNDERRUN_FADE_SAMPLES);
                let tail_idx = (tail_base + pos).min(self.buffer.len().saturating_sub(1));
                let tail_sample = self.buffer[tail_idx];

                // `sample` is the head sample (current cursor position)
                sample = tail_sample * fade_out + sample * fade_in;

                self.underrun_fade_pos += 1;
            } else {
                self.underrun_recovering = false;
                self.underrun_fade_pos = 0;
            }
        }

        self.cursor += 1;

        // Post-processing
        let pre_filter_sample = sample;

        if let Some(ref mut filters) = self.lp_filters {
            for f in filters {
                sample = f.run(sample);
            }
        }
        if let Some(ref mut filters) = self.hp_filters {
            for f in filters {
                sample = f.run(sample);
            }
        }

        sample = self.apply_post_filter_renorm(pre_filter_sample, sample);

        sample * self.base_amplitude
    }

    fn apply_post_filter_renorm(&mut self, pre: f32, post: f32) -> f32 {
        self.pre_rms_accum += pre * pre;
        self.post_rms_accum += post * post;
        self.rms_samples += 1;

        if self.rms_samples >= RENORM_WINDOW {
            let pre_rms = (self.pre_rms_accum / self.rms_samples as f32).sqrt();
            let post_rms = (self.post_rms_accum / self.rms_samples as f32).sqrt();

            if pre_rms > 1e-6 && post_rms > 1e-6 {
                let target_gain = (pre_rms / post_rms).clamp(0.25, 16.0);

                if self.is_unmodulated {
                    // STATIC CALIBRATION FOR UNMODULATED NOISE
                    // Once we calculate the correct makeup gain (during warmup), we LOCK it.
                    // This provides the correct volume boost without any "pumping" or instability.
                    if !self.renorm_initialized {
                        self.renorm_gain = target_gain;
                        self.smoothed_gain = target_gain;
                        self.renorm_initialized = true;
                    }
                    // If already initialized, DO NOT CHANGE IT.
                } else {
                    // DYNAMIC TRACKING FOR SWEPT NOISE
                    // For sweeps, the filter response changes over time, so we must track it.

                    // Apply hysteresis to avoid micro-jitters
                    let ratio_diff = (target_gain - self.renorm_gain).abs() / self.renorm_gain;
                    if ratio_diff > RENORM_HYSTERESIS_RATIO {
                        if !self.renorm_initialized {
                            self.renorm_gain = target_gain;
                            self.smoothed_gain = target_gain;
                            self.renorm_initialized = true;
                        } else {
                            // Blend toward the target
                            self.renorm_gain = 0.8 * self.renorm_gain + 0.2 * target_gain;
                        }
                    }
                }
            } else {
                // Fallback if signal is too quiet (shouldn't happen with RMS locking)
                if !self.renorm_initialized {
                    self.renorm_gain = 1.0;
                    self.smoothed_gain = 1.0;
                    self.renorm_initialized = true;
                }
            }

            self.pre_rms_accum = 0.0;
            self.post_rms_accum = 0.0;
            self.rms_samples = 0;
        }

        // Apply per-sample gain smoothing
        self.smoothed_gain = GAIN_SMOOTHING_COEFF * self.smoothed_gain
            + (1.0 - GAIN_SMOOTHING_COEFF) * self.renorm_gain;

        post * self.smoothed_gain
    }
}

// --- Precomputed Hann window (matching np.hanning) ---

fn hann_window(size: usize) -> Vec<f32> {
    // np.hanning(N) = 0.5 - 0.5 * cos(2*pi*n/(N-1)), n = 0..N-1
    (0..size)
        .map(|n| 0.5 - 0.5 * (2.0 * std::f32::consts::PI * n as f32 / (size as f32 - 1.0)).cos())
        .collect()
}

// --- OLA (Overlap-Add) State for Python-compat streaming ---

struct OlaState {
    // Ring buffer for input samples (mono base noise)
    input_ring: Vec<f32>,
    input_write_pos: usize,
    input_samples_buffered: usize,

    // Overlap-add accumulators for each channel (ring buffer style)
    out_acc_l: Vec<f32>,
    out_acc_r: Vec<f32>,
    win_acc: Vec<f32>,

    // Position tracking within the accumulator ring
    acc_read_pos: usize,
    acc_write_pos: usize,

    // Samples ready to output
    samples_ready: usize,

    // Absolute sample index for time tracking (block start positions)
    absolute_block_start: usize,

    // Precomputed Hann window
    window: Vec<f32>,

    // Scratch buffers for block processing
    block_l: Vec<f32>,
    block_r: Vec<f32>,

    // Smoothed RMS compensation gains for each channel (prevents clicking)
    smoothed_gain_l: f32,
    smoothed_gain_r: f32,

    // Pre-allocated buffers for process_ola_block() to avoid allocations in audio callback
    t_vals: Vec<f32>,
    lfo_main_l: Vec<f32>,
    lfo_main_r: Vec<f32>,
    lfo_extra_l: Vec<f32>,
    lfo_extra_r: Vec<f32>,
    min_series: Vec<f32>,
    max_series: Vec<f32>,
    q_series: Vec<f32>,
    casc_series: Vec<usize>,
    notch_freq_l: Vec<f32>,
    notch_freq_r: Vec<f32>,
    notch_freq_l_extra: Vec<f32>,
    notch_freq_r_extra: Vec<f32>,
    casc_series_clamped: Vec<usize>,
}

impl OlaState {
    fn new() -> Self {
        let window = hann_window(BLOCK_SIZE);
        let acc_size = BLOCK_SIZE * 2;

        Self {
            input_ring: vec![0.0; BLOCK_SIZE],
            input_write_pos: 0,
            input_samples_buffered: 0,
            out_acc_l: vec![0.0; acc_size],
            out_acc_r: vec![0.0; acc_size],
            win_acc: vec![0.0; acc_size],
            acc_read_pos: 0,
            acc_write_pos: 0,
            samples_ready: 0,
            absolute_block_start: 0,
            window,
            block_l: vec![0.0; BLOCK_SIZE],
            block_r: vec![0.0; BLOCK_SIZE],
            smoothed_gain_l: 1.0,
            smoothed_gain_r: 1.0,
            // Pre-allocate all buffers used in process_ola_block() to avoid
            // allocations in the real-time audio callback
            t_vals: vec![0.0; BLOCK_SIZE],
            lfo_main_l: vec![0.0; BLOCK_SIZE],
            lfo_main_r: vec![0.0; BLOCK_SIZE],
            lfo_extra_l: vec![0.0; BLOCK_SIZE],
            lfo_extra_r: vec![0.0; BLOCK_SIZE],
            min_series: vec![0.0; BLOCK_SIZE],
            max_series: vec![0.0; BLOCK_SIZE],
            q_series: vec![0.0; BLOCK_SIZE],
            casc_series: vec![0; BLOCK_SIZE],
            notch_freq_l: vec![0.0; BLOCK_SIZE],
            notch_freq_r: vec![0.0; BLOCK_SIZE],
            notch_freq_l_extra: vec![0.0; BLOCK_SIZE],
            notch_freq_r_extra: vec![0.0; BLOCK_SIZE],
            casc_series_clamped: vec![0; BLOCK_SIZE],
        }
    }
}

// --- Sweep parameters for varying mode ---

#[derive(Clone)]
struct SweepParams {
    start_min: f32,
    end_min: f32,
    start_max: f32,
    end_max: f32,
    start_q: f32,
    end_q: f32,
    start_casc: usize,
    end_casc: usize,
}

#[derive(Clone)]
struct SweepRuntime {
    max_casc: usize,
    // Each cascade stage must preserve its own state across blocks, like a true
    // series of biquads applied to a continuous signal.
    l_main: Vec<BiquadState64>,
    r_main: Vec<BiquadState64>,
    l_extra: Vec<BiquadState64>,
    r_extra: Vec<BiquadState64>,
}

impl SweepRuntime {
    fn new(max_casc: usize) -> Self {
        let max_casc = max_casc.max(1);
        Self {
            max_casc,
            l_main: vec![BiquadState64::new(); max_casc],
            r_main: vec![BiquadState64::new(); max_casc],
            l_extra: vec![BiquadState64::new(); max_casc],
            r_extra: vec![BiquadState64::new(); max_casc],
        }
    }
}

impl SweepParams {
    fn interpolate_at(&self, t: f32) -> (f32, f32, f32, usize) {
        let t = t.clamp(0.0, 1.0);
        let min_freq = self.start_min + (self.end_min - self.start_min) * t;
        let max_freq = self.start_max + (self.end_max - self.start_max) * t;
        let q = self.start_q + (self.end_q - self.start_q) * t;
        let casc_f = self.start_casc as f32 + (self.end_casc as f32 - self.start_casc as f32) * t;
        let casc = casc_f.round().max(1.0) as usize;
        (min_freq, max_freq, q, casc)
    }
}

pub struct StreamingNoise {
    sample_rate: f32,
    duration_samples: usize,

    // LFO parameters
    start_lfo_freq: f32,
    end_lfo_freq: f32,
    lfo_freq: f32,
    start_lfo_phase_offset: f32,
    end_lfo_phase_offset: f32,
    start_intra_offset: f32,
    end_intra_offset: f32,
    lfo_waveform: String,
    initial_offset: f32,

    // Sweep parameters (for varying mode)
    sweep_params: Vec<SweepParams>,

    // Persistent biquad states per sweep (per channel + per pass + per cascade stage)
    sweep_runtime: Vec<SweepRuntime>,

    // Mode flags
    transition: bool,

    // FFT Generator for all noise modes
    fft_gen: FftNoiseGenerator,

    // OLA state for Python-compat mode
    ola: OlaState,

    // Total samples output so far (for absolute time tracking)
    total_samples_output: usize,
}

impl StreamingNoise {
    fn build_sweep_params(params: &NoiseParams) -> Vec<SweepParams> {
        params
            .sweeps
            .iter()
            .map(|sw| {
                let start_min = if sw.start_min > 0.0 {
                    sw.start_min
                } else {
                    1000.0
                };
                let end_min = if sw.end_min > 0.0 {
                    sw.end_min
                } else {
                    start_min
                };
                let start_max = if sw.start_max > 0.0 {
                    sw.start_max.max(start_min + 1.0)
                } else {
                    start_min + 9000.0
                };
                let end_max = if sw.end_max > 0.0 {
                    sw.end_max.max(end_min + 1.0)
                } else {
                    start_max
                };
                let start_q = if sw.start_q > 0.0 { sw.start_q } else { 25.0 };
                let end_q = if sw.end_q > 0.0 { sw.end_q } else { start_q };
                let start_casc = if sw.start_casc > 0 { sw.start_casc } else { 10 };
                let end_casc = if sw.end_casc > 0 {
                    sw.end_casc
                } else {
                    start_casc
                };
                SweepParams {
                    start_min,
                    end_min,
                    start_max,
                    end_max,
                    start_q,
                    end_q,
                    start_casc,
                    end_casc,
                }
            })
            .collect()
    }

    pub fn new(params: &NoiseParams, sample_rate: u32) -> Self {
        let sample_rate_f = sample_rate as f32;
        let duration_samples = (params.duration_seconds * sample_rate_f) as usize;

        let lfo_freq = if params.transition {
            params.start_lfo_freq
        } else if params.lfo_freq != 0.0 {
            params.lfo_freq
        } else {
            1.0 / 12.0
        };

        let sweep_params = Self::build_sweep_params(params);

        let sweep_runtime: Vec<SweepRuntime> = sweep_params
            .iter()
            .map(|sp| {
                let max_casc = sp.start_casc.max(sp.end_casc).max(1);
                SweepRuntime::new(max_casc)
            })
            .collect();

        let mut gen = Self {
            sample_rate: sample_rate_f,
            duration_samples,
            start_lfo_freq: if params.start_lfo_freq > 0.0 {
                params.start_lfo_freq
            } else {
                lfo_freq
            },
            end_lfo_freq: if params.end_lfo_freq > 0.0 {
                params.end_lfo_freq
            } else {
                lfo_freq
            },
            lfo_freq,
            start_lfo_phase_offset: params.start_lfo_phase_offset_deg.to_radians(),
            end_lfo_phase_offset: params.end_lfo_phase_offset_deg.to_radians(),
            start_intra_offset: params.start_intra_phase_offset_deg.to_radians(),
            end_intra_offset: params.end_intra_phase_offset_deg.to_radians(),
            lfo_waveform: params.lfo_waveform.clone(),
            initial_offset: params.initial_offset,
            sweep_params,
            sweep_runtime,
            transition: params.transition,
            fft_gen: FftNoiseGenerator::new(params, sample_rate_f),
            ola: OlaState::new(),
            total_samples_output: 0,
        };

        // --- WARMUP / CALIBRATION LOOP ---
        // For unmodulated noise, we rely on the first renormalization calculation
        // to set the static makeup gain. We need to run enough samples through
        // the generator here so that it has "latched" onto the correct gain
        // *before* we start outputting real audio. This prevents a "quiet start"
        // or fade-in artifact.
        if params.sweeps.is_empty() {
            // Run exactly one window's worth of samples to trigger the first calc
            // RENORM_WINDOW is currently 8192
            for _ in 0..RENORM_WINDOW {
                // discard output, just warming up state
                gen.fft_gen.next();
            }
            // Reset state that shouldn't persist (optional, but good practice)
            // Actually, we WANT to keep the renorm_gain, so we don't reset that.
            // But we might want to reset the cursor or buffer if we wanted to align things,
            // but for noise it doesn't matter.
        }

        gen
    }

    pub fn update_realtime_params(&mut self, params: &NoiseParams) -> bool {
        if params.sweeps.len() != self.sweep_params.len() {
            return false;
        }

        let lfo_freq = if params.transition {
            params.start_lfo_freq
        } else if params.lfo_freq != 0.0 {
            params.lfo_freq
        } else {
            1.0 / 12.0
        };

        let sweep_params = Self::build_sweep_params(params);
        for (rt, sp) in self.sweep_runtime.iter_mut().zip(&sweep_params) {
            let max_casc = sp.start_casc.max(sp.end_casc).max(1);
            if max_casc > rt.max_casc {
                return false;
            }
            rt.max_casc = max_casc;
        }

        self.sweep_params = sweep_params;
        self.transition = params.transition;
        self.lfo_waveform = params.lfo_waveform.clone();
        self.start_lfo_freq = if params.start_lfo_freq > 0.0 {
            params.start_lfo_freq
        } else {
            lfo_freq
        };
        self.end_lfo_freq = if params.end_lfo_freq > 0.0 {
            params.end_lfo_freq
        } else {
            lfo_freq
        };
        self.lfo_freq = lfo_freq;
        self.start_lfo_phase_offset = params.start_lfo_phase_offset_deg.to_radians();
        self.end_lfo_phase_offset = params.end_lfo_phase_offset_deg.to_radians();
        self.start_intra_offset = params.start_intra_phase_offset_deg.to_radians();
        self.end_intra_offset = params.end_intra_phase_offset_deg.to_radians();
        true
    }

    pub fn new_with_calibrated_peak(
        params: &NoiseParams,
        sample_rate: u32,
        calibration_frames: usize,
    ) -> (Self, f32) {
        let frames = calibration_frames.max(1);

        let mut calibration_gen = StreamingNoise::new(params, sample_rate);
        let mut scratch = vec![0.0f32; frames * 2];
        calibration_gen.generate(&mut scratch);

        // IMPORTANT: Using absolute max is extremely fragile for streaming.
        // Deep/high-Q cascades can create rare block-edge spikes (especially with brown noise)
        // that make `peak` enormous. Peak-normalization then makes everything sound *silent*.
        // We use a robust peak estimate (99.9th percentile of |x|) to avoid one-sample poison.
        let mut abs_vals: Vec<f32> = scratch.iter().map(|v| v.abs()).collect();
        abs_vals.sort_by(|a, b| a.partial_cmp(b).unwrap_or(Ordering::Equal));
        let idx = ((abs_vals.len() as f64) * 0.999).floor() as usize;
        let idx = idx.min(abs_vals.len().saturating_sub(1));
        let peak = abs_vals.get(idx).copied().unwrap_or(0.0).max(1e-9);

        let generator = StreamingNoise::new(params, sample_rate);

        (generator, peak)
    }

    pub fn skip_samples(&mut self, n: usize) {
        let mut scratch = vec![0.0f32; n * 2];
        self.generate(&mut scratch);
    }

    fn next_base(&mut self) -> f32 {
        self.fft_gen.next()
    }

    /// Compute the transition fraction at a given absolute sample index
    fn transition_fraction(&self, sample_idx: usize) -> f32 {
        if !self.transition || self.duration_samples == 0 {
            return 0.0;
        }
        (sample_idx as f32 / self.duration_samples as f32).clamp(0.0, 1.0)
    }

    /// Interpolate LFO frequency at a given transition fraction
    fn interpolate_lfo_freq(&self, t: f32) -> f32 {
        if !self.transition {
            return self.lfo_freq;
        }
        self.start_lfo_freq + (self.end_lfo_freq - self.start_lfo_freq) * t
    }

    /// Interpolate phase offset at a given transition fraction
    fn interpolate_phase_offset(&self, t: f32) -> f32 {
        if !self.transition {
            return self.start_lfo_phase_offset;
        }
        self.start_lfo_phase_offset + (self.end_lfo_phase_offset - self.start_lfo_phase_offset) * t
    }

    /// Interpolate intra offset at a given transition fraction
    fn interpolate_intra_offset(&self, t: f32) -> f32 {
        if !self.transition {
            return self.start_intra_offset;
        }
        self.start_intra_offset + (self.end_intra_offset - self.start_intra_offset) * t
    }

    /// Compute LFO phase at a given sample index using Python's approach:
    /// t = sample_idx / sample_rate + initial_offset
    /// phase = 2 * pi * lfo_freq * t + phase_offset
    fn compute_lfo_phase(&self, sample_idx: usize, lfo_freq: f32, extra_phase_offset: f32) -> f32 {
        let t = sample_idx as f32 / self.sample_rate + self.initial_offset;
        2.0 * std::f32::consts::PI * lfo_freq * t + extra_phase_offset
    }

    /// Process a single block using overlap-add approach (Python-compat mode)
    /// IMPORTANT: This function uses pre-allocated buffers to avoid heap allocations
    /// in the real-time audio callback, which would cause audio stuttering/catching.
    fn process_ola_block(&mut self) {
        let acc_size = self.ola.out_acc_l.len();
        let block_start_idx = self.ola.absolute_block_start;

        // Use pre-allocated buffers instead of allocating new vectors each call.
        // This is critical for real-time audio - allocations cause stuttering.
        let do_extra = self.start_intra_offset.abs() > 1e-6 || self.end_intra_offset.abs() > 1e-6;

        for i in 0..BLOCK_SIZE {
            let abs_idx = block_start_idx + i;
            let t = self.transition_fraction(abs_idx);
            self.ola.t_vals[i] = t;

            let lfo_freq = self.interpolate_lfo_freq(t);
            let phase_offset = self.interpolate_phase_offset(t);
            let intra_offset = self.interpolate_intra_offset(t);

            let l_phase = self.compute_lfo_phase(abs_idx, lfo_freq, 0.0);
            let r_phase = self.compute_lfo_phase(abs_idx, lfo_freq, phase_offset);
            self.ola.lfo_main_l[i] = lfo_value(l_phase, &self.lfo_waveform);
            self.ola.lfo_main_r[i] = lfo_value(r_phase, &self.lfo_waveform);
            if do_extra {
                self.ola.lfo_extra_l[i] = lfo_value(l_phase + intra_offset, &self.lfo_waveform);
                self.ola.lfo_extra_r[i] = lfo_value(r_phase + intra_offset, &self.lfo_waveform);
            }
        }

        // Copy input block from ring buffer WITHOUT windowing.
        // The window is applied AFTER filtering to avoid IIR filter state discontinuities.
        // Also compute RMS of the unwindowed input for later compensation.
        let mut sum_sq_in: f32 = 0.0;
        for i in 0..BLOCK_SIZE {
            let ring_idx =
                (self.ola.input_write_pos + BLOCK_SIZE - self.ola.input_samples_buffered + i)
                    % BLOCK_SIZE;
            let base = self.ola.input_ring[ring_idx];
            self.ola.block_l[i] = base;
            self.ola.block_r[i] = base;
            sum_sq_in += base * base;
        }
        let rms_in = (sum_sq_in / BLOCK_SIZE as f32).sqrt();

        // Apply notch filters for each sweep using smoothly changing coefficients.
        // We keep per-stage filter state across blocks and vary coefficients per-sample
        // to avoid block-edge clicks when parameters move quickly.
        // NOTE: Using pre-allocated buffers in self.ola to avoid allocations.

        for (si, sp) in self.sweep_params.iter().enumerate() {
            let rt = &mut self.sweep_runtime[si];
            for i in 0..BLOCK_SIZE {
                let t = self.ola.t_vals[i];
                let min_f = sp.start_min + (sp.end_min - sp.start_min) * t;
                let max_f = sp.start_max + (sp.end_max - sp.start_max) * t;
                let q = sp.start_q + (sp.end_q - sp.start_q) * t;
                let casc_f = sp.start_casc as f32 + (sp.end_casc as f32 - sp.start_casc as f32) * t;
                self.ola.min_series[i] = min_f;
                self.ola.max_series[i] = max_f;
                self.ola.q_series[i] = q;
                self.ola.casc_series[i] = casc_f.round().max(1.0) as usize;
            }

            for i in 0..BLOCK_SIZE {
                let center_freq = (self.ola.min_series[i] + self.ola.max_series[i]) * 0.5;
                let freq_range = (self.ola.max_series[i] - self.ola.min_series[i]) * 0.5;
                self.ola.notch_freq_l[i] = center_freq + freq_range * self.ola.lfo_main_l[i];
                self.ola.notch_freq_r[i] = center_freq + freq_range * self.ola.lfo_main_r[i];
                if do_extra {
                    self.ola.notch_freq_l_extra[i] =
                        center_freq + freq_range * self.ola.lfo_extra_l[i];
                    self.ola.notch_freq_r_extra[i] =
                        center_freq + freq_range * self.ola.lfo_extra_r[i];
                }
            }

            // Compute clamped cascade counts using pre-allocated buffer
            for i in 0..BLOCK_SIZE {
                self.ola.casc_series_clamped[i] = self.ola.casc_series[i].min(rt.max_casc).max(1);
            }

            biquad_time_varying_block(
                &mut self.ola.block_l,
                &self.ola.notch_freq_l,
                &self.ola.q_series,
                &self.ola.casc_series_clamped,
                &mut rt.l_main,
                self.sample_rate as f64,
            );
            biquad_time_varying_block(
                &mut self.ola.block_r,
                &self.ola.notch_freq_r,
                &self.ola.q_series,
                &self.ola.casc_series_clamped,
                &mut rt.r_main,
                self.sample_rate as f64,
            );

            if do_extra {
                biquad_time_varying_block(
                    &mut self.ola.block_l,
                    &self.ola.notch_freq_l_extra,
                    &self.ola.q_series,
                    &self.ola.casc_series_clamped,
                    &mut rt.l_extra,
                    self.sample_rate as f64,
                );
                biquad_time_varying_block(
                    &mut self.ola.block_r,
                    &self.ola.notch_freq_r_extra,
                    &self.ola.q_series,
                    &self.ola.casc_series_clamped,
                    &mut rt.r_extra,
                    self.sample_rate as f64,
                );
            }
        }

        // RMS compensation: restore original loudness after notch filtering
        // This matches Python's behavior where it computes rms_in before filtering
        // and then scales output by (rms_in / rms_out) to restore loudness.
        //
        // IMPORTANT: Only apply when we have active sweeps (notch filters).
        // For steady-state noise without sweeps, skipping this avoids per-block
        // volume fluctuations from minor RMS variations.
        if !self.sweep_params.is_empty() && rms_in > 1e-8 {
            let mut sum_sq_l: f32 = 0.0;
            let mut sum_sq_r: f32 = 0.0;
            for i in 0..BLOCK_SIZE {
                sum_sq_l += self.ola.block_l[i] * self.ola.block_l[i];
                sum_sq_r += self.ola.block_r[i] * self.ola.block_r[i];
            }
            let rms_l = (sum_sq_l / BLOCK_SIZE as f32).sqrt();
            let rms_r = (sum_sq_r / BLOCK_SIZE as f32).sqrt();

            // Compute target gains to restore original RMS level.
            // Clamp is critical: with deep/high-Q cascades, tiny rms_out values can
            // create enormous gains that produce spikes. Those spikes poison peak
            // calibration and make the stream end up extremely quiet.
            let raw_target_l = if rms_l > 1e-8 {
                (rms_in / rms_l).clamp(0.25, 16.0)
            } else {
                self.ola.smoothed_gain_l
            };
            let raw_target_r = if rms_r > 1e-8 {
                (rms_in / rms_r).clamp(0.25, 16.0)
            } else {
                self.ola.smoothed_gain_r
            };

            // Apply hysteresis: only update target if the change is significant.
            // This prevents continuous micro-adjustments from block-to-block RMS
            // variations as the swept notch filter moves, which was causing
            // volume instability and "pumping" artifacts.
            let ratio_diff_l = (raw_target_l - self.ola.smoothed_gain_l).abs()
                / self.ola.smoothed_gain_l.max(0.01);
            let ratio_diff_r = (raw_target_r - self.ola.smoothed_gain_r).abs()
                / self.ola.smoothed_gain_r.max(0.01);

            let target_gain_l = if ratio_diff_l > OLA_RMS_HYSTERESIS_RATIO {
                raw_target_l
            } else {
                self.ola.smoothed_gain_l // Keep current, don't chase small variations
            };
            let target_gain_r = if ratio_diff_r > OLA_RMS_HYSTERESIS_RATIO {
                raw_target_r
            } else {
                self.ola.smoothed_gain_r
            };

            // Apply per-sample gain smoothing to prevent clicking from abrupt gain changes.
            // Use the OLA-specific faster smoothing coefficient so gain can settle
            // before the next block is processed. This prevents the "hunting" behavior
            // where smoothed_gain oscillates around a varying target.
            let smooth_coeff = OLA_GAIN_SMOOTHING_COEFF;
            let one_minus_coeff = 1.0 - smooth_coeff;

            for sample in self.ola.block_l.iter_mut() {
                self.ola.smoothed_gain_l =
                    smooth_coeff * self.ola.smoothed_gain_l + one_minus_coeff * target_gain_l;
                *sample *= self.ola.smoothed_gain_l;
            }
            for sample in self.ola.block_r.iter_mut() {
                self.ola.smoothed_gain_r =
                    smooth_coeff * self.ola.smoothed_gain_r + one_minus_coeff * target_gain_r;
                *sample *= self.ola.smoothed_gain_r;
            }
        }

        // Apply window AFTER filtering (filter-before-window architecture).
        // This ensures the IIR filter sees a continuous signal without windowing artifacts.
        for i in 0..BLOCK_SIZE {
            self.ola.block_l[i] *= self.ola.window[i];
            self.ola.block_r[i] *= self.ola.window[i];
        }

        // Overlap-add: accumulate windowed filtered blocks into ring accumulators
        let write_base = self.ola.acc_write_pos;
        for i in 0..BLOCK_SIZE {
            let acc_idx = (write_base + i) % acc_size;
            self.ola.out_acc_l[acc_idx] += self.ola.block_l[i];
            self.ola.out_acc_r[acc_idx] += self.ola.block_r[i];
            self.ola.win_acc[acc_idx] += self.ola.window[i];
        }

        // Advance write position by hop size
        self.ola.acc_write_pos = (self.ola.acc_write_pos + HOP_SIZE) % acc_size;
        self.ola.samples_ready += HOP_SIZE;

        // Advance absolute block start for next block
        self.ola.absolute_block_start += HOP_SIZE;
    }

    /// Generate stereo output using Python-compatible overlap-add processing
    pub fn generate(&mut self, out: &mut [f32]) {
        let frames = out.len() / 2;
        let mut frames_written = 0;
        let acc_size = self.ola.out_acc_l.len();

        while frames_written < frames {
            // If we have ready samples, emit them
            if self.ola.samples_ready > 0 {
                let read_pos = self.ola.acc_read_pos;

                // Normalize by window accumulator and output (Python: out_acc / win_acc where win_acc > 1e-8)
                let win_val = self.ola.win_acc[read_pos];
                let l = if win_val > 1e-8 {
                    self.ola.out_acc_l[read_pos] / win_val
                } else {
                    0.0
                };
                let r = if win_val > 1e-8 {
                    self.ola.out_acc_r[read_pos] / win_val
                } else {
                    0.0
                };

                out[frames_written * 2] = l;
                out[frames_written * 2 + 1] = r;

                // Clear the emitted accumulator slots for reuse (ring buffer)
                self.ola.out_acc_l[read_pos] = 0.0;
                self.ola.out_acc_r[read_pos] = 0.0;
                self.ola.win_acc[read_pos] = 0.0;

                // Advance read position
                self.ola.acc_read_pos = (read_pos + 1) % acc_size;
                self.ola.samples_ready -= 1;
                self.total_samples_output += 1;
                frames_written += 1;
            } else {
                // Need to fill input buffer and process a block
                // Fill the input ring buffer with base noise samples until we have BLOCK_SIZE
                while self.ola.input_samples_buffered < BLOCK_SIZE {
                    let sample = self.next_base();
                    self.ola.input_ring[self.ola.input_write_pos] = sample;
                    self.ola.input_write_pos = (self.ola.input_write_pos + 1) % BLOCK_SIZE;
                    self.ola.input_samples_buffered += 1;
                }

                // Process the block
                self.process_ola_block();

                // After processing, we consumed HOP_SIZE samples worth from input perspective
                // The ring buffer still holds BLOCK_SIZE samples, but logically we've advanced by HOP_SIZE
                // We need to refill HOP_SIZE samples for the next block (50% overlap)
                self.ola.input_samples_buffered = BLOCK_SIZE - HOP_SIZE;
            }
        }
    }
}
