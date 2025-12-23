use crate::audio_io::{self, PlaybackState};
use crate::command::Command;
use crate::config::CONFIG;
use crate::models::TrackData;
use crate::scheduler::TrackScheduler;
use crate::voice_loader;
use lazy_static::lazy_static;
use parking_lot::Mutex;
use ringbuf::traits::{Split, Producer};
use ringbuf::HeapRb;
use flutter_rust_bridge::frb;
use cpal::traits::HostTrait;
use hound::{SampleFormat, WavSpec, WavWriter};
use std::sync::atomic::{AtomicU64, AtomicBool, Ordering};
use std::sync::Arc;

struct EngineState {
    command_producer: ringbuf::HeapProd<Command>,
    stop_sender: crossbeam::channel::Sender<()>,
    /// Shared state for tracking playback position
    elapsed_samples: Arc<AtomicU64>,
    /// Shared state for tracking current step
    current_step: Arc<AtomicU64>,
    /// Shared state for tracking pause status
    is_paused: Arc<AtomicBool>,
    /// Sample rate used for converting samples to time
    sample_rate: u32,
}

// We use a lazy_static Mutex to hold the global engine state.
// In a real app, you might want slightly more robust state management,
// but for a single active audio session, this is sufficient.
lazy_static! {
    static ref ENGINE: Mutex<Option<EngineState>> = Mutex::new(None);
}

#[frb(init)]
pub fn init_app() {
    // Default initialization logic for the Rust environment
    flutter_rust_bridge::setup_default_user_utils();
    crate::logging::init_logging();
}

pub fn start_audio_session(track_json: String, start_time: Option<f64>) -> anyhow::Result<()> {
    log::error!("REALTIME_BACKEND: start_audio_session called");
    log::error!("REALTIME_BACKEND: track_json len: {}", track_json.len());

    // Stop existing session
    stop_audio_session();

    let track_data: TrackData = serde_json::from_str(&track_json)
        .map_err(|e| anyhow::anyhow!("Invalid track JSON: {}", e))?;
    
    log::info!("track_data parsed successfully");


    // Device setup
    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .ok_or_else(|| anyhow::anyhow!("No output device available"))?;
    let config = cpal::traits::DeviceTrait::default_output_config(&device)
        .map_err(|e| anyhow::anyhow!("Failed to get default output config: {}", e))?;
    let sample_rate = config.sample_rate().0;

    // Spawn voice loader
    let (loader_tx, loader_rx) = voice_loader::spawn_voice_loader();

    let start_secs = start_time.unwrap_or(0.0);
    let mut scheduler = TrackScheduler::new_with_start(
        track_data,
        sample_rate,
        start_secs,
        Some(loader_tx),
        Some(loader_rx),
    );
    scheduler.gpu_enabled = false; // Disable GPU for mobile realtime

    // Create command ring buffer
    let rb = HeapRb::<Command>::new(1024);
    let (prod, cons) = rb.split();

    // Create stop channel
    let (stop_tx, stop_rx) = crossbeam::channel::unbounded();

    // Create shared playback state atomics
    let elapsed_samples = Arc::new(AtomicU64::new(0));
    let current_step = Arc::new(AtomicU64::new(0));
    let is_paused = Arc::new(AtomicBool::new(false));

    // Clone Arcs for the audio thread
    let playback_state = PlaybackState {
        elapsed_samples: Arc::clone(&elapsed_samples),
        current_step: Arc::clone(&current_step),
        is_paused: Arc::clone(&is_paused),
    };

    // Spawn audio thread
    std::thread::spawn(move || {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            audio_io::run_audio_stream(scheduler, cons, stop_rx, Some(playback_state));
        }));
        if let Err(e) = result {
            // Try to downcast the panic to string
            if let Some(s) = e.downcast_ref::<&str>() {
                 log::error!("FATAL: Audio thread panicked: {}", s);
            } else if let Some(s) = e.downcast_ref::<String>() {
                 log::error!("FATAL: Audio thread panicked: {}", s);
            } else {
                 log::error!("FATAL: Audio thread panicked with unknown error");
            }
        }
    });

    // Store state with shared atomics
    let mut guard = ENGINE.lock();
    *guard = Some(EngineState {
        command_producer: prod,
        stop_sender: stop_tx,
        elapsed_samples,
        current_step,
        is_paused,
        sample_rate,
    });

    Ok(())
}

