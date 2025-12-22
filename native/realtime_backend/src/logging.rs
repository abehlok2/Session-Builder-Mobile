use std::panic;
use std::sync::Once;

static INIT: Once = Once::new();

pub fn init_logging() {
    INIT.call_once(|| {
        // Platform-specific logger initialization
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Debug)
                    .with_tag("RustBackend"),
            );
        }

        #[cfg(target_os = "ios")]
        {
            let _ = oslog::OsLogger::new("com.example.sessionbuilder")
                .level_filter(log::LevelFilter::Debug)
                .init();
        }

        #[cfg(not(any(target_os = "android", target_os = "ios")))]
        {
            // Fallback for other platforms (desktop, tests)
            // You might want env_logger or similar here if not already handled
            // For now, standard flutter_rust_bridge console output or println! is generic enough,
            // but we can try to init a simple logger if needed.
            // Since we don't have env_logger in dependencies yet, we might skip or rely on stdout.
        }

        // Set a custom panic hook
        set_panic_hook();
        
        log::info!("Logging initialized successfully");
    });
}

fn set_panic_hook() {
    let default_hook = panic::take_hook();
    panic::set_hook(Box::new(move |panic_info| {
        // 1. Get the panic message
        let payload = panic_info.payload();
        let msg = if let Some(s) = payload.downcast_ref::<&str>() {
            *s
        } else if let Some(s) = payload.downcast_ref::<String>() {
            s.as_str()
        } else {
            "Box<Any>"
        };

        // 2. Get the location
        let location = panic_info.location().map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
            .unwrap_or_else(|| "unknown location".to_string());

        // 3. Capture backtrace
        let bt = backtrace::Backtrace::new();

        // 4. Log the error (this goes to Logcat/Console via the log crate)
        log::error!(
            "RUST PANIC CAUGHT!\nMessage: {}\nLocation: {}\nBacktrace:\n{:?}",
            msg,
            location,
            bt
        );

        // 5. Chain to the default hook (prints to stderr/stdout which might also be captured or visible)
        default_hook(panic_info);
    }));
}
