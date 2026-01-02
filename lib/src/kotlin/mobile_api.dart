import 'dart:typed_data';

import 'package:flutter/services.dart';

const MethodChannel _channel = MethodChannel(
  'com.binauralbuilder.session_builder_mobile/audio',
);

Future<void> initAudioEngine() => _channel.invokeMethod('init');

Future<void> startAudioSession({
  required String trackJson,
  double? startTime,
}) async {
  await initAudioEngine();
  await _channel.invokeMethod('loadTrack', {'json': trackJson});
  if (startTime != null && startTime > 0) {
    await _channel.invokeMethod('seekTo', {'time': startTime});
  }
  await _channel.invokeMethod('play');
}

Future<void> stopAudioSession() => _channel.invokeMethod('stop');

Future<void> pauseAudio() => _channel.invokeMethod('pause');

Future<void> resumeAudio() => _channel.invokeMethod('play');

Future<void> setVolume({required double volume}) =>
    _channel.invokeMethod('setMasterGain', {'gain': volume});

Future<void> updateSession({required String trackJson}) =>
    _channel.invokeMethod('updateTrack', {'json': trackJson});

Future<void> pushClipChunk({
  required BigInt index,
  required List<double> samples,
  required bool finished,
}) => throw UnimplementedError(
  'pushClipChunk is not implemented in the Kotlin backend.',
);

/// Seek to a specific position in the audio stream (in seconds)
/// Maps to Kotlin's seekTo function
Future<void> startFrom({required double position}) =>
    _channel.invokeMethod('seekTo', {'time': position});

/// Enable or disable GPU acceleration for audio processing
Future<void> enableGpu({required bool enable}) => throw UnimplementedError(
  'enableGpu is not implemented in the Kotlin backend.',
);

/// Render up to 60 seconds of audio to a WAV file
Future<void> renderSampleWav({
  required String trackJson,
  required String outPath,
}) => throw UnimplementedError(
  'renderSampleWav is not implemented in the Kotlin backend.',
);

/// Render the complete audio track to a WAV file
Future<void> renderFullWav({
  required String trackJson,
  required String outPath,
}) => throw UnimplementedError(
  'renderFullWav is not implemented in the Kotlin backend.',
);

/// Set the master output gain (volume)
/// Alias for set_volume to match Python API naming
Future<void> setMasterGain({required double gain}) =>
    _channel.invokeMethod('setMasterGain', {'gain': gain});

/// Get the current playback status
Future<bool> isAudioPlaying() async {
  final isPlaying = await _channel.invokeMethod<bool>('isAudioPlaying');
  return isPlaying ?? false;
}

/// Get the current sample rate of the active session
Future<int?> getSampleRate() => _channel.invokeMethod<int>('getSampleRate');

/// Generate waveform data for visualization
/// Returns amplitude values (0.0 to 1.0) sampled at regular intervals
/// for the given duration in seconds
Future<Float32List> generateWaveformSnippet({required double durationSec}) =>
    throw UnimplementedError(
      'generateWaveformSnippet is not implemented in the Kotlin backend.',
    );

/// Generate waveform data from a track JSON configuration
/// This creates waveform visualization based on the step structure
Future<Float32List> generateTrackWaveform({
  required String trackJson,
  required int samplesPerSecond,
}) => throw UnimplementedError(
  'generateTrackWaveform is not implemented in the Kotlin backend.',
);

/// Get the current playback position in seconds
/// Returns None if no audio session is active
Future<double?> getPlaybackPosition() async =>
    _channel.invokeMethod<double>('getPlaybackPosition');

/// Get the number of elapsed samples since playback started
/// Returns None if no audio session is active
Future<BigInt?> getElapsedSamples() async {
  final value = await _channel.invokeMethod<int>('getElapsedSamples');
  return value == null ? null : BigInt.from(value);
}

/// Get the current step index (0-based)
/// Returns None if no audio session is active
Future<BigInt?> getCurrentStep() async {
  final value = await _channel.invokeMethod<int>('getCurrentStep');
  return value == null ? null : BigInt.from(value);
}

/// Check if playback is currently paused
/// Returns None if no audio session is active
Future<bool?> getIsPaused() => _channel.invokeMethod<bool>('getIsPaused');

/// Get complete playback status as a struct
/// Returns position in seconds, current step index, and paused state
/// Returns None if no audio session is active
Future<PlaybackStatus?> getPlaybackStatus() async {
  final data = await _channel.invokeMethod<Map<dynamic, dynamic>>(
    'getPlaybackStatus',
  );
  if (data == null) {
    return null;
  }
  return PlaybackStatus(
    positionSeconds: (data['positionSeconds'] as num?)?.toDouble() ?? 0.0,
    currentStep: BigInt.from((data['currentStep'] as num?)?.toInt() ?? 0),
    isPaused: (data['isPaused'] as bool?) ?? false,
    sampleRate: (data['sampleRate'] as num?)?.toInt() ?? 0,
  );
}

/// Playback status information returned by get_playback_status
class PlaybackStatus {
  /// Current playback position in seconds
  final double positionSeconds;

  /// Current step index (0-based)
  final BigInt currentStep;

  /// Whether playback is paused
  final bool isPaused;

  /// Sample rate of the audio session
  final int sampleRate;

  const PlaybackStatus({
    required this.positionSeconds,
    required this.currentStep,
    required this.isPaused,
    required this.sampleRate,
  });

  @override
  int get hashCode =>
      positionSeconds.hashCode ^
      currentStep.hashCode ^
      isPaused.hashCode ^
      sampleRate.hashCode;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaybackStatus &&
          runtimeType == other.runtimeType &&
          positionSeconds == other.positionSeconds &&
          currentStep == other.currentStep &&
          isPaused == other.isPaused &&
          sampleRate == other.sampleRate;
}
