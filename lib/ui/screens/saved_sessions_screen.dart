import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/logic/state/playback_provider.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:session_builder_mobile/logic/state/sessions_repository_provider.dart';
import 'package:session_builder_mobile/ui/screens/session_screen.dart';
import 'package:session_builder_mobile/ui/screens/step_list_screen.dart';
import 'package:session_builder_mobile/data/session_storage.dart';

class SavedSessionsScreen extends ConsumerStatefulWidget {
  const SavedSessionsScreen({super.key});

  @override
  ConsumerState<SavedSessionsScreen> createState() =>
      _SavedSessionsScreenState();
}

class _SavedSessionsScreenState extends ConsumerState<SavedSessionsScreen> {
  int? _selectedIndex;

  void _loadSession(SessionModel session) {
    ref.read(sessionEditorProvider.notifier).loadFromModel(session);
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => StepListScreen(
          initialSteps: session.steps,
          sessionId: session.id,
          sessionName: session.name,
        ),
      ),
    );
  }

  Future<void> _startSession(SessionModel session) async {
    ref.read(sessionEditorProvider.notifier).loadFromModel(session);
    await ref.read(playbackProvider.notifier).start(
      session.steps,
      sessionTitle: session.name,
    );
    Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => const SessionScreen()));
  }

  Future<void> _deleteSession(SessionModel session) async {
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
      await ref
          .read(sessionsRepositoryProvider.notifier)
          .deleteSession(session.id);
      setState(() {
        _selectedIndex = null;
      });
    }
  }

  Future<void> _move(int delta) async {
    final sessionsValue = ref.read(sessionsRepositoryProvider);
    if (_selectedIndex == null || sessionsValue is! AsyncData) return;
    final sessions = sessionsValue.value;
    final newIndex = _selectedIndex! + delta;
    if (newIndex < 0 || newIndex >= (sessions?.length ?? 0)) return;
    await ref
        .read(sessionsRepositoryProvider.notifier)
        .reorderSession(_selectedIndex!, newIndex);
    setState(() {
      _selectedIndex = newIndex;
    });
  }

  @override
  Widget build(BuildContext context) {
    final sessionsAsync = ref.watch(sessionsRepositoryProvider);
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
            Expanded(
              child: sessionsAsync.when(
                loading: () => const Center(
                  child: CircularProgressIndicator(color: Colors.white),
                ),
                error: (e, _) => Center(
                  child: Text(
                    'Error: $e',
                    style: const TextStyle(color: Colors.white),
                  ),
                ),
                data: (sessions) => sessions.isEmpty
                    ? const Center(
                        child: Text(
                          "No saved sessions yet.\nCreate a new session and save it!",
                          textAlign: TextAlign.center,
                          style: TextStyle(color: Colors.white54),
                        ),
                      )
                    : ListView.separated(
                        padding: const EdgeInsets.all(16),
                        itemCount: sessions.length,
                        separatorBuilder: (ctx, i) =>
                            const SizedBox(height: 10),
                        itemBuilder: (context, index) {
                          final session = sessions[index];
                          final isSelected = _selectedIndex == index;
                          return GestureDetector(
                            onTap: () => setState(() => _selectedIndex = index),
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
                      _buildActionButton("Move Up", () => _move(-1)),
                      _buildActionButton("Load", () {
                        final sessionsValue = ref.read(
                          sessionsRepositoryProvider,
                        );
                        if (_selectedIndex != null &&
                            sessionsValue is AsyncData &&
                            (sessionsValue.value?.length ?? 0) >
                                _selectedIndex!) {
                          _loadSession(
                            (sessionsValue.value ??
                                const <SessionModel>[])[_selectedIndex!],
                          );
                        }
                      }),
                    ],
                  ),
                  const SizedBox(height: 15),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _buildActionButton("Move Down", () => _move(1)),
                      _buildActionButton("Delete", () {
                        final sessionsValue = ref.read(
                          sessionsRepositoryProvider,
                        );
                        if (_selectedIndex != null &&
                            sessionsValue is AsyncData &&
                            (sessionsValue.value?.length ?? 0) >
                                _selectedIndex!) {
                          _deleteSession(
                            (sessionsValue.value ??
                                const <SessionModel>[])[_selectedIndex!],
                          );
                        }
                      }),
                    ],
                  ),
                  const SizedBox(height: 15),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      _buildActionButton("Start", () {
                        final sessionsValue = ref.read(
                          sessionsRepositoryProvider,
                        );
                        if (_selectedIndex != null &&
                            sessionsValue is AsyncData &&
                            (sessionsValue.value?.length ?? 0) >
                                _selectedIndex!) {
                          _startSession(
                            (sessionsValue.value ??
                                const <SessionModel>[])[_selectedIndex!],
                          );
                        }
                      }),
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
