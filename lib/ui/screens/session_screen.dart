import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/logic/state/playback_provider.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:session_builder_mobile/services/audio_session_service.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart';

class SessionScreen extends ConsumerStatefulWidget {
  const SessionScreen({super.key});

  @override
  ConsumerState<SessionScreen> createState() => _SessionScreenState();
}

class _SessionScreenState extends ConsumerState<SessionScreen> {
  Timer? _positionTimer;

  @override
  void initState() {
    super.initState();
    _startPositionPolling();
  }

  @override
  void dispose() {
    _positionTimer?.cancel();
    ref.read(playbackProvider.notifier).stop();
    // Deactivate audio session when leaving playback screen
    AudioSessionService.instance.deactivate();
    super.dispose();
  }

  /// Start polling playback position
  void _startPositionPolling() {
    _positionTimer?.cancel();
    _positionTimer = Timer.periodic(const Duration(milliseconds: 250), (
      _,
    ) async {
      await _updatePlaybackPosition();
    });
  }

  /// Stop polling playback position
  void _stopPositionPolling() {
    _positionTimer?.cancel();
    _positionTimer = null;
  }

  /// Update UI with current playback position from Rust backend
  Future<void> _updatePlaybackPosition() async {
    final playbackNotifier = ref.read(playbackProvider.notifier);
    final stepsLength = ref.read(sessionEditorProvider).steps.length;
    try {
      final position = await getPlaybackPosition();
      final currentStep = await getCurrentStep();

      if (position != null && mounted) {
        final activeIndex = currentStep?.toInt();
        final clampedIndex = activeIndex != null && stepsLength > 0
            ? activeIndex.clamp(0, stepsLength - 1).toInt()
            : activeIndex;
        playbackNotifier.syncPlaybackPosition(
          positionSeconds: position,
          activeStepIndex: clampedIndex,
        );
      }
    } catch (e) {
      debugPrint("Error updating playback position: $e");
    }
  }

  /// Format seconds to MM:SS or H:MM:SS
  String _formatDuration(double seconds) {
    final duration = Duration(seconds: seconds.toInt());
    final hours = duration.inHours;
    final minutes = duration.inMinutes.remainder(60);
    final secs = duration.inSeconds.remainder(60);

    if (hours > 0) {
      return '$hours:${minutes.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
    } else {
      return '${minutes.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
    }
  }

  @override
  Widget build(BuildContext context) {
    final editorState = ref.watch(sessionEditorProvider);
    final playbackState = ref.watch(playbackProvider);
    final playbackNotifier = ref.read(playbackProvider.notifier);
    playbackNotifier.ensureDurationFromSteps(editorState.steps);
    final totalDurationSeconds = playbackState.totalDurationSeconds > 0
        ? playbackState.totalDurationSeconds
        : editorState.totalSeconds;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const Text("Session", style: TextStyle(color: Colors.white)),
        backgroundColor: Colors.black,
      ),
      body: SafeArea(
        child: Column(
          children: [
            // --- Step List Layer ---
            Expanded(
              child: ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: editorState.steps.length,
                separatorBuilder: (ctx, i) => const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final step = editorState.steps[index];
                  final isActive = index == playbackState.activeStepIndex;
                  return Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: isActive ? Colors.white10 : Colors.transparent,
                      border: Border.all(
                        color: isActive ? Colors.white : Colors.white30,
                        width: isActive ? 2 : 1,
                      ),
                      borderRadius: BorderRadius.circular(15),
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                "Binaural: ${step['binaural']}",
                                style: TextStyle(
                                  color: isActive
                                      ? Colors.white
                                      : Colors.white70,
                                  fontSize: 14,
                                  fontWeight: isActive
                                      ? FontWeight.bold
                                      : FontWeight.normal,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                "Noise: ${step['noise']}",
                                style: const TextStyle(
                                  color: Colors.white70,
                                  fontSize: 14,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                "Track: ${step['track']}",
                                style: const TextStyle(
                                  color: Colors.white70,
                                  fontSize: 14,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Text(
                          step['duration'],
                          style: TextStyle(
                            color: isActive ? Colors.white : Colors.white70,
                            fontSize: 14,
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
            ),

            // --- Bottom Controls ---
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: Colors.white24)),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Progress Row
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        _formatDuration(playbackState.positionSeconds),
                        style: const TextStyle(
                          color: Colors.white54,
                          fontSize: 12,
                        ),
                      ),
                      Text(
                        _formatDuration(totalDurationSeconds),
                        style: const TextStyle(
                          color: Colors.white54,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                  Slider(
                    value: playbackState.progress,
                    onChanged: (v) => playbackNotifier.setProgress(v),
                    onChangeEnd: (v) async =>
                        playbackNotifier.seekToProgress(v, editorState.steps),
                    activeColor: Colors.white,
                    inactiveColor: Colors.white24,
                  ),

                  const SizedBox(height: 10),

                  // Playback Buttons
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      IconButton(
                        icon: Icon(
                          Icons.skip_previous,
                          color: playbackState.activeStepIndex > 0
                              ? Colors.white
                              : Colors.white38,
                        ),
                        onPressed: playbackState.activeStepIndex > 0
                            ? () => playbackNotifier.skipToStep(
                                playbackState.activeStepIndex - 1,
                                editorState.steps,
                              )
                            : null,
                      ),
                      const SizedBox(width: 20),
                      IconButton(
                        iconSize: 48,
                        icon: Icon(
                          playbackState.isPlaying
                              ? Icons.pause
                              : Icons.play_arrow,
                          color: Colors.white,
                        ),
                        onPressed: () => playbackNotifier.togglePlayPause(
                          steps: editorState.steps,
                        ),
                      ),
                      const SizedBox(width: 20),
                      IconButton(
                        icon: Icon(
                          Icons.skip_next,
                          color:
                              playbackState.activeStepIndex <
                                  editorState.steps.length - 1
                              ? Colors.white
                              : Colors.white38,
                        ),
                        onPressed:
                            playbackState.activeStepIndex <
                                editorState.steps.length - 1
                            ? () => playbackNotifier.skipToStep(
                                playbackState.activeStepIndex + 1,
                                editorState.steps,
                              )
                            : null,
                      ),
                    ],
                  ),

                  const SizedBox(height: 20),

                  // Volume Control
                  Column(
                    children: [
                      Slider(
                        value: playbackState.volume,
                        onChanged: (v) => playbackNotifier.setVolumeLevel(v),
                        activeColor: Colors.white,
                        inactiveColor: Colors.white24,
                      ),
                      Text(
                        playbackState.volume.toStringAsFixed(1),
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(height: 5),
                      const Icon(
                        Icons.arrow_upward,
                        color: Colors.white54,
                        size: 16,
                      ),
                      const Text(
                        "Volume control",
                        style: TextStyle(color: Colors.white38, fontSize: 10),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
