import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/logic/audio_helpers.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:session_builder_mobile/services/audio_session_service.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart' hide setVolume;
import 'package:session_builder_mobile/src/rust/mobile_api.dart'
    as mobile_api
    show setVolume;

abstract class AudioController {
  Future<void> start(String trackJson);
  Future<void> pause();
  Future<void> resume();
  Future<void> startFromPosition(double position);
  Future<void> setVolume(double volume);
  Future<void> stop();
  Future<PlaybackStatus?> getStatus();
}

class DefaultAudioController implements AudioController {
  @override
  Future<void> pause() => pauseAudio();

  @override
  Future<void> resume() => resumeAudio();

  @override
  Future<void> setVolume(double volume) => mobile_api.setVolume(volume: volume);

  @override
  Future<void> start(String trackJson) =>
      startAudioSession(trackJson: trackJson, startTime: 0.0);

  @override
  Future<void> startFromPosition(double position) =>
      startFrom(position: position);

  @override
  Future<void> stop() => stopAudioSession();

  @override
  Future<PlaybackStatus?> getStatus() => getPlaybackStatus(); // Added
}

final audioControllerProvider = Provider<AudioController>((ref) {
  return DefaultAudioController();
});

class PlaybackState {
  final int activeStepIndex;
  final bool isPlaying;
  final double progress; // 0-1 slider value
  final double volume;
  final double positionSeconds;
  final double totalDurationSeconds;
  final bool hasStarted;

  const PlaybackState({
    this.activeStepIndex = 0,
    this.isPlaying = false,
    this.progress = 0.0,
    this.volume = 0.5,
    this.positionSeconds = 0.0,
    this.totalDurationSeconds = 0.0,
    this.hasStarted = false,
  });

  PlaybackState copyWith({
    int? activeStepIndex,
    bool? isPlaying,
    double? progress,
    double? volume,
    double? positionSeconds,
    double? totalDurationSeconds,
    bool? hasStarted,
  }) {
    return PlaybackState(
      activeStepIndex: activeStepIndex ?? this.activeStepIndex,
      isPlaying: isPlaying ?? this.isPlaying,
      progress: progress ?? this.progress,
      volume: volume ?? this.volume,
      positionSeconds: positionSeconds ?? this.positionSeconds,
      totalDurationSeconds: totalDurationSeconds ?? this.totalDurationSeconds,
      hasStarted: hasStarted ?? this.hasStarted,
    );
  }
}

class PlaybackNotifier extends Notifier<PlaybackState> {
  late final AudioController _audioController = ref.read(
    audioControllerProvider,
  );
  Timer? _pollingTimer; // Added

  @override
  PlaybackState build() {
    ref.onDispose(() {
      _stopPolling();
    });
    return const PlaybackState();
  }

  double _calculateTotalDuration(List<Map<String, dynamic>> steps) {
    double total = 0;
    for (final step in steps) {
      final durationStr = step['duration'].toString().replaceAll('s', '');
      total += double.tryParse(durationStr) ?? 0;
    }
    return total;
  }

  void startPolling() {
    _pollingTimer?.cancel();
    _pollingTimer = Timer.periodic(const Duration(milliseconds: 100), (_) {
      _errorMessageSafeUpdate();
    });
  }

  void _stopPolling() {
    _pollingTimer?.cancel();
    _pollingTimer = null;
  }

  Future<void> _errorMessageSafeUpdate() async {
    try {
      final status = await _audioController.getStatus();
      if (status != null) {
        final totalDuration = state.totalDurationSeconds;
        final progress = totalDuration > 0
            ? (status.positionSeconds / totalDuration).clamp(0.0, 1.0)
            : 0.0;

        state = state.copyWith(
          isPlaying: !status.isPaused,
          positionSeconds: status.positionSeconds,
          activeStepIndex: status.currentStep.toInt(),
          progress: progress,
          hasStarted: true,
        );
      }
    } catch (e) {
      debugPrint('Error polling playback status: $e');
    }
  }

  Future<void> start(List<Map<String, dynamic>> steps) async {
    if (steps.isEmpty) return;
    try {
      await AudioSessionService.instance.activate();
      final trackJson = AudioHelpers.generateTrackJson(steps: steps);
      await _audioController.start(trackJson);
      final totalDuration = _calculateTotalDuration(steps);
      state = state.copyWith(
        isPlaying: true,
        hasStarted: true,
        activeStepIndex: 0,
        progress: 0,
        positionSeconds: 0,
        totalDurationSeconds: totalDuration,
      );
      startPolling(); // Added
    } catch (e) {
      debugPrint('Error starting playback: $e');
    }
  }

  Future<void> startFromEditor(SessionEditorState editorState) async {
    await start(editorState.steps);
  }

  Future<void> togglePlayPause({List<Map<String, dynamic>>? steps}) async {
    if (state.isPlaying) {
      await _audioController.pause();
      // State updated by polling, but optimistic update feels better
      state = state.copyWith(isPlaying: false);
    } else {
      if (!state.hasStarted) {
        if (steps == null || steps.isEmpty) return;
        await start(steps);
        return;
      }
      await _audioController.resume();
      state = state.copyWith(isPlaying: true);
      startPolling(); // Ensure polling is active
    }
  }

  Future<void> skipToStep(int index, List<Map<String, dynamic>> steps) async {
    if (index < 0 || index >= steps.length) return;
    final startTime = _calculateStartTimeForStep(index, steps);
    await _audioController.startFromPosition(startTime);
    // State will be updated by polling
    startPolling(); // Added
  }

  Future<void> setVolumeLevel(double volume) async {
    state = state.copyWith(volume: volume);
    await _audioController.setVolume(volume);
  }

  Future<void> stop() async {
    _stopPolling(); // Added
    await _audioController.stop();
    state = const PlaybackState();
  }

  void setProgress(double value) {
    // Only update UI state for dragging, don't seek yet
    state = state.copyWith(progress: value);
  }

  // Removed syncPlaybackPosition as polling handles this

  Future<void> seekToPosition(
    double positionSeconds,
    List<Map<String, dynamic>> steps,
  ) async {
    await _audioController.startFromPosition(positionSeconds);
    // Polling handles the rest
    startPolling(); // Added
  }

  // Removed _getStepIndexForPosition as PlaybackStatus provides currentStep

  Future<void> seekToProgress(
    double progress,
    List<Map<String, dynamic>> steps,
  ) async {
    final target = state.totalDurationSeconds > 0
        ? state.totalDurationSeconds * progress
        : 0.0;
    await seekToPosition(target, steps);
  }

  void ensureDurationFromSteps(List<Map<String, dynamic>> steps) {
    if (state.totalDurationSeconds == 0) {
      state = state.copyWith(
        totalDurationSeconds: _calculateTotalDuration(steps),
      );
    }
  }

  double _calculateStartTimeForStep(
    int stepIndex,
    List<Map<String, dynamic>> steps,
  ) {
    double startTime = 0.0;
    for (int i = 0; i < stepIndex && i < steps.length; i++) {
      final durationStr = steps[i]['duration'].toString().replaceAll('s', '');
      startTime += double.tryParse(durationStr) ?? 0.0;
    }
    return startTime;
  }
}

final playbackProvider = NotifierProvider<PlaybackNotifier, PlaybackState>(
  PlaybackNotifier.new,
);
