use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig};
use crossbeam::channel::Receiver;
#[cfg(target_os = "android")]
use oboe::{
    AudioOutputCallback, AudioOutputStreamSafe, AudioStream, AudioStreamBase, AudioStreamBuilder,
    AudioStreamSafe, DataCallbackResult, Mono, PerformanceMode, SharingMode, Stereo,
};
use ringbuf::traits::Consumer;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;

use crate::command::Command;

use crate::scheduler::TrackScheduler;

/// Shared state atomics for tracking playback position from the UI thread
pub struct PlaybackState {
    pub elapsed_samples: Arc<AtomicU64>,
    pub current_step: Arc<AtomicU64>,
    pub is_paused: Arc<AtomicBool>,
}

#[cfg(feature = "audio-telemetry")]
struct AudioTelemetry {
    block_count: AtomicU64,
    max_amp_bits: AtomicU32,
}

#[cfg(feature = "audio-telemetry")]
impl AudioTelemetry {
    fn new() -> Self {
        Self {
            block_count: AtomicU64::new(0),
            max_amp_bits: AtomicU32::new(0.0f32.to_bits()),
        }
    }

    fn record_block(&self, data: &[f32]) {
        self.block_count.fetch_add(1, Ordering::Relaxed);
        let mut max_amp = 0.0f32;
        for sample in data {
            let abs = sample.abs();
            if abs > max_amp {
                max_amp = abs;
            }
        }
        self.update_max_amp(max_amp);
    }

    fn update_max_amp(&self, value: f32) {
        let mut current = self.max_amp_bits.load(Ordering::Relaxed);
        loop {
            let current_val = f32::from_bits(current);
            if value <= current_val {
                break;
            }
            match self.max_amp_bits.compare_exchange(
                current,
                value.to_bits(),
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(next) => current = next,
            }
        }
    }
}

#[cfg(feature = "audio-telemetry")]
fn spawn_audio_telemetry_thread(
    stop_rx: Receiver<()>,
    telemetry: Arc<AudioTelemetry>,
    label: &'static str,
) {
    std::thread::spawn(move || {
        let interval = std::time::Duration::from_secs(1);
        loop {
            if stop_rx.recv_timeout(interval).is_ok() {
                break;
            }
            let blocks = telemetry.block_count.swap(0, Ordering::Relaxed);
            let max_amp = f32::from_bits(
                telemetry
                    .max_amp_bits
                    .swap(0.0f32.to_bits(), Ordering::Relaxed),
            );
            log::debug!(
                "{label} telemetry: blocks={}, max_amp={:.4}",
                blocks,
                max_amp
            );
        }
    });
}

pub fn run_audio_stream<C>(
    scheduler: TrackScheduler,
    cmd_rx: C,
    stop_rx: Receiver<()>,
    playback_state: Option<PlaybackState>,
) where
    C: Consumer<Item = Command> + Send + 'static,
{
    #[cfg(target_os = "android")]
    {
        run_audio_stream_android(scheduler, cmd_rx, stop_rx, playback_state);
        return;
    }

    let host = cpal::default_host();
    log::error!(
        "REALTIME_BACKEND: run_audio_stream entered. Host: {:?}",
        host.id()
    );
    let device = host
        .default_output_device()
        .expect("no output device available");
    let supported_config = device.default_output_config().expect("no default config");
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
                // Request larger buffer for emulator stability
                config.buffer_size = cpal::BufferSize::Fixed(4096);
            } else {
                eprintln!(
                    "Sample rate {} not supported, using {}",
                    desired_rate, config.sample_rate.0
                );
            }
        } else {
            eprintln!("Could not query supported output configs; using default");
        }
    } else {
        // desired rate matches default
        config.buffer_size = cpal::BufferSize::Fixed(4096);
    }

    let mut sched = scheduler;
    let mut cmds = cmd_rx;
    #[cfg(feature = "audio-telemetry")]
    let telemetry = Arc::new(AudioTelemetry::new());
    #[cfg(feature = "audio-telemetry")]
    spawn_audio_telemetry_thread(stop_rx.clone(), telemetry.clone(), "CPAL");
    let audio_callback = move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
        while let Some(cmd) = cmds.try_pop() {
            sched.handle_command(cmd);
        }
        #[cfg(feature = "audio-telemetry")]
        telemetry.record_block(data);
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

    let stream = match sample_format {
        SampleFormat::F32 => device
            .build_output_stream(
                &config,
                audio_callback,
                |err| eprintln!("stream error: {err}"),
                None,
            )
            .expect("failed to build output stream"),
        _ => panic!("Unsupported sample format"),
    };
    stream.play().unwrap();

    // Keep the stream alive until a stop signal is received
    while stop_rx
        .recv_timeout(std::time::Duration::from_millis(100))
        .is_err()
    {}
}