pub fn stop_audio_session() {
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.take() {
        // Sending stop signal
        let _ = state.stop_sender.send(());
        // The audio thread will drop and exit
    }
}

pub fn pause_audio() {
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.as_mut() {
        let _ = state.command_producer.try_push(Command::SetPaused(true));
    }
}

pub fn resume_audio() {
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.as_mut() {
        let _ = state.command_producer.try_push(Command::SetPaused(false));
    }
}

pub fn set_volume(volume: f32) {
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.as_mut() {
        let _ = state.command_producer.try_push(Command::SetMasterGain(volume));
    }
}

pub fn update_session(track_json: String) -> anyhow::Result<()> {
    let track_data: TrackData = serde_json::from_str(&track_json)
        .map_err(|e| anyhow::anyhow!("Invalid track JSON: {}", e))?;
    
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.as_mut() {
        let _ = state.command_producer.try_push(Command::UpdateTrack(track_data));
    }
    Ok(())
}

pub fn push_clip_chunk(index: usize, samples: Vec<f32>, finished: bool) {
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.as_mut() {
        let _ = state.command_producer.try_push(Command::PushClipSamples {
            index,
            data: samples,
            finished,
        });
    }
}

/// Seek to a specific position in the audio stream (in seconds)
/// Maps to Python's start_from function
pub fn start_from(position: f64) {
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.as_mut() {
        let _ = state.command_producer.try_push(Command::StartFrom(position));
    }
}

/// Enable or disable GPU acceleration for audio processing
/// Maps to Python's enable_gpu function
pub fn enable_gpu(enable: bool) {
    let mut guard = ENGINE.lock();
    if let Some(state) = guard.as_mut() {
        let _ = state.command_producer.try_push(Command::EnableGpu(enable));
    }
}

/// Render up to 60 seconds of audio to a WAV file
/// Maps to Python's render_sample_wav function
pub fn render_sample_wav(track_json: String, out_path: String) -> anyhow::Result<()> {
    let track_data: TrackData = serde_json::from_str(&track_json)
        .map_err(|e| anyhow::anyhow!("Invalid track JSON: {}", e))?;

    let sample_rate = track_data.global_settings.sample_rate;
    let mut scheduler = TrackScheduler::new(track_data.clone(), sample_rate);
    // Use GPU acceleration when rendering to a file if available
    scheduler.gpu_enabled = true;

    let track_frames: usize = track_data
        .steps
        .iter()
        .map(|s| (s.duration * sample_rate as f64) as usize)
        .sum();
    // Limit to 60 seconds for sample rendering
    let target_frames = (sample_rate as usize * 60).min(track_frames);

    let spec = WavSpec {
        channels: 2,
        sample_rate,
        bits_per_sample: 16,
        sample_format: SampleFormat::Int,
    };

    let output_path = if std::path::Path::new(&out_path).is_absolute() {
        std::path::PathBuf::from(&out_path)
    } else {
        CONFIG.output_dir.join(&out_path)
    };

    if let Some(parent) = output_path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| anyhow::anyhow!("Failed to create output directory: {}", e))?;
    }

    let mut writer = WavWriter::create(&output_path, spec)
        .map_err(|e| anyhow::anyhow!("Failed to create WAV file: {}", e))?;

    let mut remaining = target_frames;
    let mut buffer = vec![0.0f32; 512 * 2];
    while remaining > 0 {
        let frames = 512.min(remaining);
        buffer.resize(frames * 2, 0.0);
        scheduler.process_block(&mut buffer);
        for sample in &buffer[..frames * 2] {
            let s = (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16;
            writer
                .write_sample(s)
                .map_err(|e| anyhow::anyhow!("Failed to write sample: {}", e))?;
        }
        remaining -= frames;
    }

    writer
        .finalize()
        .map_err(|e| anyhow::anyhow!("Failed to finalize WAV file: {}", e))?;

    Ok(())
}

