use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig};
use crossbeam::channel::Receiver;
#[cfg(target_os = "android")]
use oboe::{
    AudioOutputCallback, AudioOutputStreamSafe, AudioStream, AudioStreamBase, AudioStreamBuilder,
    AudioStreamSafe, DataCallbackResult, Mono, PerformanceMode, SharingMode, Stereo,
};
use ringbuf::traits::{Consumer, Observer, Producer, Split};
use ringbuf::HeapRb;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use crate::command::Command;

use crate::scheduler::TrackScheduler;

/// Shared state atomics for tracking playback position from the UI thread
pub struct PlaybackState {
    pub elapsed_samples: Arc<AtomicU64>,
    pub current_step: Arc<AtomicU64>,
    pub is_paused: Arc<AtomicBool>,
}

const AUDIO_RING_MIN_SECONDS: f32 = 0.5;
const AUDIO_RING_MAX_SECONDS: f32 = 2.0;
const AUDIO_WORKER_BLOCK_FRAMES: usize = 512;

fn samples_for_seconds(sample_rate: u32, seconds: f32, channels: usize) -> usize {
    ((sample_rate as f32 * seconds).ceil() as usize).saturating_mul(channels)
}

fn mix_from_ringbuffer<C: Consumer<Item = f32>>(
    consumer: &mut C,
    data: &mut [f32],
    last_sample: &mut f32,
    low_watermark_samples: usize,
) {
    let available = consumer.occupied_len();
    let copied = consumer.pop_slice(data);
    if copied > 0 {
        if available < low_watermark_samples {
            let fade_len = copied.max(1) as f32;
            for (idx, sample) in data[..copied].iter_mut().enumerate() {
                let alpha = (idx + 1) as f32 / fade_len;
                *sample = *last_sample * (1.0 - alpha) + *sample * alpha;
            }
        }
        *last_sample = data[copied - 1];
    }
    if copied < data.len() {
        for sample in &mut data[copied..] {
            *sample = *last_sample;
        }
    }
}

fn update_playback_state(playback_state: &Option<PlaybackState>, scheduler: &TrackScheduler) {
    if let Some(ref state) = playback_state {
        state
            .elapsed_samples
            .store(scheduler.absolute_sample, Ordering::Relaxed);
        state
            .current_step
            .store(scheduler.current_step as u64, Ordering::Relaxed);
        state.is_paused.store(scheduler.paused, Ordering::Relaxed);
    }
}

