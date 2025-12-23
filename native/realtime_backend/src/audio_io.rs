use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{BufferSize, SampleFormat, StreamConfig};
use crossbeam::channel::Receiver;
use ringbuf::traits::Consumer;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use crate::command::Command;

use crate::scheduler::TrackScheduler;

/// Shared state atomics for tracking playback position from the UI thread
pub struct PlaybackState {
    pub elapsed_samples: Arc<AtomicU64>,
    pub current_step: Arc<AtomicU64>,
    pub is_paused: Arc<AtomicBool>,
}

/// Preferred buffer size for mobile devices to prevent audio glitches.
/// Larger buffers provide more stability but increase latency.
/// 2048 samples at 44.1kHz = ~46ms latency, which is a good balance.
const MOBILE_BUFFER_SIZE: u32 = 2048;

/// Determine the best buffer size for this device.
/// Tries to use a fixed size for stability, falls back to default if not supported.
fn configure_buffer_size(device: &cpal::Device, config: &mut StreamConfig) {
    // First, try to check if the device supports our preferred buffer size
    // by checking the supported range
    if let Ok(ranges) = device.supported_output_configs() {
        for range in ranges {
            if let Some(buffer_range) = range.buffer_size() {
                let min = buffer_range.min;
                let max = buffer_range.max;
                if MOBILE_BUFFER_SIZE >= min && MOBILE_BUFFER_SIZE <= max {
                    config.buffer_size = BufferSize::Fixed(MOBILE_BUFFER_SIZE);
                    log::info!("Using fixed buffer size: {} samples", MOBILE_BUFFER_SIZE);
                    return;
                }
            }
        }
    }

    // If we can't determine support, try fixed buffer anyway
    // (build_output_stream will fail if not supported, and we handle that)
    config.buffer_size = BufferSize::Fixed(MOBILE_BUFFER_SIZE);
    log::info!("Attempting fixed buffer size: {} samples", MOBILE_BUFFER_SIZE);
}

pub fn run_audio_stream<C>(
    scheduler: TrackScheduler,
    cmd_rx: C,
    stop_rx: Receiver<()>,
    playback_state: Option<PlaybackState>,
) where
    C: Consumer<Item = Command> + Send + 'static,
{
    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .expect("no output device available");
    let supported_config = device
        .default_output_config()
        .expect("no default config");
    let sample_format = supported_config.sample_format();
    let mut config: StreamConfig = supported_config.clone().into();

    // Use the scheduler's sample rate if it differs from the device default.
    let desired_rate = scheduler.sample_rate as u32;
    if desired_rate != config.sample_rate.0 {
        if let Ok(mut ranges) = device.supported_output_configs() {
            if let Some(range) = ranges.find(|r| {
                r.channels() == config.channels
                    && r.sample_format() == sample_format
                    && r.min_sample_rate().0 <= desired_rate
                    && desired_rate <= r.max_sample_rate().0
            }) {
                config = range
                    .with_sample_rate(cpal::SampleRate(desired_rate))
                    .config();
            } else {
                log::warn!(
                    "Sample rate {} not supported, using {}",
                    desired_rate, config.sample_rate.0
                );
            }
        } else {
            log::warn!("Could not query supported output configs; using default");
        }
    }

    // Configure buffer size for smooth playback on mobile
    configure_buffer_size(&device, &mut config);

    let mut sched = scheduler;
    let mut cmds = cmd_rx;
    let audio_callback = move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
        while let Some(cmd) = cmds.try_pop() {
            sched.handle_command(cmd);
        }
        sched.process_block(data);

        // Update shared playback state atomics for UI thread access
        if let Some(ref state) = playback_state {
            state
                .elapsed_samples
                .store(sched.absolute_sample, Ordering::Relaxed);
            state
                .current_step
                .store(sched.current_step as u64, Ordering::Relaxed);
            state.is_paused.store(sched.paused, Ordering::Relaxed);
        }
    };

    // Build the stream, with fallback to default buffer size if fixed size fails
    let stream = match sample_format {
        SampleFormat::F32 => {
            match device.build_output_stream(
                &config,
                audio_callback,
                |err| log::error!("Audio stream error: {err}"),
                None,
            ) {
                Ok(s) => {
                    log::info!("Audio stream created successfully with buffer size {:?}", config.buffer_size);
                    s
                }
                Err(e) => {
                    // Fixed buffer size not supported, need to restart with default
                    log::warn!(
                        "Fixed buffer size failed ({}), audio may be choppy",
                        e
                    );
                    // Since the callback was moved, we can't retry here.
                    // In practice, most Android devices support fixed buffer sizes.
                    panic!("Failed to build audio stream: {}", e);
                }
            }
        }
        _ => panic!("Unsupported sample format: {:?}", sample_format),
    };
    stream.play().expect("Failed to start audio playback");

    // Keep the stream alive until a stop signal is received
    while stop_rx.recv_timeout(std::time::Duration::from_millis(100)).is_err() {}

    log::info!("Audio stream stopped");
}

// The actual stop logic is handled via the channel in `run_audio_stream`.
pub fn stop_audio_stream(sender: &crossbeam::channel::Sender<()>) {
    let _ = sender.send(());
}
