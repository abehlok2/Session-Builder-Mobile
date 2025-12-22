import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/data/session_storage.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:session_builder_mobile/logic/state/sessions_repository_provider.dart';

class FakeSessionStorage implements SessionStorageBase {
  final List<SessionModel> sessions = [];

  @override
  void clearCache() {}

  @override
  Future<void> deleteSession(String id) async {
    sessions.removeWhere((element) => element.id == id);
  }

  @override
  Future<File> exportSessionToFile(SessionModel session) {
    // Not needed for this test; return a dummy file reference.
    throw UnimplementedError();
  }

  @override
  Future<List<SessionModel>> loadSessions() async {
    return sessions;
  }

  @override
  Future<void> reorderSession(int oldIndex, int newIndex) async {
    if (oldIndex < newIndex) newIndex -= 1;
    final item = sessions.removeAt(oldIndex);
    sessions.insert(newIndex, item);
  }

  @override
  Future<SessionModel> saveSession({
    required String name,
    required List<Map<String, dynamic>> steps,
    int crossfadeSeconds = 3,
    String? existingId,
  }) async {
    if (existingId != null) {
      final idx = sessions.indexWhere((s) => s.id == existingId);
      if (idx != -1) {
        sessions[idx] = sessions[idx].copyWith(
          name: name,
          steps: steps,
          crossfadeSeconds: crossfadeSeconds,
        );
        return sessions[idx];
      }
    }
    final session = SessionModel(
      id: existingId ?? DateTime.now().millisecondsSinceEpoch.toString(),
      name: name,
      steps: steps,
      crossfadeSeconds: crossfadeSeconds,
    );
    sessions.add(session);
    return session;
  }
}

void main() {
  test('sessions repository saves and reloads sessions', () async {
    final fakeStorage = FakeSessionStorage();
    final container = ProviderContainer(
      overrides: [
        sessionStorageProvider.overrideWithValue(fakeStorage),
      ],
    );
    addTearDown(container.dispose);

    final repo = container.read(sessionsRepositoryProvider.notifier);
    final editorState = const SessionEditorState(
      steps: [
        {'duration': '10s', 'binaural': 'A', 'noise': 'B', 'track': 'None'}
      ],
    );

    final saved = await repo.saveFromEditor(
      editorState: editorState,
      name: 'Test Session',
    );
    expect(saved.name, 'Test Session');

    await repo.refresh();
    final sessionsValue = container.read(sessionsRepositoryProvider);
    expect(sessionsValue.hasValue, true);
    expect(sessionsValue.value?.length, 1);
  });
}
