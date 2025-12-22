import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/logic/state/playback_provider.dart';

class FakeAudioController implements AudioController {
  bool started = false;
  bool paused = false;
  double lastVolume = 0;
  double lastStartPosition = 0;

  @override
  Future<void> pause() async {
    paused = true;
  }

  @override
  Future<void> resume() async {
    paused = false;
  }

  @override
  Future<void> setVolume(double volume) async {
    lastVolume = volume;
  }

  @override
  Future<void> start(String trackJson) async {
    started = true;
  }

  @override
  Future<void> startFromPosition(double position) async {
    lastStartPosition = position;
  }

  @override
  Future<void> stop() async {
    started = false;
  }
}

void main() {
  test('playback start/toggle/volume updates state', () async {
    final fakeController = FakeAudioController();
    final container = ProviderContainer(
      overrides: [
        audioControllerProvider.overrideWithValue(fakeController),
      ],
    );
    addTearDown(container.dispose);

    final notifier = container.read(playbackProvider.notifier);
    await notifier.start([
      {'duration': '5s', 'binaural': 'A', 'noise': 'B', 'track': 'None'}
    ]);

    var state = container.read(playbackProvider);
    expect(fakeController.started, true);
    expect(state.isPlaying, true);

    await notifier.togglePlayPause();
    state = container.read(playbackProvider);
    expect(state.isPlaying, false);
    expect(fakeController.paused, true);

    await notifier.setVolumeLevel(0.9);
    state = container.read(playbackProvider);
    expect(state.volume, 0.9);
    expect(fakeController.lastVolume, 0.9);

    await notifier.skipToStep(0, [
      {'duration': '5s', 'binaural': 'A', 'noise': 'B', 'track': 'None'}
    ]);
    expect(fakeController.lastStartPosition, 0);
  });
}
