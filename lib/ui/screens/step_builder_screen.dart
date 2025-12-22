import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/data/presets_repository.dart';
import 'package:session_builder_mobile/logic/audio_helpers.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart';

class StepBuilderScreen extends ConsumerStatefulWidget {
  const StepBuilderScreen({super.key});

  @override
  ConsumerState<StepBuilderScreen> createState() => _StepBuilderScreenState();
}

class _StepBuilderScreenState extends ConsumerState<StepBuilderScreen> {
  // State variables matches wireframe
  String _binauralPreset = "F10-B";
  double _binauralVolume = 0.5;

  String _noisePreset = "Brown Noise";
  double _noiseVolume = 0.5;

  String? _backgroundTrack; // Null means nothing loaded
  double _backgroundVolume = 0.5;
  bool _backgroundExtend = false;

  final TextEditingController _durationController = TextEditingController(
    text: "300",
  );

  bool _isTestPlaying = false;

  @override
  void dispose() {
    // Stop any test playback when leaving the screen
    if (_isTestPlaying) {
      stopAudioSession();
    }
    _durationController.dispose();
    super.dispose();
  }

  Future<void> _showPresetDialog(
    String title,
    List<String> items,
    Function(String) onSelected,
  ) async {
    await showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title, style: const TextStyle(color: Colors.white)),
        backgroundColor: Colors.grey[900],
        content: SizedBox(
          width: double.maxFinite,
          height: 300,
          child: ListView.builder(
            itemCount: items.length,
            itemBuilder: (context, index) {
              final item = items[index];
              return ListTile(
                title: Text(item, style: const TextStyle(color: Colors.white)),
                onTap: () {
                  onSelected(item);
                  Navigator.of(context).pop();
                },
              );
            },
          ),
        ),
      ),
    );
  }

  void _selectBinauralPreset() async {
    final presets = PresetsRepository().getBinauralPresetNames();
    _showPresetDialog("Select Binaural Preset", presets, (val) {
      setState(() => _binauralPreset = val);
    });
  }

  void _selectNoisePreset() async {
    final presets = PresetsRepository().getNoisePresetNames();
    _showPresetDialog("Select Noise Preset", presets, (val) {
      setState(() => _noisePreset = val);
    });
  }

  void _loadBackground() async {
    // TODO: Integrate with file picker when available
    // For now, simulating a loaded track
    setState(() => _backgroundTrack = "rain_sounds.mp3");
  }

  void _clearBackground() {
    setState(() => _backgroundTrack = null);
  }

  Future<void> _toggleTest() async {
    if (_isTestPlaying) {
      // Stop the test playback
      await stopAudioSession();
      setState(() => _isTestPlaying = false);
    } else {
      // Build a test step from current settings
      final testStep = {
        "binaural": _binauralPreset,
        "binaural_volume": _binauralVolume,
        "noise": _noisePreset,
        "noise_volume": _noiseVolume,
        "track": _backgroundTrack ?? "None",
        "track_volume": _backgroundVolume,
        "duration": "30s", // Short test duration
      };

      try {
        final trackJson = AudioHelpers.generateTrackJson(steps: [testStep]);
        await startAudioSession(trackJson: trackJson, startTime: 0.0);
        setState(() => _isTestPlaying = true);
      } catch (e) {
        debugPrint("Error starting test playback: $e");
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("Error starting test: $e")),
          );
        }
      }
    }
  }

  void _addStep() {
    final newStep = {
      "binaural": _binauralPreset,
      "binaural_volume": _binauralVolume,
      "noise": _noisePreset,
      "noise_volume": _noiseVolume,
      "track": _backgroundTrack ?? "None",
      "track_volume": _backgroundVolume,
      "duration": "${int.tryParse(_durationController.text) ?? 300}s",
    };
    ref.read(sessionEditorProvider.notifier).addStep(newStep);
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    const labelStyle = TextStyle(color: Colors.white70, fontSize: 14);
    const valueStyle = TextStyle(color: Colors.white, fontSize: 12);

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const Text(
          "Step Builder",
          style: TextStyle(color: Colors.white),
        ),
        backgroundColor: Colors.black,
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: Column(
                  children: [
                    // --- Binaural Section ---
                    const Text("Binaural", style: labelStyle),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          flex: 2,
                          child: OutlinedButton(
                            onPressed: _selectBinauralPreset,
                            style: _controlButtonStyle(),
                            child: Text(
                              _binauralPreset,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(color: Colors.white),
                            ),
                          ),
                        ),
                        Expanded(
                          flex: 3,
                          child: Column(
                            children: [
                              Slider(
                                value: _binauralVolume,
                                onChanged: (v) =>
                                    setState(() => _binauralVolume = v),
                                activeColor: Colors.white,
                                inactiveColor: Colors.white24,
                              ),
                              Text(
                                _binauralVolume.toStringAsFixed(1),
                                style: valueStyle,
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 30),

                    // --- Noise Section ---
                    const Text("Noise", style: labelStyle),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          flex: 2,
                          child: OutlinedButton(
                            onPressed: _selectNoisePreset,
                            style: _controlButtonStyle(),
                            child: Text(
                              _noisePreset,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(color: Colors.white),
                            ),
                          ),
                        ),
                        Expanded(
                          flex: 3,
                          child: Column(
                            children: [
                              Slider(
                                value: _noiseVolume,
                                onChanged: (v) =>
                                    setState(() => _noiseVolume = v),
                                activeColor: Colors.white,
                                inactiveColor: Colors.white24,
                              ),
                              Text(
                                _noiseVolume.toStringAsFixed(1),
                                style: valueStyle,
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 30),

                    // --- Background Section ---
                    const Text("Background", style: labelStyle),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          flex: 2,
                          child: Column(
                            children: [
                              SizedBox(
                                width: double.infinity,
                                child: OutlinedButton(
                                  onPressed: _loadBackground,
                                  style: _controlButtonStyle(),
                                  child: const Text(
                                    "Load",
                                    style: TextStyle(color: Colors.white),
                                  ),
                                ),
                              ),
                              const SizedBox(height: 8),
                              SizedBox(
                                width: double.infinity,
                                child: OutlinedButton(
                                  onPressed: _clearBackground,
                                  style: _controlButtonStyle(),
                                  child: const Text(
                                    "Clear",
                                    style: TextStyle(color: Colors.white),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        Expanded(
                          flex: 3,
                          child: Column(
                            children: [
                              if (_backgroundTrack != null)
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 5),
                                  child: Text(
                                    _backgroundTrack!,
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 10,
                                    ),
                                    textAlign: TextAlign.center,
                                  ),
                                )
                              else
                                const Padding(
                                  padding: EdgeInsets.only(bottom: 5),
                                  child: Text(
                                    "No track loaded",
                                    style: TextStyle(
                                      color: Colors.white38,
                                      fontSize: 10,
                                    ),
                                  ),
                                ),
                              Slider(
                                value: _backgroundVolume,
                                onChanged: (v) =>
                                    setState(() => _backgroundVolume = v),
                                activeColor: Colors.white,
                                inactiveColor: Colors.white24,
                              ),
                              Text(
                                _backgroundVolume.toStringAsFixed(1),
                                style: valueStyle,
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Row(
                      mainAxisAlignment: MainAxisAlignment
                          .start, // Align leftish as wireframe seems centered/left
                      children: [
                        // Hacky way to align it under the buttons
                        const SizedBox(width: 20),
                        Column(
                          children: [
                            Checkbox(
                              value: _backgroundExtend,
                              onChanged: (v) => setState(
                                () => _backgroundExtend = v ?? false,
                              ),
                              side: const BorderSide(color: Colors.white),
                              activeColor: Colors.white,
                              checkColor: Colors.black,
                            ),
                            const Text("Extend?", style: labelStyle),
                          ],
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),

            // --- Bottom Footer ---
            Container(
              padding: const EdgeInsets.all(20),
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: Colors.white24)),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  // Duration and Add Step
                  Expanded(
                    flex: 1,
                    child: Column(
                      children: [
                        SizedBox(
                          width: 100,
                          height: 50,
                          child: TextField(
                            controller: _durationController,
                            keyboardType: TextInputType.number,
                            style: const TextStyle(color: Colors.white),
                            textAlign: TextAlign.center,
                            decoration: InputDecoration(
                              suffixText: "s",
                              suffixStyle: const TextStyle(
                                color: Colors.white70,
                              ),
                              enabledBorder: OutlineInputBorder(
                                borderRadius: BorderRadius.circular(10),
                                borderSide: const BorderSide(
                                  color: Colors.white,
                                ),
                              ),
                              focusedBorder: OutlineInputBorder(
                                borderRadius: BorderRadius.circular(10),
                                borderSide: const BorderSide(
                                  color: Colors.white,
                                  width: 2,
                                ),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 15),
                        SizedBox(
                          width: 120,
                          height: 50,
                          child: OutlinedButton(
                            onPressed: _addStep,
                            style: OutlinedButton.styleFrom(
                              side: const BorderSide(color: Colors.white),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                              foregroundColor: Colors.white,
                            ),
                            child: const Text("Add Step"),
                          ),
                        ),
                      ],
                    ),
                  ),

                  // Test Controls
                  Expanded(
                    flex: 1,
                    child: Column(
                      children: [
                        const Text(
                          "Test",
                          style: TextStyle(color: Colors.white, fontSize: 18),
                        ),
                        IconButton(
                          iconSize: 48,
                          color: Colors.white,
                          icon: Icon(
                            _isTestPlaying ? Icons.stop : Icons.play_arrow,
                          ),
                          onPressed: _toggleTest,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  ButtonStyle _controlButtonStyle() {
    return OutlinedButton.styleFrom(
      side: const BorderSide(color: Colors.white),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
    );
  }
}
