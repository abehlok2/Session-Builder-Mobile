use crate::audio_io;
use crate::command::Command;
use crate::models::TrackData;
use crate::scheduler::TrackScheduler;
use crate::voice_loader;
use lazy_static::lazy_static;
use parking_lot::Mutex;
use ringbuf::traits::{Split, Producer, Consumer};
use ringbuf::HeapRb;
use std::sync::Arc;
use flutter_rust_bridge::frb;
use cpal::traits::{DeviceTrait, HostTrait};

struct EngineState {
    command_producer: ringbuf::HeapProd<Command>,
    stop_sender: crossbeam::channel::Sender<()>,
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
}

pub fn start_audio_session(track_json: String, start_time: Option<f64>) -> anyhow::Result<()> {
    // Stop any existing session
    stop_audio_session();

    let track_data: TrackData = serde_json::from_str(&track_json)
        .map_err(|e| anyhow::anyhow!("Invalid track JSON: {}", e))?;

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

    // Spawn audio thread
    std::thread::spawn(move || {
        audio_io::run_audio_stream(scheduler, cons, stop_rx);
    });

    // Store state
    let mut guard = ENGINE.lock();
    *guard = Some(EngineState {
        command_producer: prod,
        stop_sender: stop_tx,
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

// Generate simple waveform data for visualization (optional, if we want to add it later)
pub fn generate_waveform_snippet(_duration_sec: f32) -> Vec<f32> {
    // Placeholder for potential future visualization logic
    vec![]
}