#[cfg(target_os = "android")]
struct AndroidAudioCallback<C> {
    scheduler: TrackScheduler,
    cmd_rx: C,
    playback_state: Option<PlaybackState>,
    #[cfg(feature = "audio-telemetry")]
    telemetry: Arc<AudioTelemetry>,
}

#[cfg(target_os = "android")]
impl<C> AudioOutputCallback for AndroidAudioCallback<C>
where
    C: Consumer<Item = Command> + Send,
{
    type FrameType = (f32, Stereo);

    fn on_audio_ready(
        &mut self,
        _stream: &mut dyn AudioOutputStreamSafe,
        audio_data: &mut [(f32, f32)],
    ) -> DataCallbackResult {
        while let Some(cmd) = self.cmd_rx.try_pop() {
            self.scheduler.handle_command(cmd);
        }

        // Cast (f32, f32) slice to f32 slice for process_block
        let float_slice = unsafe {
            std::slice::from_raw_parts_mut(
                audio_data.as_mut_ptr() as *mut f32,
                audio_data.len() * 2,
            )
        };
        #[cfg(feature = "audio-telemetry")]
        self.telemetry.record_block(float_slice);

        self.scheduler.process_block(float_slice);

        if let Some(ref state) = self.playback_state {
            state
                .elapsed_samples
                .store(self.scheduler.absolute_sample, Ordering::Relaxed);
            state
                .current_step
                .store(self.scheduler.current_step as u64, Ordering::Relaxed);
            state
                .is_paused
                .store(self.scheduler.paused, Ordering::Relaxed);
        }

        DataCallbackResult::Continue
    }
}

/// Buffer size for Android audio output (in frames).
/// Using 2048 frames at 44100Hz = ~46ms latency.
/// This provides a more stable buffer for continuous playback,
/// preventing choppy/static audio on devices like Galaxy S21.
/// The larger buffer helps compensate for CPU scheduling variability
/// on mobile devices which can cause buffer underruns.
#[cfg(target_os = "android")]
const ANDROID_BUFFER_FRAMES: i32 = 2048;

#[cfg(target_os = "android")]
fn run_audio_stream_android<C>(
    scheduler: TrackScheduler,
    cmd_rx: C,
    stop_rx: Receiver<()>,
    playback_state: Option<PlaybackState>,
) where
    C: Consumer<Item = Command> + Send + 'static,
{
    log::error!("REALTIME_BACKEND: Starting Oboe stream (Android specialized)...");

    let callback = AndroidAudioCallback {
        scheduler,
        cmd_rx,
        playback_state,
        #[cfg(feature = "audio-telemetry")]
        telemetry: Arc::new(AudioTelemetry::new()),
    };
    #[cfg(feature = "audio-telemetry")]
    spawn_audio_telemetry_thread(stop_rx.clone(), callback.telemetry.clone(), "Oboe");

    // Use PerformanceMode::None instead of LowLatency for more stable playback.
    // LowLatency can cause buffer underruns on some devices leading to choppy audio.
    // Also set explicit buffer size to prevent underruns.
    // Using 8x buffer capacity (vs 4x) provides more headroom for CPU scheduling
    // variability on mobile devices, reducing stuttering during background tasks.
    let mut stream = AudioStreamBuilder::default()
        .set_performance_mode(PerformanceMode::LowLatency)
        .set_sharing_mode(SharingMode::Shared)
        .set_format::<f32>()
        .set_channel_count::<Stereo>()
        .set_sample_rate(44100)
        .set_frames_per_callback(ANDROID_BUFFER_FRAMES)
        .set_buffer_capacity_in_frames(ANDROID_BUFFER_FRAMES * 4)
        .set_callback(callback)
        .open_stream()
        .expect("Failed to open Oboe stream");

    log::error!(
        "REALTIME_BACKEND: Oboe stream opened. Buffer size: {} frames, capacity: {} frames",
        stream.get_frames_per_burst(),
        stream.get_buffer_size_in_frames()
    );

    stream.start().expect("Failed to start Oboe stream");

    log::error!("REALTIME_BACKEND: Oboe stream started successfully.");

    while stop_rx
        .recv_timeout(std::time::Duration::from_millis(100))
        .is_err()
    {}
}

// The actual stop logic is handled via the channel in `run_audio_stream`.
pub fn stop_audio_stream(sender: &crossbeam::channel::Sender<()>) {
    let _ = sender.send(());
}
