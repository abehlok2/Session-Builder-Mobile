import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/data/session_storage.dart';

class SessionEditorState {
  final List<Map<String, dynamic>> steps;
  final int? selectedIndex;
  final int crossfadeSeconds;
  final String sessionName;
  final String? sessionId;

  const SessionEditorState({
    this.steps = const [],
    this.selectedIndex,
    this.crossfadeSeconds = 3,
    this.sessionName = 'Untitled Session',
    this.sessionId,
  });

  double get totalSeconds {
    double total = 0;
    for (final step in steps) {
      final durationStr = step['duration'].toString().replaceAll('s', '');
      total += double.tryParse(durationStr) ?? 0;
    }
    return total;
  }

  String get totalDurationFormatted {
    final hours = (totalSeconds / 3600).floor();
    final minutes = ((totalSeconds % 3600) / 60).floor();
    final seconds = (totalSeconds % 60).floor();
    return '${hours.toString().padLeft(2, '0')}:${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
  }

  bool get hasSteps => steps.isNotEmpty;

  SessionEditorState copyWith({
    List<Map<String, dynamic>>? steps,
    int? selectedIndex,
    int? crossfadeSeconds,
    String? sessionName,
    String? sessionId,
    bool clearSelection = false,
  }) {
    return SessionEditorState(
      steps: steps ?? this.steps,
      selectedIndex: clearSelection ? null : selectedIndex ?? this.selectedIndex,
      crossfadeSeconds: crossfadeSeconds ?? this.crossfadeSeconds,
      sessionName: sessionName ?? this.sessionName,
      sessionId: sessionId ?? this.sessionId,
    );
  }
}

class SessionEditorNotifier extends Notifier<SessionEditorState> {
  @override
  SessionEditorState build() {
    return const SessionEditorState();
  }

  void reset() {
    state = const SessionEditorState();
  }

  void loadFromModel(SessionModel model) {
    state = SessionEditorState(
      steps: List<Map<String, dynamic>>.from(model.steps),
      selectedIndex: null,
      crossfadeSeconds: model.crossfadeSeconds,
      sessionName: model.name,
      sessionId: model.id,
    );
  }

  void setSessionMetadata({String? name, String? id}) {
    state = state.copyWith(sessionName: name, sessionId: id);
  }

  void addStep(Map<String, dynamic> step) {
    final updated = [...state.steps, step];
    state = state.copyWith(steps: updated, selectedIndex: updated.length - 1);
  }

  void updateStep(int index, Map<String, dynamic> step) {
    if (index < 0 || index >= state.steps.length) return;
    final updated = [...state.steps];
    updated[index] = step;
    state = state.copyWith(steps: updated);
  }

  void removeSelectedStep() {
    final index = state.selectedIndex;
    if (index == null || index < 0 || index >= state.steps.length) return;
    final updated = [...state.steps]..removeAt(index);
    state = state.copyWith(
      steps: updated,
      selectedIndex: index >= updated.length ? updated.length - 1 : index,
    );
  }

  void reorderSteps(int oldIndex, int newIndex) {
    final updated = [...state.steps];
    if (oldIndex < newIndex) {
      newIndex -= 1;
    }
    final item = updated.removeAt(oldIndex);
    updated.insert(newIndex, item);
    state = state.copyWith(steps: updated);
  }

  void selectStep(int? index) {
    state = state.copyWith(selectedIndex: index);
  }

  void cycleCrossfade() {
    final next = (state.crossfadeSeconds % 30) + 1;
    state = state.copyWith(crossfadeSeconds: next);
  }
}

final sessionEditorProvider =
    NotifierProvider<SessionEditorNotifier, SessionEditorState>(
  SessionEditorNotifier.new,
);
