import 'package:flutter/material.dart';
import 'package:session_builder_mobile/src/rust/frb_generated.dart';
import 'package:session_builder_mobile/ui/screens/home_screen.dart';
import 'package:session_builder_mobile/ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await RustLib.init();
  runApp(const MyApp());
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
