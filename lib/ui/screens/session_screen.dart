import 'dart:async';
import 'package:flutter/material.dart';
import 'package:session_builder_mobile/logic/audio_helpers.dart';
import 'package:session_builder_mobile/services/audio_session_service.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/logic/state/playback_provider.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';

class SessionScreen extends ConsumerStatefulWidget {
  const SessionScreen({super.key});

  @override
  ConsumerState<SessionScreen> createState() => _SessionScreenState();
}

class _SessionScreenState extends State<SessionScreen> {
  int _activeStepIndex = 0;
  bool _isPlaying = false;
  double _progressValue = 0.0; // 0.0 to 1.0
  double _volumeValue = 0.5;
  bool _hasStarted = false;
  double _currentPositionSeconds = 0.0;
  late double _totalDurationSeconds;
  Timer? _positionTimer;
  bool _isSeeking = false;

  @override
  void initState() {
    super.initState();
    _totalDurationSeconds = _calculateTotalDuration();
    // Auto-start session
    _startSessionPlayback();
  }

  @override
  void dispose() {
    _positionTimer?.cancel();
    stopAudioSession();
    // Deactivate audio session when leaving playback screen
    AudioSessionService.instance.deactivate();
    super.dispose();
  }

  /// Calculate total session duration from all steps
  double _calculateTotalDuration() {
    double total = 0.0;
    for (final step in widget.steps) {
      final durationStr = step['duration'].toString().replaceAll('s', '');
      final duration = double.tryParse(durationStr) ?? 0.0;
      total += duration;
    }
    return total;
  }

  /// Start polling playback position
  void _startPositionPolling() {
    _positionTimer?.cancel();
    _positionTimer = Timer.periodic(const Duration(milliseconds: 250), (_) async {
      if (!_isSeeking) {
        await _updatePlaybackPosition();
      }
    });
  }

  /// Stop polling playback position
  void _stopPositionPolling() {
    _positionTimer?.cancel();
    _positionTimer = null;
  }

  /// Update UI with current playback position from Rust backend
  Future<void> _updatePlaybackPosition() async {
    try {
      final position = await getPlaybackPosition();
      final currentStep = await getCurrentStep();

      if (position != null && mounted) {
        setState(() {
          _currentPositionSeconds = position;
          if (_totalDurationSeconds > 0) {
            _progressValue = (position / _totalDurationSeconds).clamp(0.0, 1.0);
          }
          if (currentStep != null) {
            _activeStepIndex = currentStep.toInt().clamp(0, widget.steps.length - 1);
          }
        });
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

  /// Handle slider seek
  Future<void> _handleSeek(double value) async {
    final seekPosition = value * _totalDurationSeconds;
    try {
      await startFrom(position: seekPosition);
      setState(() {
        _currentPositionSeconds = seekPosition;
        _progressValue = value;
        // Update active step based on seek position
        _activeStepIndex = _getStepIndexForPosition(seekPosition);
      });
    } catch (e) {
      debugPrint("Error seeking: $e");
    }
  }

  /// Get step index for a given position in seconds
  int _getStepIndexForPosition(double positionSeconds) {
    double accumulated = 0.0;
    for (int i = 0; i < widget.steps.length; i++) {
      final durationStr = widget.steps[i]['duration'].toString().replaceAll('s', '');
      final duration = double.tryParse(durationStr) ?? 0.0;
      accumulated += duration;
      if (positionSeconds < accumulated) {
        return i;
      }
    }
    return widget.steps.length - 1;
  }

  Future<void> _startSessionPlayback() async {
    try {
      // Activate audio session for background playback
      await AudioSessionService.instance.activate();

      final trackJson = AudioHelpers.generateTrackJson(steps: widget.steps);
      await startAudioSession(trackJson: trackJson, startTime: 0.0);
      setState(() {
        _isPlaying = true;
        _hasStarted = true;
      });
      _startPositionPolling();
    } catch (e) {
      debugPrint("Error starting session: $e");
    }
  }

  Future<void> _togglePlayPause() async {
    try {
      if (_isPlaying) {
        await pauseAudio();
        _stopPositionPolling();
        setState(() => _isPlaying = false);
      } else {
        if (!_hasStarted) {
          await _startSessionPlayback();
        } else {
          await resumeAudio();
          _startPositionPolling();
          setState(() => _isPlaying = true);
        }
      }
    } catch (e) {
      debugPrint("Error toggling playback: $e");
    }
  }

  /// Calculate the start time in seconds for a given step index
  double _getStepStartTime(int stepIndex) {
    double startTime = 0.0;
    for (int i = 0; i < stepIndex && i < widget.steps.length; i++) {
      final durationStr =
          widget.steps[i]['duration'].toString().replaceAll('s', '');
      final duration = double.tryParse(durationStr) ?? 0.0;
      startTime += duration;
    }
    return startTime;
  }

  Future<void> _skipToPreviousStep() async {
    if (_activeStepIndex > 0) {
      final newIndex = _activeStepIndex - 1;
      final startTime = _getStepStartTime(newIndex);

      try {
        startFrom(position: startTime);
        setState(() => _activeStepIndex = newIndex);
      } catch (e) {
        debugPrint("Error skipping to previous step: $e");
      }
    }
  }

  Future<void> _skipToNextStep() async {
    if (_activeStepIndex < widget.steps.length - 1) {
      final newIndex = _activeStepIndex + 1;
      final startTime = _getStepStartTime(newIndex);

      try {
        startFrom(position: startTime);
        setState(() => _activeStepIndex = newIndex);
      } catch (e) {
        debugPrint("Error skipping to next step: $e");
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final editorState = ref.watch(sessionEditorProvider);
    final playbackState = ref.watch(playbackProvider);
    final playbackNotifier = ref.read(playbackProvider.notifier);

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
                                  color:
                                      isActive ? Colors.white : Colors.white70,
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
                        _formatDuration(_currentPositionSeconds),
                        style: const TextStyle(color: Colors.white54, fontSize: 12),
                      ),
                      Text(
                        _formatDuration(_totalDurationSeconds),
                        style: const TextStyle(color: Colors.white54, fontSize: 12),
                      ),
                    ],
                  ),
                  Slider(
                    value: _progressValue,
                    onChangeStart: (_) {
                      _isSeeking = true;
                    },
                    onChanged: (v) => setState(() => _progressValue = v),
                    onChangeEnd: (v) async {
                      await _handleSeek(v);
                      _isSeeking = false;
                    },
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
                        onPressed: playbackNotifier.togglePlayPause,
                      ),
                      const SizedBox(width: 20),
                      IconButton(
                        icon: Icon(
                          Icons.skip_next,
                          color: playbackState.activeStepIndex <
                                  editorState.steps.length - 1
                              ? Colors.white
                              : Colors.white38,
                        ),
                        onPressed: playbackState.activeStepIndex <
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
