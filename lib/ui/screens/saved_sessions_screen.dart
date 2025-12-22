import 'package:flutter/material.dart';
import 'package:session_builder_mobile/data/session_storage.dart';
import 'package:session_builder_mobile/ui/screens/step_list_screen.dart';
import 'package:session_builder_mobile/ui/screens/session_screen.dart';

class SavedSessionsScreen extends StatefulWidget {
  const SavedSessionsScreen({super.key});

  @override
  State<SavedSessionsScreen> createState() => _SavedSessionsScreenState();
}

class _SavedSessionsScreenState extends State<SavedSessionsScreen> {
  final SessionStorage _storage = SessionStorage();
  List<SessionModel> _savedSessions = [];
  bool _isLoading = true;
  int? _selectedIndex;

  @override
  void initState() {
    super.initState();
    _loadSessions();
  }

  Future<void> _loadSessions() async {
    setState(() => _isLoading = true);
    try {
      final sessions = await _storage.loadSessions();
      setState(() {
        _savedSessions = sessions;
        _isLoading = false;
      });
    } catch (e) {
      debugPrint('Error loading sessions: $e');
      setState(() => _isLoading = false);
    }
  }

  void _loadSession() {
    if (_selectedIndex == null) return;

    final session = _savedSessions[_selectedIndex!];

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Colors.grey[900],
        title: const Text(
          "Load Session",
          style: TextStyle(color: Colors.white),
        ),
        content: Text(
          "Do you want to Edit '${session.name}' or Start it immediately?",
          style: const TextStyle(color: Colors.white70),
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
                      session.steps,
                    ),
                    sessionId: session.id,
                    sessionName: session.name,
                  ),
                ),
              );
            },
            child: const Text("Edit"),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              if (session.steps.isEmpty) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text("This session has no steps to play."),
                  ),
                );
                return;
              }
              // Navigate to Start (Session Screen)
              Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => SessionScreen(
                    steps: List<Map<String, dynamic>>.from(session.steps),
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

  Future<void> _deleteSession() async {
    if (_selectedIndex == null) return;

    final session = _savedSessions[_selectedIndex!];

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Colors.grey[900],
        title: const Text(
          "Delete Session",
          style: TextStyle(color: Colors.white),
        ),
        content: Text(
          "Are you sure you want to delete '${session.name}'?",
          style: const TextStyle(color: Colors.white70),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text("Cancel"),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text("Delete", style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await _storage.deleteSession(session.id);
      setState(() {
        _savedSessions.removeAt(_selectedIndex!);
        _selectedIndex = null;
      });
    }
  }

  Future<void> _moveUp() async {
    if (_selectedIndex != null && _selectedIndex! > 0) {
      await _storage.reorderSession(_selectedIndex!, _selectedIndex! - 1);
      setState(() {
        final item = _savedSessions.removeAt(_selectedIndex!);
        _savedSessions.insert(_selectedIndex! - 1, item);
        _selectedIndex = _selectedIndex! - 1;
      });
    }
  }

  Future<void> _moveDown() async {
    if (_selectedIndex != null && _selectedIndex! < _savedSessions.length - 1) {
      await _storage.reorderSession(_selectedIndex!, _selectedIndex! + 2);
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
              child: _isLoading
                  ? const Center(
                      child: CircularProgressIndicator(color: Colors.white),
                    )
                  : _savedSessions.isEmpty
                      ? const Center(
                          child: Text(
                            "No saved sessions yet.\nCreate a new session and save it!",
                            textAlign: TextAlign.center,
                            style: TextStyle(color: Colors.white54),
                          ),
                        )
                      : ListView.separated(
                          padding: const EdgeInsets.all(16),
                          itemCount: _savedSessions.length,
                          separatorBuilder: (ctx, i) =>
                              const SizedBox(height: 10),
                          itemBuilder: (context, index) {
                            final session = _savedSessions[index];
                            final isSelected = _selectedIndex == index;
                            return GestureDetector(
                              onTap: () =>
                                  setState(() => _selectedIndex = index),
                              child: Container(
                                padding: const EdgeInsets.all(16),
                                decoration: BoxDecoration(
                                  color: isSelected
                                      ? Colors.white10
                                      : Colors.transparent,
                                  border: Border.all(
                                    color: isSelected
                                        ? Colors.white
                                        : Colors.white30,
                                    width: isSelected ? 2 : 1,
                                  ),
                                  borderRadius: BorderRadius.circular(15),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      session.name,
                                      style: const TextStyle(
                                        color: Colors.white,
                                        fontSize: 16,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                    const SizedBox(height: 5),
                                    Text(
                                      "# Steps: ${session.stepsCount}",
                                      style: const TextStyle(
                                        color: Colors.white70,
                                        fontSize: 14,
                                      ),
                                    ),
                                    Text(
                                      "Total Duration: ${session.totalDuration}",
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
