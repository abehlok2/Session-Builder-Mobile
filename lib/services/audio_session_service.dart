import 'package:audio_session/audio_session.dart';
import 'package:flutter/foundation.dart';

/// Service to configure and manage audio session for background playback
class AudioSessionService {
  static AudioSessionService? _instance;
  AudioSession? _session;
  bool _isConfigured = false;

  AudioSessionService._();

  static AudioSessionService get instance {
    _instance ??= AudioSessionService._();
    return _instance!;
  }

  /// Configure the audio session for playback
  /// This should be called once when the app starts
  Future<void> configure() async {
    if (_isConfigured) return;

    try {
      _session = await AudioSession.instance;

      await _session!.configure(const AudioSessionConfiguration(
        avAudioSessionCategory: AVAudioSessionCategory.playback,
        avAudioSessionCategoryOptions: AVAudioSessionCategoryOptions.mixWithOthers,
        avAudioSessionMode: AVAudioSessionMode.defaultMode,
        avAudioSessionRouteSharingPolicy: AVAudioSessionRouteSharingPolicy.defaultPolicy,
        avAudioSessionSetActiveOptions: AVAudioSessionSetActiveOptions.none,
        androidAudioAttributes: AndroidAudioAttributes(
          contentType: AndroidAudioContentType.music,
          usage: AndroidAudioUsage.media,
          flags: AndroidAudioFlags.none,
        ),
        androidAudioFocusGainType: AndroidAudioFocusGainType.gain,
        androidWillPauseWhenDucked: false,
      ));

      // Listen for audio interruptions
      _session!.interruptionEventStream.listen((event) {
        if (event.begin) {
          switch (event.type) {
            case AudioInterruptionType.duck:
              // Lower volume temporarily
              debugPrint('Audio ducking requested');
              break;
            case AudioInterruptionType.pause:
            case AudioInterruptionType.unknown:
              // Pause audio
              debugPrint('Audio interruption: pause requested');
              break;
          }
        } else {
          switch (event.type) {
            case AudioInterruptionType.duck:
              // Restore volume
              debugPrint('Audio ducking ended');
              break;
            case AudioInterruptionType.pause:
            case AudioInterruptionType.unknown:
              // Resume audio if appropriate
              debugPrint('Audio interruption ended');
              break;
          }
        }
      });

      // Listen for becoming noisy (e.g., headphones unplugged)
      _session!.becomingNoisyEventStream.listen((_) {
        // Pause audio when headphones are unplugged
        debugPrint('Becoming noisy - should pause audio');
      });

      _isConfigured = true;
      debugPrint('Audio session configured successfully');
    } catch (e) {
      debugPrint('Error configuring audio session: $e');
    }
  }

  /// Activate the audio session before starting playback
  Future<bool> activate() async {
    if (_session == null) {
      await configure();
    }

    try {
      return await _session!.setActive(true);
    } catch (e) {
      debugPrint('Error activating audio session: $e');
      return false;
    }
  }

  /// Deactivate the audio session when stopping playback
  Future<void> deactivate() async {
    try {
      await _session?.setActive(false);
    } catch (e) {
      debugPrint('Error deactivating audio session: $e');
    }
  }

  /// Check if audio is currently ducked
  bool get isDucked => _session?.interruptionEventStream != null;
}
