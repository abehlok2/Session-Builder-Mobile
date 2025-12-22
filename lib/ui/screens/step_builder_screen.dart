import 'package:flutter/material.dart';

class StepBuilderScreen extends StatefulWidget {
  const StepBuilderScreen({super.key});

  @override
  State<StepBuilderScreen> createState() => _StepBuilderScreenState();
}

class _StepBuilderScreenState extends State<StepBuilderScreen> {
  // State variables matches wireframe
  String _binauralPreset = "F10-B";
  double _binauralVolume = 0.5;

  String _noisePreset = "Brown";
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
    _durationController.dispose();
    super.dispose();
  }

  void _selectBinauralPreset() async {
    // Mock selection
    setState(() => _binauralPreset = "F10-B (Alpha)");
  }

  void _selectNoisePreset() async {
    // Mock selection
    setState(() => _noisePreset = "Pink");
  }

  void _loadBackground() async {
    // Mock file loading
    setState(() => _backgroundTrack = "rain_sounds.mp3");
  }

  void _clearBackground() {
    setState(() => _backgroundTrack = null);
  }

  void _toggleTest() {
    setState(() => _isTestPlaying = !_isTestPlaying);
    // TODO: Implement actual audio streaming test
  }

  void _addStep() {
    // Mock return data
    final newStep = {
      "binaural": _binauralPreset,
      "noise": _noisePreset,
      "track": _backgroundTrack ?? "None",
      "duration": "${int.tryParse(_durationController.text) ?? 300}s",
    };
    Navigator.of(context).pop(newStep);
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
                                    "Loaded Track Name", // Placeholder as per wireframe
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
                            _isTestPlaying ? Icons.pause : Icons.pause,
                          ), // Wireframe shows pause bars, assuming pause icon fits better visually for "Test" active state or play for inactive. Wireframe has Pause icon (II)
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
