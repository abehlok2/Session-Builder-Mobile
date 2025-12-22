import Flutter
import UIKit
import AVFoundation

@main
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    // Configure audio session for background playback
    configureAudioSession()

    GeneratedPluginRegistrant.register(with: self)
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  private func configureAudioSession() {
    let audioSession = AVAudioSession.sharedInstance()
    do {
      // Set category to playback for background audio
      try audioSession.setCategory(
        .playback,
        mode: .default,
        options: [.mixWithOthers, .allowAirPlay, .allowBluetooth, .allowBluetoothA2DP]
      )
      // Activate the audio session
      try audioSession.setActive(true)
    } catch {
      print("Failed to configure audio session: \(error.localizedDescription)")
    }
  }

  // Handle audio interruptions (phone calls, etc.)
  override func applicationWillResignActive(_ application: UIApplication) {
    // App is about to become inactive
    super.applicationWillResignActive(application)
  }

  override func applicationDidBecomeActive(_ application: UIApplication) {
    // App became active again - reactivate audio session if needed
    let audioSession = AVAudioSession.sharedInstance()
    do {
      try audioSession.setActive(true)
    } catch {
      print("Failed to reactivate audio session: \(error.localizedDescription)")
    }
    super.applicationDidBecomeActive(application)
  }
}
