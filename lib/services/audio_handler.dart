import 'dart:async';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';
import 'package:session_builder_mobile/logic/state/audio_controller.dart';

class SessionAudioHandler extends BaseAudioHandler with SeekHandler {
  SessionAudioHandler({AudioController? controller})
      : _controller = controller ?? DefaultAudioController();

  final AudioController _controller;
  Timer? _pollingTimer;
  Duration _duration = Duration.zero;

  Future<void> startSession({
    required String trackJson,
    required Duration duration,
    String? title,
  }) async {
    _duration = duration;
    mediaItem.add(
      MediaItem(
        id: 'session_builder_session',
        title: title ?? 'Session',
        duration: duration,
      ),
    );
    await _controller.start(trackJson);
    _startPolling();
    playbackState.add(_buildPlaybackState(playing: true, position: Duration.zero));
  }

  @override
  Future<void> play() async {
    await _controller.resume();
    _startPolling();
    playbackState.add(
      _buildPlaybackState(
        playing: true,
        position: playbackState.value.updatePosition,
      ),
    );
  }

  @override
  Future<void> pause() async {
    await _controller.pause();
    playbackState.add(
      _buildPlaybackState(
        playing: false,
        position: playbackState.value.updatePosition,
      ),
    );
  }

  @override
  Future<void> seek(Duration position) async {
    await _controller.startFromPosition(position.inMilliseconds / 1000);
    playbackState.add(
      _buildPlaybackState(
        playing: playbackState.value.playing,
        position: position,
      ),
    );
  }

  @override
  Future<void> stop() async {
    _stopPolling();
    await _controller.stop();
    playbackState.add(
      playbackState.value.copyWith(
        controls: const [],
        systemActions: const {},
        processingState: AudioProcessingState.idle,
        playing: false,
        updatePosition: Duration.zero,
        bufferedPosition: Duration.zero,
        speed: 1.0,
      ),
    );
    await super.stop();
  }

  @override
  Future<void> onTaskRemoved() async {
    await stop();
  }

  void _startPolling() {
    _pollingTimer?.cancel();
    _pollingTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      _updateFromStatus();
    });
  }

  void _stopPolling() {
    _pollingTimer?.cancel();
    _pollingTimer = null;
  }

  Future<void> _updateFromStatus() async {
    try {
      final status = await _controller.getStatus();
      if (status == null) return;
      final position = Duration(
        milliseconds: (status.positionSeconds * 1000).round(),
      );
      playbackState.add(
        _buildPlaybackState(playing: !status.isPaused, position: position),
      );
      final current = mediaItem.value;
      if (current != null && current.duration != _duration) {
        mediaItem.add(current.copyWith(duration: _duration));
      }
    } catch (e) {
      debugPrint('Error updating playback state: $e');
    }
  }

  PlaybackState _buildPlaybackState({
    required bool playing,
    required Duration position,
  }) {
    return playbackState.value.copyWith(
      controls: [
        if (playing) MediaControl.pause else MediaControl.play,
        MediaControl.stop,
      ],
      systemActions: const {MediaAction.seek},
      processingState: AudioProcessingState.ready,
      playing: playing,
      updatePosition: position,
      bufferedPosition: Duration.zero,
      speed: 1.0,
    );
  }
}

class AudioHandlerService {
  AudioHandlerService._();

  static final AudioHandlerService instance = AudioHandlerService._();

  SessionAudioHandler? _handler;

  Future<SessionAudioHandler> init() async {
    if (_handler != null) return _handler!;
    final handler = await AudioService.init(
      builder: () => SessionAudioHandler(),
      config: const AudioServiceConfig(
        androidNotificationChannelId:
            'com.session_builder_mobile.session_playback',
        androidNotificationChannelName: 'Session Playback',
        androidNotificationOngoing: true,
        androidStopForegroundOnPause: false,
      ),
    );
    _handler = handler as SessionAudioHandler;
    return _handler!;
  }

  SessionAudioHandler get handler {
    final handler = _handler;
    if (handler == null) {
      throw StateError('AudioHandlerService has not been initialized.');
    }
    return handler;
  }
}
