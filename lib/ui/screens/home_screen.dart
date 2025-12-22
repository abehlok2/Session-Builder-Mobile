import 'package:flutter/material.dart';
import 'package:session_builder_mobile/src/rust/mobile_api.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String status = "Ready";

  Future<void> _startSession() async {
    try {
      // Temporary test JSON
      const trackJson = r'''
      {
        "global_settings": { "sample_rate": 44100 },
        "steps": [],
        "background_noise": null
      }
      ''';

      await startAudioSession(trackJson: trackJson, startTime: 0.0);
      setState(() => status = "Streaming Started");
    } catch (e) {
      setState(() => status = "Error: $e");
    }
  }

  Future<void> _stopSession() async {
    await stopAudioSession();
    setState(() => status = "Streaming Stopped");
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("Session Builder Mobile")),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(status, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _startSession,
              child: const Text("Start Session (Test)"),
            ),
            const SizedBox(height: 10),
            ElevatedButton(
              onPressed: _stopSession,
              child: const Text("Stop Session"),
            ),
          ],
        ),
      ),
    );
  }
}
