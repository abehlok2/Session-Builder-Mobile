package com.binauralbuilder.session_builder_mobile

import com.ryanheise.audioservice.AudioServiceActivity

class MainActivity : AudioServiceActivity() {
    init {
        System.loadLibrary("realtime_backend")
    }
}
