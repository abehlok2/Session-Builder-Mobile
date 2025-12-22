import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/data/session_storage.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';

final sessionStorageProvider = Provider<SessionStorageBase>((ref) {
  return SessionStorage();
});

class SessionsRepositoryNotifier extends AsyncNotifier<List<SessionModel>> {
  late final SessionStorageBase _storage = ref.read(sessionStorageProvider);

  @override
  Future<List<SessionModel>> build() async {
    return _storage.loadSessions();
  }

  Future<void> refresh() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(_storage.loadSessions);
  }

  Future<SessionModel> saveFromEditor({
    required SessionEditorState editorState,
    required String name,
  }) async {
    final saved = await _storage.saveSession(
      name: name,
      steps: editorState.steps,
      crossfadeSeconds: editorState.crossfadeSeconds,
      existingId: editorState.sessionId,
    );
    await refresh();
    return saved;
  }

  Future<void> deleteSession(String id) async {
    await _storage.deleteSession(id);
    await refresh();
  }

  Future<void> reorderSession(int oldIndex, int newIndex) async {
    await _storage.reorderSession(oldIndex, newIndex);
    await refresh();
  }

  Future<File> exportSession(SessionModel session) async {
    return _storage.exportSessionToFile(session);
  }
}

final sessionsRepositoryProvider =
    AsyncNotifierProvider<SessionsRepositoryNotifier, List<SessionModel>>(
  SessionsRepositoryNotifier.new,
);
