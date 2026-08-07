// guest/portal-agent/src/inotify_watcher.rs
// Inotify directory watcher for /usr/share/applications/ and ~/.local/share/applications/ (F-R4-005)

use std::collections::{HashMap, HashSet};
use std::ffi::CString;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{channel, Sender};
use std::thread;
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum InotifyEvent {
    CreatedOrModified(PathBuf),
    Deleted(PathBuf),
}

pub struct InotifyWatcher {
    watch_paths: Vec<PathBuf>,
}

impl InotifyWatcher {
    pub fn new(paths: Vec<PathBuf>) -> Self {
        Self { watch_paths: paths }
    }

    pub fn start_watching<F>(&self, callback: F)
    where
        F: Fn(InotifyEvent) + Send + 'static,
    {
        let (tx, rx) = channel::<InotifyEvent>();
        let paths = self.watch_paths.clone();

        // Spawn producer thread that owns tx and monitors filesystem
        let tx_producer = tx.clone();
        thread::spawn(move || {
            #[cfg(target_os = "linux")]
            {
                unsafe {
                    let fd = libc::inotify_init1(libc::IN_CLOEXEC);
                    if fd >= 0 {
                        let mut wd_map = HashMap::new();
                        for path in &paths {
                            if let Ok(c_path) = CString::new(path.as_os_str().as_bytes()) {
                                let flags = libc::IN_CREATE
                                    | libc::IN_MODIFY
                                    | libc::IN_CLOSE_WRITE
                                    | libc::IN_DELETE
                                    | libc::IN_MOVED_TO
                                    | libc::IN_MOVED_FROM;
                                let wd = libc::inotify_add_watch(fd, c_path.as_ptr(), flags);
                                if wd >= 0 {
                                    println!("[portal-agent] Added inotify watch on {:?} (wd={})", path, wd);
                                    wd_map.insert(wd, path.clone());
                                }
                            }
                        }

                        let mut buffer = [0u8; 4096];
                        loop {
                            let bytes_read = libc::read(
                                fd,
                                buffer.as_mut_ptr() as *mut libc::c_void,
                                buffer.len(),
                            );
                            if bytes_read <= 0 {
                                thread::sleep(Duration::from_millis(100));
                                continue;
                            }

                            let mut offset = 0;
                            while offset < bytes_read as usize {
                                if offset + std::mem::size_of::<libc::inotify_event>() > bytes_read as usize {
                                    break;
                                }
                                let event_ptr = buffer.as_ptr().add(offset) as *const libc::inotify_event;
                                let event = &*event_ptr;

                                let name_len = event.len as usize;
                                if name_len > 0
                                    && offset + std::mem::size_of::<libc::inotify_event>() + name_len <= bytes_read as usize
                                {
                                    let name_bytes = std::slice::from_raw_parts(
                                        buffer.as_ptr().add(offset + std::mem::size_of::<libc::inotify_event>()),
                                        name_len,
                                    );
                                    let nul_pos = name_bytes.iter().position(|&b| b == 0).unwrap_or(name_bytes.len());
                                    if let Ok(name_str) = std::str::from_utf8(&name_bytes[..nul_pos]) {
                                        if name_str.ends_with(".desktop") {
                                            if let Some(parent_path) = wd_map.get(&event.wd) {
                                                let full_path = parent_path.join(name_str);
                                                let is_delete = (event.mask & (libc::IN_DELETE | libc::IN_MOVED_FROM)) != 0;
                                                let ev = if is_delete {
                                                    InotifyEvent::Deleted(full_path)
                                                } else {
                                                    InotifyEvent::CreatedOrModified(full_path)
                                                };
                                                let _ = tx_producer.send(ev);
                                            }
                                        }
                                    }
                                }
                                offset += std::mem::size_of::<libc::inotify_event>() + name_len;
                            }
                        }
                    }
                }
            }

            #[cfg(not(target_os = "linux"))]
            {
                println!("[portal-agent] Directory watcher initialized for {:?}", paths);
                let mut known_files: HashMap<PathBuf, std::time::SystemTime> = HashMap::new();
                loop {
                    for dir in &paths {
                        if let Ok(entries) = std::fs::read_dir(dir) {
                            for entry in entries.flatten() {
                                let path = entry.path();
                                if path.extension().map_or(false, |ext| ext == "desktop") {
                                    if let Ok(meta) = entry.metadata() {
                                        if let Ok(mtime) = meta.modified() {
                                            match known_files.get(&path) {
                                                Some(&prev_time) if prev_time == mtime => {}
                                                _ => {
                                                    known_files.insert(path.clone(), mtime);
                                                    let _ = tx_producer.send(InotifyEvent::CreatedOrModified(path));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    thread::sleep(Duration::from_millis(100));
                }
            }
        });

        // Spawn consumer / debouncer thread
        thread::spawn(move || {
            let mut pending_events = HashSet::new();
            loop {
                thread::sleep(Duration::from_millis(50));
                while let Ok(event) = rx.try_recv() {
                    pending_events.insert(event);
                }
                for event in pending_events.drain() {
                    callback(event);
                }
            }
        });
    }
}
