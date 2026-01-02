import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:session_builder_mobile/data/presets_repository.dart';
import 'package:session_builder_mobile/services/audio_handler.dart';
import 'package:session_builder_mobile/services/audio_session_service.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart';
import 'package:session_builder_mobile/ui/screens/home_screen.dart';
import 'package:session_builder_mobile/ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize Kotlin audio backend
  await initAudioEngine();

  // Initialize audio service early for foreground playback controls
  await AudioHandlerService.instance.init();

  // Configure audio session for background playback
  await AudioSessionService.instance.configure();

  // Load presets
  await PresetsRepository().loadPresets();
  runApp(const ProviderScope(child: MyApp()));
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Session Builder',
      theme: AppTheme.darkTheme,
      home: const HomeScreen(),
    );
  }
}
