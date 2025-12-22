use crate::models::{StepData, TrackData};
use crate::scheduler::StepVoice;
use crate::voices::voices_for_step;
use crossbeam::channel::{Receiver, Sender};
use std::collections::HashMap;
use std::thread;

pub struct LoadRequest {
    pub step_index: usize,
    pub step_data: StepData,
    pub sample_rate: f32,
    pub track_data: TrackData,
}

pub struct LoadResponse {
    pub step_index: usize,
    pub voices: Vec<StepVoice>,
}

pub struct VoiceLoader {
    request_rx: Receiver<LoadRequest>,
    response_tx: Sender<LoadResponse>,
}

impl VoiceLoader {
    pub fn new(request_rx: Receiver<LoadRequest>, response_tx: Sender<LoadResponse>) -> Self {
        Self {
            request_rx,
            response_tx,
        }
    }

    pub fn run(&self) {
        while let Ok(req) = self.request_rx.recv() {
            // This is the heavy lifting: creating voices (which may involve file I/O)
            let voices = voices_for_step(&req.step_data, req.sample_rate);
            
            // Send the result back to the audio thread
            let _ = self.response_tx.send(LoadResponse {
                step_index: req.step_index,
                voices,
            });
        }
    }
}

pub fn spawn_voice_loader() -> (Sender<LoadRequest>, Receiver<LoadResponse>) {
    let (req_tx, req_rx) = crossbeam::channel::unbounded();
    let (res_tx, res_rx) = crossbeam::channel::unbounded();

    thread::spawn(move || {
        let loader = VoiceLoader::new(req_rx, res_tx);
        loader.run();
    });

    (req_tx, res_rx)
}
