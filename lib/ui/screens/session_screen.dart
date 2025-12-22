import 'package:flutter/material.dart';
import 'package:session_builder_mobile/logic/audio_helpers.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart';

class SessionScreen extends StatefulWidget {
  final List<Map<String, dynamic>> steps;

  const SessionScreen({super.key, required this.steps});

  @override
  State<SessionScreen> createState() => _SessionScreenState();
}

class _SessionScreenState extends State<SessionScreen> {
  int _activeStepIndex = 0;
  bool _isPlaying = false;
  double _progressValue = 0.0; // 0.0 to 1.0
  double _volumeValue = 0.5;
  bool _hasStarted = false;

  @override
  void initState() {
    super.initState();
    // Auto-start session
    _startSessionPlayback();
  }

  @override
  void dispose() {
    stopAudioSession();
    super.dispose();
  }

  Future<void> _startSessionPlayback() async {
    try {
      final trackJson = AudioHelpers.generateTrackJson(steps: widget.steps);
      await startAudioSession(trackJson: trackJson, startTime: 0.0);
      setState(() {
        _isPlaying = true;
        _hasStarted = true;
      });
    } catch (e) {
      debugPrint("Error starting session: $e");
    }
  }

  Future<void> _togglePlayPause() async {
    try {
      if (_isPlaying) {
        await pauseAudio();
        setState(() => _isPlaying = false);
      } else {
        if (!_hasStarted) {
          await _startSessionPlayback();
        } else {
          await resumeAudio();
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
                itemCount: widget.steps.length,
                separatorBuilder: (ctx, i) => const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final step = widget.steps[index];
                  final isActive = index == _activeStepIndex;
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
                    children: const [
                      Text(
                        "00:00",
                        style: TextStyle(color: Colors.white54, fontSize: 12),
                      ),
                      Text(
                        "1:00:00",
                        style: TextStyle(color: Colors.white54, fontSize: 12),
                      ),
                    ],
                  ),
                  Slider(
                    value: _progressValue,
                    onChanged: (v) => setState(() => _progressValue = v),
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
                          color: _activeStepIndex > 0
                              ? Colors.white
                              : Colors.white38,
                        ),
                        onPressed:
                            _activeStepIndex > 0 ? _skipToPreviousStep : null,
                      ),
                      const SizedBox(width: 20),
                      IconButton(
                        iconSize: 48,
                        icon: Icon(
                          _isPlaying ? Icons.pause : Icons.play_arrow,
                          color: Colors.white,
                        ),
                        onPressed: _togglePlayPause,
                      ),
                      const SizedBox(width: 20),
                      IconButton(
                        icon: Icon(
                          Icons.skip_next,
                          color: _activeStepIndex < widget.steps.length - 1
                              ? Colors.white
                              : Colors.white38,
                        ),
                        onPressed: _activeStepIndex < widget.steps.length - 1
                            ? _skipToNextStep
                            : null,
                      ),
                    ],
                  ),

                  const SizedBox(height: 20),

                  // Volume Control
                  Column(
                    children: [
                      Slider(
                        value: _volumeValue,
                        onChanged: (v) {
                          setState(() => _volumeValue = v);
                          setVolume(volume: v);
                        },
                        activeColor: Colors.white,
                        inactiveColor: Colors.white24,
                      ),
                      Text(
                        _volumeValue.toStringAsFixed(1),
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