/// Render the complete audio track to a WAV file
/// Maps to Python's render_full_wav function
pub fn render_full_wav(track_json: String, out_path: String) -> anyhow::Result<()> {
    let track_data: TrackData = serde_json::from_str(&track_json)
        .map_err(|e| anyhow::anyhow!("Invalid track JSON: {}", e))?;

    let sample_rate = track_data.global_settings.sample_rate;
    let mut scheduler = TrackScheduler::new(track_data.clone(), sample_rate);
    // Enable GPU acceleration for full track rendering
    scheduler.gpu_enabled = true;

    let target_frames: usize = track_data
        .steps
        .iter()
        .map(|s| (s.duration * sample_rate as f64) as usize)
        .sum();

    let spec = WavSpec {
        channels: 2,
        sample_rate,
        bits_per_sample: 16,
        sample_format: SampleFormat::Int,
    };

    let output_path = if std::path::Path::new(&out_path).is_absolute() {
        std::path::PathBuf::from(&out_path)
    } else {
        CONFIG.output_dir.join(&out_path)
    };

    if let Some(parent) = output_path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| anyhow::anyhow!("Failed to create output directory: {}", e))?;
    }

    let mut writer = WavWriter::create(&output_path, spec)
        .map_err(|e| anyhow::anyhow!("Failed to create WAV file: {}", e))?;

    log::info!("Rendering full track: {} frames at {} Hz", target_frames, sample_rate);
    let start_time = std::time::Instant::now();

    let mut remaining = target_frames;
    let mut buffer = vec![0.0f32; 512 * 2];
    while remaining > 0 {
        let frames = 512.min(remaining);
        buffer.resize(frames * 2, 0.0);
        scheduler.process_block(&mut buffer);
        for sample in &buffer[..frames * 2] {
            let s = (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16;
            writer
                .write_sample(s)
                .map_err(|e| anyhow::anyhow!("Failed to write sample: {}", e))?;
        }
        remaining -= frames;
    }

    writer
        .finalize()
        .map_err(|e| anyhow::anyhow!("Failed to finalize WAV file: {}", e))?;

    let elapsed = start_time.elapsed().as_secs_f32();
    log::info!("Total generation time: {:.2}s", elapsed);

    Ok(())
}

/// Set the master output gain (volume)
/// Alias for set_volume to match Python API naming
pub fn set_master_gain(gain: f32) {
    set_volume(gain);
}

/// Get the current playback status
pub fn is_audio_playing() -> bool {
    let guard = ENGINE.lock();
    guard.is_some()
}

/// Get the current sample rate of the active session
pub fn get_sample_rate() -> Option<u32> {
    let guard = ENGINE.lock();
    guard.as_ref().map(|s| s.sample_rate)
}

/// Generate waveform data for visualization
/// Returns amplitude values (0.0 to 1.0) sampled at regular intervals
/// for the given duration in seconds
pub fn generate_waveform_snippet(duration_sec: f32) -> Vec<f32> {
    // Generate 100 samples per second for visualization
    let samples_per_second = 100;
    let total_samples = (duration_sec * samples_per_second as f32) as usize;

    if total_samples == 0 {
        return vec![];
    }

    let mut waveform = Vec::with_capacity(total_samples);

    // Generate a representative waveform pattern
    // Combines multiple frequencies to create an organic look
    for i in 0..total_samples {
        let t = i as f32 / samples_per_second as f32;

        // Combine multiple sine waves at different frequencies
        let wave1 = (t * 2.0 * std::f32::consts::PI * 0.5).sin() * 0.3;
        let wave2 = (t * 2.0 * std::f32::consts::PI * 1.2).sin() * 0.2;
        let wave3 = (t * 2.0 * std::f32::consts::PI * 2.8).sin() * 0.15;
        let wave4 = (t * 2.0 * std::f32::consts::PI * 0.1).sin() * 0.25;

        // Add some noise for organic feel
        let noise = ((t * 17.3).sin() * (t * 31.7).cos()) * 0.1;

        // Combine and normalize to 0.0-1.0 range
        let combined = (wave1 + wave2 + wave3 + wave4 + noise).abs();
        let normalized = (combined + 0.2).min(1.0); // Add baseline and clamp

        waveform.push(normalized);
    }

    waveform
}

