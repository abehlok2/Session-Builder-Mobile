import 'dart:convert';
import 'package:session_builder_mobile/data/presets_repository.dart';

class AudioHelpers {
  /// Generates the full Track JSON string expected by the Rust backend.
  static String generateTrackJson({
    required List<Map<String, dynamic>> steps,
    Map<String, dynamic>? globalSettings,
  }) {
    final Map<String, dynamic> trackData = {
      "global_settings":
          globalSettings ??
          {
            "sample_rate": 44100,
            "crossfade_duration": 3.0,
            "crossfade_curve": "linear",
            "normalization_level": 0.95,
          },
      "steps": steps.map(_convertStep).toList(),
      "background_noise": null, // Can be added later if needed globally
      "overlay_clips": [],
    };

    print("AudioHelpers: Generating Track JSON for ${steps.length} steps.");
    return jsonEncode(trackData);
  }

  /// Converts a UI step map to the Rust StepData structure.
  static Map<String, dynamic> _convertStep(Map<String, dynamic> uiStep) {
    // Parse duration "300s" -> 300.0
    final durationStr = uiStep['duration'].toString().replaceAll('s', '');
    final duration = double.tryParse(durationStr) ?? 300.0;

    final voices = <Map<String, dynamic>>[];

    // Resolve Binaural Voice(s)
    final binauralPresetName = uiStep['binaural']?.toString();
    if (binauralPresetName != null && binauralPresetName != "None") {
      final presetVoices = _createBinauralVoices(binauralPresetName);
      voices.addAll(presetVoices);
    }

    // Resolve Noise Voice
    final noisePresetName = uiStep['noise']?.toString();
    if (noisePresetName != null && noisePresetName != "None") {
      final noiseVoice = _createNoiseVoice(noisePresetName);
      if (noiseVoice != null) {
        voices.add(noiseVoice);
      }
    }

    // Volumes
    final binauralVol = (uiStep['binaural_volume'] as double?) ?? 0.5;
    final noiseVol = (uiStep['noise_volume'] as double?) ?? 0.5;

    return {
      "duration": duration,
      "description": "Generated Step",
      "voices": voices,
      "binaural_volume": binauralVol,
      "noise_volume": noiseVol,
      "normalization_level": 0.95,
    };
  }

  static List<Map<String, dynamic>> _createBinauralVoices(String presetName) {
    print("AudioHelpers: Resolving binaural preset for '$presetName'...");
    final preset = PresetsRepository().getBinauralPreset(presetName);
    if (preset == null) {
      print("AudioHelpers WARNING: Binaural preset '$presetName' not found.");
      return [];
    }

    // Extract voices from the first step of the progression
    // Presumes structure is { "progression": [ { "voices": [...] } ] }
    if (preset['progression'] != null &&
        (preset['progression'] as List).isNotEmpty) {
      final firstStep = (preset['progression'] as List)[0];
      if (firstStep['voices'] != null) {
        final voicesData = (firstStep['voices'] as List)
            .cast<Map<String, dynamic>>();
        print(
          "AudioHelpers: Loaded ${voicesData.length} voices for '$presetName'.",
        );
        return voicesData;
      } else {
        print(
          "AudioHelpers WARNING: Preset '$presetName' has no voices in first progression step.",
        );
      }
    } else {
      print(
        "AudioHelpers WARNING: Preset '$presetName' has empty/null progression.",
      );
    }
    return [];
  }

  static Map<String, dynamic>? _createNoiseVoice(String presetName) {
    print("AudioHelpers: Resolving noise preset for '$presetName'...");
    var preset = PresetsRepository().getNoisePreset(presetName);

    // Robust lookup: Try appending " Noise" if not found
    if (preset == null && !presetName.endsWith(" Noise")) {
      final retryName = "$presetName Noise";
      print("AudioHelpers: '$presetName' not found, trying '$retryName'...");
      preset = PresetsRepository().getNoisePreset(retryName);
    }

    if (preset == null) {
      print(
        "AudioHelpers WARNING: Noise preset '$presetName' (or fallback) not found in repository. Available keys: ${PresetsRepository().getNoisePresetNames()}",
      );
      return null;
    }

    print(
      "AudioHelpers: Successfully found noise preset. Params keys: ${preset.keys.toList()}",
    );

    return {
      "params": preset, // Pass the full preset object as params
      "voice_type": "noise",
      "description": presetName,
    };
  }
}
