import 'package:flutter_test/flutter_test.dart';
import 'package:session_builder_mobile/logic/state/session_editor_provider.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

void main() {
  group('SessionEditorProvider', () {
    test('add and remove steps updates state', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(sessionEditorProvider.notifier);
      notifier.addStep({'duration': '10s', 'binaural': 'A', 'noise': 'B', 'track': 'None'});

      var state = container.read(sessionEditorProvider);
      expect(state.steps.length, 1);
      expect(state.totalSeconds, 10);

      notifier.selectStep(0);
      notifier.removeSelectedStep();

      state = container.read(sessionEditorProvider);
      expect(state.steps.isEmpty, true);
    });

    test('cycleCrossfade wraps and updates', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(sessionEditorProvider.notifier);
      notifier.cycleCrossfade();

      final state = container.read(sessionEditorProvider);
      expect(state.crossfadeSeconds, 4);
    });
  });
}
