import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

abstract class SessionStorageBase {
  Future<List<SessionModel>> loadSessions();
  Future<SessionModel> saveSession({
    required String name,
    required List<Map<String, dynamic>> steps,
    int crossfadeSeconds,
    String? existingId,
  });
  Future<void> deleteSession(String id);
  Future<void> reorderSession(int oldIndex, int newIndex);
  Future<File> exportSessionToFile(SessionModel session);
  void clearCache();
}

class SessionModel {
  final String id;
  final String name;
  final List<Map<String, dynamic>> steps;
  final int crossfadeSeconds;
  final DateTime createdAt;
  final DateTime updatedAt;

  SessionModel({
    required this.id,
    required this.name,
    required this.steps,
    this.crossfadeSeconds = 3,
    DateTime? createdAt,
    DateTime? updatedAt,
  })  : createdAt = createdAt ?? DateTime.now(),
        updatedAt = updatedAt ?? DateTime.now();

  int get stepsCount => steps.length;

  String get totalDuration {
    double totalSeconds = 0;
    for (final step in steps) {
      final durationStr = step['duration'].toString().replaceAll('s', '');
      totalSeconds += double.tryParse(durationStr) ?? 0;
    }
    final hours = (totalSeconds / 3600).floor();
    final minutes = ((totalSeconds % 3600) / 60).floor();
    final seconds = (totalSeconds % 60).floor();
    return '${hours.toString().padLeft(2, '0')}:${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'steps': steps,
        'crossfadeSeconds': crossfadeSeconds,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory SessionModel.fromJson(Map<String, dynamic> json) => SessionModel(
        id: json['id'] as String,
        name: json['name'] as String,
        steps: List<Map<String, dynamic>>.from(json['steps'] as List),
        crossfadeSeconds: json['crossfadeSeconds'] as int? ?? 3,
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: DateTime.parse(json['updatedAt'] as String),
      );

  SessionModel copyWith({
    String? name,
    List<Map<String, dynamic>>? steps,
    int? crossfadeSeconds,
  }) =>
      SessionModel(
        id: id,
        name: name ?? this.name,
        steps: steps ?? this.steps,
        crossfadeSeconds: crossfadeSeconds ?? this.crossfadeSeconds,
        createdAt: createdAt,
        updatedAt: DateTime.now(),
      );
}

class SessionStorage implements SessionStorageBase {
  static final SessionStorage _instance = SessionStorage._internal();
  factory SessionStorage() => _instance;
  SessionStorage._internal();

  static const String _fileName = 'sessions.json';
  List<SessionModel>? _cachedSessions;

  Future<Directory> get _storageDir async {
    final dir = await getApplicationDocumentsDirectory();
    return dir;
  }

  Future<File> get _sessionsFile async {
    final dir = await _storageDir;
    return File('${dir.path}/$_fileName');
  }

  Future<List<SessionModel>> loadSessions() async {
    if (_cachedSessions != null) {
      return _cachedSessions!;
    }

    try {
      final file = await _sessionsFile;
      if (await file.exists()) {
        final content = await file.readAsString();
        final List<dynamic> jsonList = json.decode(content);
        _cachedSessions =
            jsonList.map((j) => SessionModel.fromJson(j)).toList();
        return _cachedSessions!;
      }
    } catch (e) {
      debugPrint('Error loading sessions: $e');
    }

    _cachedSessions = [];
    return _cachedSessions!;
  }

  Future<void> _saveSessions() async {
    if (_cachedSessions == null) return;

    try {
      final file = await _sessionsFile;
      final jsonList = _cachedSessions!.map((s) => s.toJson()).toList();
      await file.writeAsString(json.encode(jsonList));
    } catch (e) {
      debugPrint('Error saving sessions: $e');
    }
  }

  Future<SessionModel> saveSession({
    required String name,
    required List<Map<String, dynamic>> steps,
    int crossfadeSeconds = 3,
    String? existingId,
  }) async {
    await loadSessions();

    SessionModel session;

    if (existingId != null) {
      final index =
          _cachedSessions!.indexWhere((s) => s.id == existingId);
      if (index != -1) {
        session = _cachedSessions![index].copyWith(
          name: name,
          steps: steps,
          crossfadeSeconds: crossfadeSeconds,
        );
        _cachedSessions![index] = session;
      } else {
        session = SessionModel(
          id: DateTime.now().millisecondsSinceEpoch.toString(),
          name: name,
          steps: steps,
          crossfadeSeconds: crossfadeSeconds,
        );
        _cachedSessions!.add(session);
      }
    } else {
      session = SessionModel(
        id: DateTime.now().millisecondsSinceEpoch.toString(),
        name: name,
        steps: steps,
        crossfadeSeconds: crossfadeSeconds,
      );
      _cachedSessions!.add(session);
    }

    await _saveSessions();
    return session;
  }

  Future<void> deleteSession(String id) async {
    await loadSessions();
    _cachedSessions!.removeWhere((s) => s.id == id);
    await _saveSessions();
  }

  Future<void> reorderSession(int oldIndex, int newIndex) async {
    await loadSessions();
    if (oldIndex < newIndex) {
      newIndex -= 1;
    }
    final item = _cachedSessions!.removeAt(oldIndex);
    _cachedSessions!.insert(newIndex, item);
    await _saveSessions();
  }

  Future<String> exportSessionToJson(SessionModel session) async {
    return const JsonEncoder.withIndent('  ').convert(session.toJson());
  }

  Future<File> exportSessionToFile(SessionModel session) async {
    final dir = await _storageDir;
    final safeFileName = session.name.replaceAll(RegExp(r'[^\w\s-]'), '_');
    final file = File('${dir.path}/${safeFileName}_export.json');
    final jsonContent = await exportSessionToJson(session);
    await file.writeAsString(jsonContent);
    return file;
  }

  void clearCache() {
    _cachedSessions = null;
  }
}
