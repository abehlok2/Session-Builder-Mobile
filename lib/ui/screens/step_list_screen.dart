import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import 'package:session_builder_mobile/data/session_storage.dart';
import 'package:session_builder_mobile/ui/screens/step_builder_screen.dart';
import 'package:session_builder_mobile/ui/screens/session_screen.dart';

class StepListScreen extends StatefulWidget {
  final List<Map<String, dynamic>>? initialSteps;
  final String? sessionId;
  final String? sessionName;

  const StepListScreen({
    super.key,
    this.initialSteps,
    this.sessionId,
    this.sessionName,
  });

  @override
  State<StepListScreen> createState() => _StepListScreenState();
}

class _StepListScreenState extends State<StepListScreen> {
  late List<Map<String, dynamic>> _steps;
  late String? _sessionId;
  late String _sessionName;
  final SessionStorage _storage = SessionStorage();

  @override
  void initState() {
    super.initState();
    _steps = widget.initialSteps != null ? List.from(widget.initialSteps!) : [];
    _sessionId = widget.sessionId;
    _sessionName = widget.sessionName ?? 'Untitled Session';
  }

  int _crossfadeSeconds = 3;
  int? _selectedStepIndex;

  String _calculateTotalDuration() {
    double totalSeconds = 0;
    for (final step in _steps) {
      final durationStr = step['duration'].toString().replaceAll('s', '');
      totalSeconds += double.tryParse(durationStr) ?? 0;
    }
    final hours = (totalSeconds / 3600).floor();
    final minutes = ((totalSeconds % 3600) / 60).floor();
    final seconds = (totalSeconds % 60).floor();
    return '${hours.toString().padLeft(2, '0')}:${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
  }

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

  Future<void> _saveSession() async {
    if (_steps.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Add at least one step before saving.")),
      );
      return;
    }

    // Show dialog to get/confirm session name
    final nameController = TextEditingController(text: _sessionName);
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Colors.grey[900],
        title: const Text(
          'Save Session',
          style: TextStyle(color: Colors.white),
        ),
        content: TextField(
          controller: nameController,
          autofocus: true,
          style: const TextStyle(color: Colors.white),
          decoration: const InputDecoration(
            labelText: 'Session Name',
            labelStyle: TextStyle(color: Colors.white70),
            enabledBorder: UnderlineInputBorder(
              borderSide: BorderSide(color: Colors.white54),
            ),
            focusedBorder: UnderlineInputBorder(
              borderSide: BorderSide(color: Colors.white),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, nameController.text),
            child: const Text('Save'),
          ),
        ],
      ),
    );

    if (result != null && result.isNotEmpty) {
      try {
        final session = await _storage.saveSession(
          name: result,
          steps: _steps,
          crossfadeSeconds: _crossfadeSeconds,
          existingId: _sessionId,
        );
        setState(() {
          _sessionId = session.id;
          _sessionName = session.name;
        });
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("Session '${session.name}' saved!")),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("Error saving session: $e")),
          );
        }
      }
    }
  }

  void _startSession() {
    if (_steps.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Please add at least one step to start.")),
      );
      return;
    }
    Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => SessionScreen(steps: _steps)));
  }

  Future<void> _exportSession() async {
    if (_steps.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Add at least one step before exporting.")),
      );
      return;
    }

    try {
      // Create a temporary session model for export
      final session = SessionModel(
        id: _sessionId ?? DateTime.now().millisecondsSinceEpoch.toString(),
        name: _sessionName,
        steps: _steps,
        crossfadeSeconds: _crossfadeSeconds,
      );

      // Export to file
      final file = await _storage.exportSessionToFile(session);

      // Share the file
      await Share.shareXFiles(
        [XFile(file.path)],
        subject: 'Session: $_sessionName',
        text: 'Exported session from Session Builder',
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text("Error exporting session: $e")),
        );
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
                  Text(
                    _calculateTotalDuration(),
                    style: const TextStyle(color: Colors.white, fontSize: 14),
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
