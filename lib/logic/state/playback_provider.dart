import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/logic/audio_helpers.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart';

abstract class AudioController {
  Future<void> start(String trackJson);
  Future<void> pause();
  Future<void> resume();
  Future<void> startFromPosition(double position);
  Future<void> setVolume(double volume);
  Future<void> stop();
}

class DefaultAudioController implements AudioController {
  @override
  Future<void> pause() => pauseAudio();

  @override
  Future<void> resume() => resumeAudio();

  @override
  Future<void> setVolume(double volume) => setVolume(volume: volume);

  @override
  Future<void> start(String trackJson) =>
      startAudioSession(trackJson: trackJson, startTime: 0.0);

  @override
  Future<void> startFromPosition(double position) =>
      startFrom(position: position);

  @override
  Future<void> stop() => stopAudioSession();
}

final audioControllerProvider = Provider<AudioController>((ref) {
  return DefaultAudioController();
});

class PlaybackState {
  final int activeStepIndex;
  final bool isPlaying;
  final double progress; // 0-1 slider value
  final double volume;

  const PlaybackState({
    this.activeStepIndex = 0,
    this.isPlaying = false,
    this.progress = 0.0,
    this.volume = 0.5,
  });

  PlaybackState copyWith({
    int? activeStepIndex,
    bool? isPlaying,
    double? progress,
    double? volume,
  }) {
    return PlaybackState(
      activeStepIndex: activeStepIndex ?? this.activeStepIndex,
      isPlaying: isPlaying ?? this.isPlaying,
      progress: progress ?? this.progress,
      volume: volume ?? this.volume,
    );
  }
}

class PlaybackNotifier extends Notifier<PlaybackState> {
  late final AudioController _audioController =
      ref.read(audioControllerProvider);

  @override
  PlaybackState build() => const PlaybackState();

  Future<void> start(List<Map<String, dynamic>> steps) async {
    if (steps.isEmpty) return;
    try {
      final trackJson = AudioHelpers.generateTrackJson(steps: steps);
      await _audioController.start(trackJson);
      state = state.copyWith(isPlaying: true, activeStepIndex: 0, progress: 0);
    } catch (e) {
      debugPrint('Error starting playback: $e');
    }
  }

  Future<void> startFromEditor(SessionEditorState editorState) async {
    await start(editorState.steps);
  }

  Future<void> togglePlayPause() async {
    if (state.isPlaying) {
      await _audioController.pause();
      state = state.copyWith(isPlaying: false);
    } else {
      await _audioController.resume();
      state = state.copyWith(isPlaying: true);
    }
  }

  Future<void> skipToStep(int index, List<Map<String, dynamic>> steps) async {
    if (index < 0 || index >= steps.length) return;
    double startTime = 0;
    for (int i = 0; i < index && i < steps.length; i++) {
      final durationStr = steps[i]['duration'].toString().replaceAll('s', '');
      startTime += double.tryParse(durationStr) ?? 0;
    }
    await _audioController.startFromPosition(startTime);
    state = state.copyWith(activeStepIndex: index);
  }

  Future<void> setVolumeLevel(double volume) async {
    state = state.copyWith(volume: volume);
    await _audioController.setVolume(volume);
  }

  Future<void> stop() async {
    await _audioController.stop();
    state = const PlaybackState();
  }

  void setProgress(double value) {
    state = state.copyWith(progress: value);
  }
}

final playbackProvider = NotifierProvider<PlaybackNotifier, PlaybackState>(
  PlaybackNotifier.new,
);
