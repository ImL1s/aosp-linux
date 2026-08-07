// guest/portal-agent/src/main.rs
// Guest Inotify Monitor Daemon for /usr/share/applications/ and ~/.local/share/applications/ (F-R4-005)

mod desktop_parser;
mod inotify_watcher;

use desktop_parser::{parse_desktop_file, DesktopAppInfo};
use inotify_watcher::{InotifyEvent, InotifyWatcher};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("[portal-agent] Starting Guest Desktop Entry Inotify Monitor Daemon...");

    let watch_paths = vec![
        PathBuf::from("/usr/share/applications/"),
        PathBuf::from("/home/user/.local/share/applications/"),
    ];

    let watcher = InotifyWatcher::new(watch_paths);
    watcher.start_watching(|event| match event {
        InotifyEvent::CreatedOrModified(path) => {
            println!("[portal-agent] File created/modified: {:?}", path);
            if let Ok(Some(app_info)) = parse_desktop_file(&path) {
                println!("[portal-agent] Parsed app metadata: {} ({})", app_info.name, app_info.app_id);
                transmit_app_sync_to_host(&app_info);
            }
        }
        InotifyEvent::Deleted(path) => {
            println!("[portal-agent] File deleted: {:?}", path);
            transmit_app_delete_to_host(&path);
        }
    });

    loop {
        thread::sleep(Duration::from_secs(10));
    }
}

fn transmit_app_sync_to_host(app_info: &DesktopAppInfo) {
    let payload = serde_json::to_string(app_info).unwrap_or_default();
    println!("[portal-agent] Transmitting CMD_APP_SYNC to Host Vsock 5000: {}", payload);
}

fn transmit_app_delete_to_host(path: &Path) {
    let app_id = path.file_stem().and_then(|s| s.to_str()).unwrap_or("unknown");
    println!("[portal-agent] Transmitting CMD_APP_DELETE to Host Vsock 5000: {}", app_id);
}
