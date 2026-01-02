import 'package:session_builder_mobile/src/kotlin/mobile_api.dart'
    hide setVolume;
import 'package:session_builder_mobile/src/kotlin/mobile_api.dart'
    as mobile_api
    show setVolume;

abstract class AudioController {
  Future<void> start(String trackJson);
  Future<void> pause();
  Future<void> resume();
  Future<void> startFromPosition(double position);
  Future<void> setVolume(double volume);
  Future<void> stop();
  Future<PlaybackStatus?> getStatus();
}

class DefaultAudioController implements AudioController {
  @override
  Future<void> pause() => pauseAudio();

  @override
  Future<void> resume() => resumeAudio();

  @override
  Future<void> setVolume(double volume) => mobile_api.setVolume(volume: volume);

  @override
  Future<void> start(String trackJson) =>
      startAudioSession(trackJson: trackJson, startTime: 0.0);

  @override
  Future<void> startFromPosition(double position) =>
      startFrom(position: position);

  @override
  Future<void> stop() => stopAudioSession();

  @override
  Future<PlaybackStatus?> getStatus() => getPlaybackStatus();
}
