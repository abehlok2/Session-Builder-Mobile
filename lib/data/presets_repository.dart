import 'dart:convert';
import 'package:flutter/services.dart';

class PresetsRepository {
  static final PresetsRepository _instance = PresetsRepository._internal();
  factory PresetsRepository() => _instance;
  PresetsRepository._internal();

  Map<String, dynamic>? _data;

  Future<void> loadPresets() async {
    try {
      final jsonString = await rootBundle.loadString('assets/presets.json');
      _data = json.decode(jsonString);
    } catch (e) {
      print("Error loading presets: $e");
    }
  }

  List<String> getBinauralPresetNames() {
    if (_data == null) return [];
    return (_data!['binaural'] as Map<String, dynamic>).keys.toList();
  }

  List<String> getNoisePresetNames() {
    if (_data == null) return [];
    return (_data!['noise'] as Map<String, dynamic>).keys.toList();
  }

  Map<String, dynamic>? getBinauralPreset(String name) {
    if (_data == null) return null;
    return _data!['binaural'][name];
  }

  Map<String, dynamic>? getNoisePreset(String name) {
    if (_data == null) return null;
    return _data!['noise'][name];
  }
}
