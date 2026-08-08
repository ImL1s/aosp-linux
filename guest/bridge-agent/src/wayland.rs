use std::env;
use std::io::{self, Read, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::thread;
use crate::vsock::VsockStream;

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

pub fn handle_wayland_proxy(vsock_stream: VsockStream) -> io::Result<()> {
    let wayland_path = get_wayland_socket_path();
    let unix_stream = match UnixStream::connect(&wayland_path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Wayland proxy error: could not connect to {:?}: {}", wayland_path, e);
            return Err(e);
        }
    };

    let vsock_read = vsock_stream.try_clone()?;
    let vsock_write = vsock_stream;

    let unix_read = unix_stream.try_clone()?;
    let unix_write = unix_stream;

    proxy_split(vsock_read, unix_write, unix_read, vsock_write)
}

pub fn proxy_split<R1, W1, R2, W2>(
    mut r1: R1,
    mut w1: W1,
    mut r2: R2,
    mut w2: W2,
) -> io::Result<()>
where
    R1: Read + Send + 'static,
    W1: Write + Send + 'static,
    R2: Read + Send + 'static,
    W2: Write + Send + 'static,
{
    let t1 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match r1.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if w1.write_all(&buf[..n]).is_err() || w1.flush().is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    let t2 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match r2.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if w2.write_all(&buf[..n]).is_err() || w2.flush().is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    let _ = t1.join();
    let _ = t2.join();
    Ok(())
}

#[allow(dead_code)]
pub fn proxy_bi_directional<S1, S2>(stream1: S1, stream2: S2) -> io::Result<()>
where
    S1: Read + Write + Send + 'static,
    S2: Read + Write + Send + 'static,
{
    let (r1, w1) = (stream1, stream2);
    let t1 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        let mut r = r1;
        let mut w = w1;
        loop {
            match r.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if w.write_all(&buf[..n]).is_err() || w.flush().is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    let _ = t1.join();
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

    #[test]
    fn test_proxy_split_unix_stream_full_duplex() {
        let (s1_a, s1_b) = UnixStream::pair().expect("UnixStream pair 1 failed");
        let (s2_a, s2_b) = UnixStream::pair().expect("UnixStream pair 2 failed");

        let s1_read = s1_a.try_clone().unwrap();
        let s1_write = s1_a;
        let s2_read = s2_a.try_clone().unwrap();
        let s2_write = s2_a;

        let handle = thread::spawn(move || {
            proxy_split(s1_read, s2_write, s2_read, s1_write)
        });

        let mut b = s1_b;
        let mut c = s2_b;
        b.write_all(b"ping").unwrap();
        let mut buf = [0u8; 4];
        c.read_exact(&mut buf).unwrap();
        assert_eq!(&buf, b"ping");

        c.write_all(b"pong").unwrap();
        let mut buf2 = [0u8; 4];
        b.read_exact(&mut buf2).unwrap();
        assert_eq!(&buf2, b"pong");

        drop(b);
        drop(c);
        let _ = handle.join();
    }
}