fn spawn_audio_worker<C>(
    mut scheduler: TrackScheduler,
    mut cmd_rx: C,
    mut producer: ringbuf::HeapProd<f32>,
    playback_state: Option<PlaybackState>,
    stop_flag: Arc<AtomicBool>,
    sample_rate: u32,
    channels: usize,
) where
    C: Consumer<Item = Command> + Send + 'static,
{
    thread::spawn(move || {
        let min_samples = samples_for_seconds(sample_rate, AUDIO_RING_MIN_SECONDS, channels);
        let max_samples = samples_for_seconds(sample_rate, AUDIO_RING_MAX_SECONDS, channels)
            .max(AUDIO_WORKER_BLOCK_FRAMES * channels);
        let mut block = vec![0.0f32; AUDIO_WORKER_BLOCK_FRAMES * channels];

        while !stop_flag.load(Ordering::Relaxed) {
            while let Some(cmd) = cmd_rx.try_pop() {
                scheduler.handle_command(cmd);
            }

            if producer.occupied_len() < min_samples {
                let target = max_samples.min(producer.capacity().get());
                while producer.occupied_len() < target && !stop_flag.load(Ordering::Relaxed) {
                    let vacant = producer.vacant_len();
                    if vacant == 0 {
                        break;
                    }
                    let mut samples_to_write = vacant
                        .min(block.len())
                        .min(target.saturating_sub(producer.occupied_len()));
                    samples_to_write = (samples_to_write / channels) * channels;
                    if samples_to_write == 0 {
                        break;
                    }
                    if block.len() < samples_to_write {
                        block.resize(samples_to_write, 0.0);
                    }
                    scheduler.process_block(&mut block[..samples_to_write]);
                    let pushed = producer.push_slice(&block[..samples_to_write]);
                    if pushed == 0 {
                        break;
                    }
                    update_playback_state(&playback_state, &scheduler);
                }
            } else {
                thread::sleep(Duration::from_millis(5));
            }
        }
    });
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

    let channels = 2usize;
    let sample_rate = scheduler.sample_rate as u32;
    let max_samples = samples_for_seconds(sample_rate, AUDIO_RING_MAX_SECONDS, channels)
        .max(AUDIO_WORKER_BLOCK_FRAMES * channels);
    let rb = HeapRb::<f32>::new(max_samples);
    let (producer, mut consumer) = rb.split();
    let low_watermark_samples = samples_for_seconds(sample_rate, AUDIO_RING_MIN_SECONDS, channels);
    let stop_flag = Arc::new(AtomicBool::new(false));
    spawn_audio_worker(
        scheduler,
        cmd_rx,
        producer,
        playback_state,
        Arc::clone(&stop_flag),
        sample_rate,
        channels,
    );
    #[cfg(feature = "audio-telemetry")]
    let telemetry = Arc::new(AudioTelemetry::new());
    #[cfg(feature = "audio-telemetry")]
    spawn_audio_telemetry_thread(stop_rx.clone(), telemetry.clone(), "CPAL");
    let mut last_sample = 0.0f32;
    let audio_callback = move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
        mix_from_ringbuffer(&mut consumer, data, &mut last_sample, low_watermark_samples);
        #[cfg(feature = "audio-telemetry")]
        telemetry.record_block(data);
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
    stop_flag.store(true, Ordering::Relaxed);
}

#[cfg(target_os = "android")]
struct AndroidAudioCallback {
    audio_consumer: ringbuf::HeapCons<f32>,
    last_sample: f32,
    low_watermark_samples: usize,
    #[cfg(feature = "audio-telemetry")]
    telemetry: Arc<AudioTelemetry>,
}

#[cfg(target_os = "android")]
impl AudioOutputCallback for AndroidAudioCallback {
    type FrameType = (f32, Stereo);

    fn on_audio_ready(
        &mut self,
        _stream: &mut dyn AudioOutputStreamSafe,
        audio_data: &mut [(f32, f32)],
    ) -> DataCallbackResult {
        // Cast (f32, f32) slice to f32 slice for process_block
        let float_slice = unsafe {
            std::slice::from_raw_parts_mut(
                audio_data.as_mut_ptr() as *mut f32,
                audio_data.len() * 2,
            )
        };
        mix_from_ringbuffer(
            &mut self.audio_consumer,
            float_slice,
            &mut self.last_sample,
            self.low_watermark_samples,
        );
        #[cfg(feature = "audio-telemetry")]
        self.telemetry.record_block(float_slice);

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

    let channels = 2usize;
    let sample_rate = scheduler.sample_rate as u32;
    let max_samples = samples_for_seconds(sample_rate, AUDIO_RING_MAX_SECONDS, channels)
        .max(AUDIO_WORKER_BLOCK_FRAMES * channels);
    let rb = HeapRb::<f32>::new(max_samples);
    let (producer, consumer) = rb.split();
    let low_watermark_samples = samples_for_seconds(sample_rate, AUDIO_RING_MIN_SECONDS, channels);
    let stop_flag = Arc::new(AtomicBool::new(false));
    spawn_audio_worker(
        scheduler,
        cmd_rx,
        producer,
        playback_state,
        Arc::clone(&stop_flag),
        sample_rate,
        channels,
    );

    let callback = AndroidAudioCallback {
        audio_consumer: consumer,
        last_sample: 0.0f32,
        low_watermark_samples,
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
        .set_performance_mode(PerformanceMode::None)
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
    stop_flag.store(true, Ordering::Relaxed);
}

// The actual stop logic is handled via the channel in `run_audio_stream`.
pub fn stop_audio_stream(sender: &crossbeam::channel::Sender<()>) {
    let _ = sender.send(());
}
