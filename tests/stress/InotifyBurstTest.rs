// tests/stress/InotifyBurstTest.rs
// Empirical burst stress test for inotify_watcher.rs

use std::fs::{self, File};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

#[path = "../../guest/portal-agent/src/inotify_watcher.rs"]
mod inotify_watcher;

use inotify_watcher::{InotifyEvent, InotifyWatcher};

fn main() {
    println!("=== Running Empirical Inotify Burst Stress Test ===");

    // Create a temporary test directory
    let temp_dir = std::env::temp_dir().join("aosp_inotify_burst_test");
    let _ = fs::remove_dir_all(&temp_dir);
    fs::create_dir_all(&temp_dir).expect("Failed to create temp_dir");

    let event_count = Arc::new(AtomicUsize::new(0));
    let event_count_clone = Arc::clone(&event_count);

    let watcher = InotifyWatcher::new(vec![temp_dir.clone()]);
    watcher.start_watching(move |event| {
        match event {
            InotifyEvent::CreatedOrModified(path) => {
                println!("[TEST EVENT] CreatedOrModified: {:?}", path.file_name().unwrap());
                event_count_clone.fetch_add(1, Ordering::SeqCst);
            }
            InotifyEvent::Deleted(path) => {
                println!("[TEST EVENT] Deleted: {:?}", path.file_name().unwrap());
                event_count_clone.fetch_add(1, Ordering::SeqCst);
            }
        }
    });

    // Wait for watcher thread to initialize
    thread::sleep(Duration::from_millis(200));

    // Scenario 1: Burst file creations (10 distinct .desktop files created in rapid burst)
    println!("[TEST STEP 1] Firing burst creation of 10 .desktop files...");
    for i in 0..10 {
        let file_path = temp_dir.join(format!("burst_app_{}.desktop", i));
        let mut file = File::create(&file_path).expect("Failed to create file");
        writeln!(file, "[Desktop Entry]\nName=Burst App {}\nExec=app{}", i, i).unwrap();
    }

    // Wait for consumer debouncer loop (poll interval + debounce window)
    thread::sleep(Duration::from_millis(500));

    let count_after_burst = event_count.load(Ordering::SeqCst);
    println!("[TEST STEP 1 RESULT] Events received: {}", count_after_burst);
    assert!(count_after_burst >= 10, "Expected at least 10 events for 10 distinct desktop files, got {}", count_after_burst);

    // Scenario 2: Rapid burst modifications on a single file (10 writes to same file in 5ms)
    println!("[TEST STEP 2] Rapid burst writes to a single file...");
    let single_file = temp_dir.join("single_burst.desktop");
    let initial_count = event_count.load(Ordering::SeqCst);
    for i in 0..10 {
        let mut file = File::create(&single_file).expect("Failed to open file");
        writeln!(file, "[Desktop Entry]\nName=Single Burst {}\nExec=single", i).unwrap();
        thread::sleep(Duration::from_millis(1));
    }

    thread::sleep(Duration::from_millis(500));

    let final_count = event_count.load(Ordering::SeqCst);
    let single_burst_events = final_count - initial_count;
    println!("[TEST STEP 2 RESULT] Events received for 10 rapid writes: {}", single_burst_events);
    assert!(single_burst_events >= 1, "Expected at least 1 event for rapid burst writes, got {}", single_burst_events);

    // Cleanup
    let _ = fs::remove_dir_all(&temp_dir);
    println!("=== ALL Inotify Burst Stress Tests PASSED ===");
}
