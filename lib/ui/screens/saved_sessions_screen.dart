import 'package:flutter/material.dart';
import 'package:session_builder_mobile/ui/screens/step_list_screen.dart';
import 'package:session_builder_mobile/ui/screens/session_screen.dart';

class SavedSessionsScreen extends StatefulWidget {
  const SavedSessionsScreen({super.key});

  @override
  State<SavedSessionsScreen> createState() => _SavedSessionsScreenState();
}

class _SavedSessionsScreenState extends State<SavedSessionsScreen> {
  // Mock Data
  final List<Map<String, dynamic>> _savedSessions = [
    {
      "name": "Focus Flow",
      "steps_count": 5,
      "duration": "00:25:00",
      "steps": [
        // Mock steps data for loading
        {
          "binaural": "Beta",
          "noise": "White",
          "track": "None",
          "duration": "10:00",
        },
        {
          "binaural": "Alpha",
          "noise": "Pink",
          "track": "Rain",
          "duration": "15:00",
        },
      ],
    },
    {
      "name": "Sleep Aid",
      "steps_count": 3,
      "duration": "00:45:00",
      "steps": [
        {
          "binaural": "Theta",
          "noise": "Brown",
          "track": "Ocean",
          "duration": "45:00",
        },
      ],
    },
    {
      "name": "Meditation",
      "steps_count": 4,
      "duration": "00:20:00",
      "steps": [],
    },
  ];

  int? _selectedIndex;

  void _loadSession() {
    if (_selectedIndex == null) return;

    final session = _savedSessions[_selectedIndex!];

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text("Load Session"),
        content: Text(
          "Do you want to Edit '${session['name']}' or Start it immediately?",
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              // Navigate to Edit (Step List Screen)
              Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => StepListScreen(
                    initialSteps: List<Map<String, dynamic>>.from(
                      session['steps'],
                    ),
                  ),
                ),
              );
            },
            child: const Text("Edit"),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              // Navigate to Start (Session Screen)
              Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => SessionScreen(
                    steps: List<Map<String, dynamic>>.from(session['steps']),
                  ),
                ),
              );
            },
            child: const Text("Start"),
          ),
        ],
      ),
    );
  }

  void _deleteSession() {
    if (_selectedIndex != null) {
      setState(() {
        _savedSessions.removeAt(_selectedIndex!);
        _selectedIndex = null;
      });
    }
  }

  void _moveUp() {
    if (_selectedIndex != null && _selectedIndex! > 0) {
      setState(() {
        final item = _savedSessions.removeAt(_selectedIndex!);
        _savedSessions.insert(_selectedIndex! - 1, item);
        _selectedIndex = _selectedIndex! - 1;
      });
    }
  }

  void _moveDown() {
    if (_selectedIndex != null && _selectedIndex! < _savedSessions.length - 1) {
      setState(() {
        final item = _savedSessions.removeAt(_selectedIndex!);
        _savedSessions.insert(_selectedIndex! + 1, item);
        _selectedIndex = _selectedIndex! + 1;
      });
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
        title: const Text(
          "Saved Sessions",
          style: TextStyle(color: Colors.white),
        ),
        backgroundColor: Colors.black,
      ),
      body: SafeArea(
        child: Column(
          children: [
            // List of Sessions
            Expanded(
              child: ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: _savedSessions.length,
                separatorBuilder: (ctx, i) => const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final session = _savedSessions[index];
                  final isSelected = _selectedIndex == index;
                  return GestureDetector(
                    onTap: () => setState(() => _selectedIndex = index),
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: isSelected ? Colors.white10 : Colors.transparent,
                        border: Border.all(
                          color: isSelected ? Colors.white : Colors.white30,
                          width: isSelected ? 2 : 1,
                        ),
                        borderRadius: BorderRadius.circular(15),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            "${session['name']}",
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 5),
                          Text(
                            "# Steps: ${session['steps_count']}",
                            style: const TextStyle(
                              color: Colors.white70,
                              fontSize: 14,
                            ),
                          ),
                          Text(
                            "Total Duration: ${session['duration']}",
                            style: const TextStyle(
                              color: Colors.white70,
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
              padding: const EdgeInsets.all(20),
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: Colors.white24)),
              ),
              child: Column(
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _buildActionButton("Move Up", _moveUp),
                      _buildActionButton("Load", _loadSession),
                    ],
                  ),
                  const SizedBox(height: 15),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _buildActionButton("Move Down", _moveDown),
                      _buildActionButton("Delete", _deleteSession),
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

  Widget _buildActionButton(String label, VoidCallback onPressed) {
    return SizedBox(
      width: 140,
      height: 45,
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
