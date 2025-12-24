import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';
import 'package:session_builder_mobile/data/session_storage.dart';
import 'package:session_builder_mobile/logic/state/playback_provider.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:session_builder_mobile/logic/state/sessions_repository_provider.dart';
import 'package:session_builder_mobile/ui/screens/session_screen.dart';
import 'package:session_builder_mobile/ui/screens/step_builder_screen.dart';

class StepListScreen extends ConsumerStatefulWidget {
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
  ConsumerState<StepListScreen> createState() => _StepListScreenState();
}

class _StepListScreenState extends ConsumerState<StepListScreen> {
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      final editor = ref.read(sessionEditorProvider.notifier);
      if (widget.initialSteps != null ||
          widget.sessionId != null ||
          widget.sessionName != null) {
        editor.state = SessionEditorState(
          steps: widget.initialSteps != null
              ? List<Map<String, dynamic>>.from(widget.initialSteps!)
              : const [],
          sessionId: widget.sessionId,
          sessionName: widget.sessionName ?? 'Untitled Session',
        );
      } else {
        editor.reset();
      }
    });
  }

  Future<void> _addStep() async {
    await Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => const StepBuilderScreen()));
  }

  void _removeStep() {
    ref.read(sessionEditorProvider.notifier).removeSelectedStep();
  }

  Future<void> _saveSession() async {
    final editorState = ref.read(sessionEditorProvider);
    if (!editorState.hasSteps) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Add at least one step before saving.")),
      );
      return;
    }

    final nameController = TextEditingController(text: editorState.sessionName);
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
        final saved = await ref
            .read(sessionsRepositoryProvider.notifier)
            .saveFromEditor(editorState: editorState, name: result);
        ref
            .read(sessionEditorProvider.notifier)
            .setSessionMetadata(name: saved.name, id: saved.id);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("Session '${saved.name}' saved!")),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text("Error saving session: $e")));
        }
      }
    }
  }

  Future<void> _startSession() async {
    final editorState = ref.read(sessionEditorProvider);
    if (!editorState.hasSteps) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Please add at least one step to start.")),
      );
      return;
    }
    await ref.read(playbackProvider.notifier).start(
      editorState.steps,
      sessionTitle: editorState.sessionName,
    );
    if (mounted) {
      Navigator.of(
        context,
      ).push(MaterialPageRoute(builder: (_) => const SessionScreen()));
    }
  }

  Future<void> _exportSession() async {
    final editorState = ref.read(sessionEditorProvider);
    if (!editorState.hasSteps) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text("Add at least one step before exporting."),
        ),
      );
      return;
    }

    try {
      final session = SessionModel(
        id:
            editorState.sessionId ??
            DateTime.now().millisecondsSinceEpoch.toString(),
        name: editorState.sessionName,
        steps: editorState.steps,
        crossfadeSeconds: editorState.crossfadeSeconds,
      );

      final file = await ref
          .read(sessionsRepositoryProvider.notifier)
          .exportSession(session);

      await Share.shareXFiles(
        [XFile(file.path)],
        subject: 'Session: ${editorState.sessionName}',
        text: 'Exported session from Session Builder',
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text("Error exporting session: $e")));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final editorState = ref.watch(sessionEditorProvider);
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: Text(
          editorState.sessionName,
          style: const TextStyle(color: Colors.white),
        ),
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
              child: ReorderableListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: editorState.steps.length,
                buildDefaultDragHandles: false,
                onReorder: (oldIndex, newIndex) {
                  ref
                      .read(sessionEditorProvider.notifier)
                      .reorderSteps(oldIndex, newIndex);
                },
                itemBuilder: (context, index) {
                  final step = editorState.steps[index];
                  final isSelected = editorState.selectedIndex == index;
                  return Padding(
                    key: ValueKey(step),
                    padding: const EdgeInsets.only(bottom: 10),
                    child: GestureDetector(
                      onTap: () {
                        Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) => StepBuilderScreen(
                              initialStep: step,
                              editIndex: index,
                            ),
                          ),
                        );
                      },
                      onLongPress: () => ref
                          .read(sessionEditorProvider.notifier)
                          .selectStep(index),
                      child: Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: isSelected
                              ? Colors.white10
                              : Colors.transparent,
                          border: Border.all(
                            color: isSelected ? Colors.white : Colors.white54,
                            width: isSelected ? 2 : 1,
                          ),
                          borderRadius: BorderRadius.circular(15),
                        ),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.center,
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
                                  const SizedBox(height: 4),
                                  Text(
                                    "Duration: ${step['duration']}",
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 14,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            // Drag Handle
                            ReorderableDragStartListener(
                              index: index,
                              child: const Padding(
                                padding: EdgeInsets.all(8.0),
                                child: Icon(
                                  Icons.drag_handle,
                                  color: Colors.white54,
                                ),
                              ),
                            ),
                          ],
                        ),
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
                        onTap: () => ref
                            .read(sessionEditorProvider.notifier)
                            .cycleCrossfade(),
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
                            "${editorState.crossfadeSeconds}s",
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
                    editorState.totalDurationFormatted,
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
