# Agent Notes

## Inventory & contract freeze (short term)
Freeze the API contract exposed to Flutter in `lib/src/rust/mobile_api.dart` and used by `lib/logic/state/audio_controller.dart`. This defines the public surface you must re-implement (start/stop/pause/resume, updateSession, waveform generation, render WAV, playback status, etc.).

Identify core modules to port from `native/realtime_backend/src`:

- `mobile_api.rs`: lifecycle + command routing + playback state
- `audio_io.rs`: audio output + worker ring buffer + underrun handling
- `scheduler.rs`, `voices.rs`, `streaming_noise.rs`, `noise_params.rs`: DSP engine + voice generation
- `dsp/*`: envelopes, noise generation, LUT trig, panning, modulation helpers
- `models.rs`: JSON schema & defaults for Track/Step/Voice data
- `command.rs`: command enum (pause/resume/update/etc.)
- `config.rs`: output paths, config constants

Clarify platforms: Kotlin will naturally cover Android, but iOS will need a separate Swift/Obj-C implementation or a Kotlin Multiplatform strategy (which still requires native audio plumbing on iOS). Confirm scope.

## Project direction
The goal for the next series of tasks is to migrate the rust-based `realtime_backend` code into Kotlin.

This project is primarily for Android (too hard to get on apple marketplace). So this will be the focus.