/// Generate waveform data from a track JSON configuration
/// This creates waveform visualization based on the step structure
pub fn generate_track_waveform(track_json: String, samples_per_second: u32) -> anyhow::Result<Vec<f32>> {
    let track_data: crate::models::TrackData = serde_json::from_str(&track_json)
        .map_err(|e| anyhow::anyhow!("Invalid track JSON: {}", e))?;

    // Calculate total duration from all steps
    let total_duration: f64 = track_data.steps.iter()
        .map(|s| s.duration)
        .sum();

    let total_samples = (total_duration * samples_per_second as f64) as usize;

    if total_samples == 0 {
        return Ok(vec![]);
    }

    let mut waveform = Vec::with_capacity(total_samples);
    let mut current_sample = 0usize;

    for (step_idx, step) in track_data.steps.iter().enumerate() {
        let step_samples = (step.duration * samples_per_second as f64) as usize;
        let voice_count = step.voices.len().max(1) as f32;

        for i in 0..step_samples {
            let local_t = i as f32 / samples_per_second as f32;
            let global_t = (current_sample + i) as f32 / samples_per_second as f32;

            // Base amplitude varies by step (different presets = different patterns)
            let step_factor = 0.4 + ((step_idx as f32 * 0.7).sin().abs() * 0.4);

            // Create wave pattern based on voices
            let wave = (local_t * 2.0 * std::f32::consts::PI * (1.0 + voice_count * 0.2)).sin();
            let envelope = (local_t * 0.5).sin().abs() * 0.3 + 0.5;

            // Add subtle variation
            let variation = (global_t * 13.7).sin() * 0.1;

            let amplitude = ((wave.abs() * envelope * step_factor) + variation).clamp(0.1, 1.0);
            waveform.push(amplitude);
        }

        current_sample += step_samples;
    }

    Ok(waveform)
}

/// Get the current playback position in seconds
/// Returns None if no audio session is active
pub fn get_playback_position() -> Option<f64> {
    let guard = ENGINE.lock();
    guard.as_ref().map(|state| {
        let samples = state.elapsed_samples.load(Ordering::Relaxed);
        samples as f64 / state.sample_rate as f64
    })
}

/// Get the number of elapsed samples since playback started
/// Returns None if no audio session is active
pub fn get_elapsed_samples() -> Option<u64> {
    let guard = ENGINE.lock();
    guard.as_ref().map(|state| state.elapsed_samples.load(Ordering::Relaxed))
}

/// Get the current step index (0-based)
/// Returns None if no audio session is active
pub fn get_current_step() -> Option<u64> {
    let guard = ENGINE.lock();
    guard.as_ref().map(|state| state.current_step.load(Ordering::Relaxed))
}

/// Check if playback is currently paused
/// Returns None if no audio session is active
pub fn get_is_paused() -> Option<bool> {
    let guard = ENGINE.lock();
    guard.as_ref().map(|state| state.is_paused.load(Ordering::Relaxed))
}

/// Get complete playback status as a struct
/// Returns position in seconds, current step index, and paused state
/// Returns None if no audio session is active
pub fn get_playback_status() -> Option<PlaybackStatus> {
    let guard = ENGINE.lock();
    guard.as_ref().map(|state| PlaybackStatus {
        position_seconds: state.elapsed_samples.load(Ordering::Relaxed) as f64 / state.sample_rate as f64,
        current_step: state.current_step.load(Ordering::Relaxed),
        is_paused: state.is_paused.load(Ordering::Relaxed),
        sample_rate: state.sample_rate,
    })
}

/// Playback status information returned by get_playback_status
#[derive(Clone, Debug)]
pub struct PlaybackStatus {
    /// Current playback position in seconds
    pub position_seconds: f64,
    /// Current step index (0-based)
    pub current_step: u64,
    /// Whether playback is paused
    pub is_paused: bool,
    /// Sample rate of the audio session
    pub sample_rate: u32,
}
