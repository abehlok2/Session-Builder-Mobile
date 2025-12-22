import 'package:flutter/material.dart';
import 'package:session_builder_mobile/ui/screens/step_builder_screen.dart';
import 'package:session_builder_mobile/ui/screens/session_screen.dart';

class StepListScreen extends StatefulWidget {
  final List<Map<String, dynamic>>? initialSteps;

  const StepListScreen({super.key, this.initialSteps});

  @override
  State<StepListScreen> createState() => _StepListScreenState();
}

class _StepListScreenState extends State<StepListScreen> {
  late List<Map<String, dynamic>> _steps;

  @override
  void initState() {
    super.initState();
    _steps = widget.initialSteps != null
        ? List.from(widget.initialSteps!)
        : [
            // Default/Mock data if nothing passed
            {
              "binaural": "100Hz + 4Hz (Theta)",
              "noise": "Pink Noise",
              "track": "None",
              "duration": "5:00",
            },
            {
              "binaural": "200Hz + 8Hz (Alpha)",
              "noise": "Brown Noise",
              "track": "Rain Sounds",
              "duration": "10:00",
            },
          ];
  }

  int _crossfadeSeconds = 3;
  int? _selectedStepIndex;

  void _addStep() async {
    final result = await Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => const StepBuilderScreen()));

    if (result != null && result is Map<String, dynamic>) {
      setState(() {
        _steps.add(result);
      });
    }
  }

  void _removeStep() {
    if (_selectedStepIndex != null) {
      setState(() {
        _steps.removeAt(_selectedStepIndex!);
        _selectedStepIndex = null;
      });
    }
  }

  void _saveSession() {
    debugPrint("Save Session pressed");
  }

  void _startSession() {
    Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => SessionScreen(steps: _steps)));
  }

  void _exportSession() {
    debugPrint("Export Session pressed");
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
        title: const Text("Step List", style: TextStyle(color: Colors.white)),
        backgroundColor: Colors.black,
        actions: [
          TextButton(
            onPressed: _exportSession,
            child: const Text("Export", style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // List of Steps
            Expanded(
              child: ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: _steps.length,
                separatorBuilder: (ctx, i) => const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final step = _steps[index];
                  final isSelected = _selectedStepIndex == index;
                  return GestureDetector(
                    onTap: () => setState(() => _selectedStepIndex = index),
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: isSelected ? Colors.white10 : Colors.transparent,
                        border: Border.all(
                          color: isSelected ? Colors.white : Colors.white54,
                          width: isSelected ? 2 : 1,
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
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontSize: 14,
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
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 14,
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),

            // Bottom Panel
            Container(
              padding: const EdgeInsets.all(16),
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: Colors.white24)),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Crossfade
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Text(
                        "Crossfade",
                        style: TextStyle(color: Colors.white),
                      ),
                      const SizedBox(width: 10),
                      InkWell(
                        onTap: () {
                          // Simple cycler for demo
                          setState(() {
                            _crossfadeSeconds = (_crossfadeSeconds % 30) + 1;
                          });
                        },
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 8,
                          ),
                          decoration: BoxDecoration(
                            border: Border.all(color: Colors.white),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Text(
                            "${_crossfadeSeconds}s",
                            style: const TextStyle(color: Colors.white),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),

                  // Action Buttons
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _buildActionButton("Add", _addStep),
                      _buildActionButton("Save", _saveSession),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _buildActionButton("Remove", _removeStep),
                      _buildActionButton("Start", _startSession),
                    ],
                  ),

                  const SizedBox(height: 15),
                  const Text(
                    "00:15:00", // Mock total duration
                    style: TextStyle(color: Colors.white, fontSize: 14),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionButton(String label, VoidCallback onPressed) {
    return SizedBox(
      width: 120, // approximate width
      child: OutlinedButton(
        onPressed: onPressed,
        style: OutlinedButton.styleFrom(
          side: const BorderSide(color: Colors.white),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
          foregroundColor: Colors.white,
        ),
        child: Text(label),
      ),
    );
  }
}
