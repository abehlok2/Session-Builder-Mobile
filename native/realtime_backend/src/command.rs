use crate::models::TrackData;

#[derive(Debug)]
pub enum Command {
    UpdateTrack(TrackData),
    UpdateRealtime(TrackData),
    /// Enable or disable GPU accelerated mixing
    EnableGpu(bool),
    /// Pause or resume playback
    SetPaused(bool),
    /// Seek to a new playback position in seconds
    StartFrom(f64),
    /// Adjust the master output gain (0.0 - 1.0)
    SetMasterGain(f32),
    /// Override the per-step binaural gain in realtime
    SetBinauralGain(f32),
    /// Override the per-step noise gain in realtime
    SetNoiseGain(f32),
    /// Override the per-step normalization level in realtime
    SetNormalizationLevel(f32),
    /// Feed audio samples to a streaming overlay clip
    PushClipSamples {
        index: usize,
        data: Vec<f32>,
        finished: bool,
    },
}
