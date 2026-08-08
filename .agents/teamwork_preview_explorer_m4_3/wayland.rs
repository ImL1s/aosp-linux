use std::env;
use std::io::{self, Read, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;

pub fn get_wayland_socket_path() -> PathBuf {
    if let Ok(path) = env::var("WAYLAND_DISPLAY") {
        if path.starts_with('/') {
            return PathBuf::from(path);
        } else if let Ok(runtime_dir) = env::var("XDG_RUNTIME_DIR") {
            return PathBuf::from(runtime_dir).join(path);
        }
    }
    if let Ok(runtime_dir) = env::var("XDG_RUNTIME_DIR") {
        let p = PathBuf::from(runtime_dir).join("wayland-0");
        if p.exists() {
            return p;
        }
    }
    let default_p = PathBuf::from("/run/user/1000/wayland-0");
    if default_p.exists() {
        return default_p;
    }
    PathBuf::from("/tmp/wayland-0")
}

pub fn handle_wayland_proxy<S>(vsock_stream: S) -> io::Result<()>
where
    S: Read + Write + Send + 'static,
{
    let wayland_path = get_wayland_socket_path();
    let unix_stream = match UnixStream::connect(&wayland_path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Wayland proxy error: could not connect to {:?}: {}", wayland_path, e);
            return Err(e);
        }
    };

    proxy_bi_directional(vsock_stream, unix_stream)
}

pub fn proxy_bi_directional<S1, S2>(stream1: S1, stream2: S2) -> io::Result<()>
where
    S1: Read + Write + Send + 'static,
    S2: Read + Write + Send + 'static,
{
    let s1 = Arc::new(Mutex::new(stream1));
    let s2 = Arc::new(Mutex::new(stream2));

    let (s1_read, s2_write) = (Arc::clone(&s1), Arc::clone(&s2));
    let t1 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            let n = match s1_read.lock() {
                Ok(mut r) => match r.read(&mut buf) {
                    Ok(0) => break,
                    Ok(n) => n,
                    Err(_) => break,
                },
                Err(_) => break,
            };

            let mut w = match s2_write.lock() {
                Ok(w) => w,
                Err(_) => break,
            };
            if w.write_all(&buf[..n]).is_err() {
                break;
            }
            let _ = w.flush();
        }
    });

    let (s2_read, s1_write) = (Arc::clone(&s2), Arc::clone(&s1));
    let t2 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            let n = match s2_read.lock() {
                Ok(mut r) => match r.read(&mut buf) {
                    Ok(0) => break,
                    Ok(n) => n,
                    Err(_) => break,
                },
                Err(_) => break,
            };

            let mut w = match s1_write.lock() {
                Ok(w) => w,
                Err(_) => break,
            };
            if w.write_all(&buf[..n]).is_err() {
                break;
            }
            let _ = w.flush();
        }
    });

    let _ = t1.join();
    let _ = t2.join();

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn test_get_wayland_socket_path_default() {
        let path = get_wayland_socket_path();
        assert!(!path.as_os_str().is_empty());
    }

    #[test]
    fn test_proxy_bi_directional() {
        let c1 = Cursor::new(b"hello from vsock".to_vec());
        let c2 = Cursor::new(b"hello from wayland".to_vec());
        let res = proxy_bi_directional(c1, c2);
        assert!(res.is_ok());
    }
}
