import 'package:flutter/material.dart';
// import 'package:session_builder_mobile/src/kotlin/mobile_api.dart';
import 'package:session_builder_mobile/ui/screens/step_list_screen.dart';
import 'package:session_builder_mobile/ui/screens/saved_sessions_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String status = "Ready";

  // Future<void> _startSession() async {
  //   try {
  //     // Temporary test JSON
  //     const trackJson = r'''
  //     {
  //       "global_settings": { "sample_rate": 44100 },
  //       "steps": [],
  //       "background_noise": null
  //     }
  //     ''';

  //     await startAudioSession(trackJson: trackJson, startTime: 0.0);
  //     setState(() => status = "Streaming Started");
  //   } catch (e) {
  //     setState(() => status = "Error: $e");
  //   }
  // }

  // Future<void> _stopSession() async {
  //   await stopAudioSession();
  //   setState(() => status = "Streaming Stopped");
  // }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Container(
            decoration: BoxDecoration(
              border: Border.all(color: Colors.white, width: 2),
              borderRadius: BorderRadius.circular(30),
            ),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 20),
                const Text(
                  "Session Builder",
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 24,
                    fontWeight: FontWeight.w400,
                    letterSpacing: 1.2,
                  ),
                ),
                const SizedBox(height: 15),
                const Divider(color: Colors.white, thickness: 1),
                const SizedBox(height: 15),
                const Text(
                  "From Alex-Entrainment",
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Colors.white70,
                    fontSize: 14,
                    fontWeight: FontWeight.w300,
                  ),
                ),
                const Spacer(),
                _buildMenuButton(
                  title: "New Session",
                  onPressed: () {
                    Navigator.of(context).push(
                      MaterialPageRoute(builder: (_) => const StepListScreen()),
                    );
                  },
                ),
                const SizedBox(height: 40),
                _buildMenuButton(
                  title: "Load Session",
                  onPressed: () {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const SavedSessionsScreen(),
                      ),
                    );
                  },
                ),
                const Spacer(),
                // Debug status
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: Text(
                    status,
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.white24, fontSize: 10),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMenuButton({
    required String title,
    required VoidCallback onPressed,
  }) {
    return SizedBox(
      height: 60,
      child: OutlinedButton(
        onPressed: onPressed,
        style: OutlinedButton.styleFrom(
          side: const BorderSide(color: Colors.white, width: 1.5),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(15),
          ),
          foregroundColor: Colors.white,
        ),
        child: Text(
          title,
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w400),
        ),
      ),
    );
  }
}
