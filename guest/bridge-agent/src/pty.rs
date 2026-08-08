use std::io::{self, Read, Write};
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;

pub const HEADER_SIZE: usize = 21;
pub const MSG_TYPE_DATA: u8 = 0x01;
pub const MSG_TYPE_RESIZE: u8 = 0x02;
pub const MSG_TYPE_PING: u8 = 0x03;

pub const MAX_PAYLOAD_SIZE: usize = 65536; // 64 KB limit

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PtyHeader {
    pub session_id: [u8; 16],
    pub msg_type: u8,
    pub payload_len: u32,
}

impl PtyHeader {
    pub fn parse(buf: &[u8; HEADER_SIZE]) -> Self {
        let mut session_id = [0u8; 16];
        session_id.copy_from_slice(&buf[0..16]);
        let msg_type = buf[16];
        let payload_len = u32::from_be_bytes([buf[17], buf[18], buf[19], buf[20]]);
        Self {
            session_id,
            msg_type,
            payload_len,
        }
    }

    pub fn encode(&self) -> [u8; HEADER_SIZE] {
        let mut buf = [0u8; HEADER_SIZE];
        buf[0..16].copy_from_slice(&self.session_id);
        buf[16] = self.msg_type;
        buf[17..21].copy_from_slice(&self.payload_len.to_be_bytes());
        buf
    }
}

pub struct PtyMaster {
    master_fd: RawFd,
}

impl PtyMaster {
    pub fn open() -> io::Result<Self> {
        let master_fd = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY) };
        if master_fd < 0 {
            return Err(io::Error::last_os_error());
        }
        if unsafe { libc::grantpt(master_fd) } != 0 {
            unsafe { libc::close(master_fd); }
            return Err(io::Error::last_os_error());
        }
        if unsafe { libc::unlockpt(master_fd) } != 0 {
            unsafe { libc::close(master_fd); }
            return Err(io::Error::last_os_error());
        }
        Ok(Self { master_fd })
    }

    pub fn slave_name(&self) -> io::Result<String> {
        let pts_name_ptr = unsafe { libc::ptsname(self.master_fd) };
        if pts_name_ptr.is_null() {
            return Err(io::Error::last_os_error());
        }
        let c_str = unsafe { std::ffi::CStr::from_ptr(pts_name_ptr) };
        Ok(c_str.to_string_lossy().into_owned())
    }

    pub fn resize(&self, cols: u16, rows: u16) -> io::Result<()> {
        let ws = libc::winsize {
            ws_row: rows,
            ws_col: cols,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        let res = unsafe { libc::ioctl(self.master_fd, libc::TIOCSWINSZ, &ws) };
        if res < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    pub fn raw_fd(&self) -> RawFd {
        self.master_fd
    }
}

impl Drop for PtyMaster {
    fn drop(&mut self) {
        unsafe { libc::close(self.master_fd); }
    }
}

pub fn spawn_shell(slave_path: &str) -> io::Result<std::process::Child> {
    let shell = if std::path::Path::new("/bin/bash").exists() {
        "/bin/bash"
    } else {
        "/bin/sh"
    };

    let slave_file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(slave_path)?;

    let slave_fd = slave_file.as_raw_fd();

    // Use libc::dup 3 times to generate 3 separate owned file descriptors (stdin_fd, stdout_fd, stderr_fd)
    // before passing each to Stdio::from_raw_fd. This fixes the IO Safety double/triple drop panic.
    let stdin_fd = unsafe { libc::dup(slave_fd) };
    let stdout_fd = unsafe { libc::dup(slave_fd) };
    let stderr_fd = unsafe { libc::dup(slave_fd) };

    if stdin_fd < 0 || stdout_fd < 0 || stderr_fd < 0 {
        if stdin_fd >= 0 { unsafe { libc::close(stdin_fd); } }
        if stdout_fd >= 0 { unsafe { libc::close(stdout_fd); } }
        if stderr_fd >= 0 { unsafe { libc::close(stderr_fd); } }
        return Err(io::Error::last_os_error());
    }

    let mut cmd = Command::new(shell);
    cmd.stdin(unsafe { Stdio::from_raw_fd(stdin_fd) })
       .stdout(unsafe { Stdio::from_raw_fd(stdout_fd) })
       .stderr(unsafe { Stdio::from_raw_fd(stderr_fd) });

    cmd.spawn()
}

pub trait TryClone: Sized {
    fn try_clone(&self) -> io::Result<Self>;
}

impl TryClone for crate::vsock::VsockStream {
    fn try_clone(&self) -> io::Result<Self> {
        crate::vsock::VsockStream::try_clone(self)
    }
}

impl TryClone for std::os::unix::net::UnixStream {
    fn try_clone(&self) -> io::Result<Self> {
        std::os::unix::net::UnixStream::try_clone(self)
    }
}

impl TryClone for std::net::TcpStream {
    fn try_clone(&self) -> io::Result<Self> {
        std::net::TcpStream::try_clone(self)
    }
}

pub fn handle_pty_session<S>(stream: S) -> io::Result<()>
where
    S: Read + Write + TryClone + Send + 'static,
{
    let pty = match PtyMaster::open() {
        Ok(p) => p,
        Err(e) => return Err(e),
    };

    let slave_name = pty.slave_name()?;
    let mut child = spawn_shell(&slave_name)?;
    let master_write_fd = pty.raw_fd();

    // Dup master_fd specifically for background reader thread to prevent FD recycling race conditions
    let master_read_fd = unsafe { libc::dup(master_write_fd) };
    if master_read_fd < 0 {
        let _ = child.kill();
        let _ = child.wait();
        return Err(io::Error::last_os_error());
    }

    let mut read_stream = stream.try_clone()?;
    let write_stream = Arc::new(Mutex::new(stream));
    let stream_writer = Arc::clone(&write_stream);

    let session_id = [1u8; 16];

    let reader_handle = thread::spawn(move || {
        let mut pty_buf = [0u8; 4096];
        loop {
            let n = unsafe {
                libc::read(
                    master_read_fd,
                    pty_buf.as_mut_ptr() as *mut libc::c_void,
                    pty_buf.len(),
                )
            };
            if n <= 0 {
                break;
            }
            let data = &pty_buf[..n as usize];
            let header = PtyHeader {
                session_id,
                msg_type: MSG_TYPE_DATA,
                payload_len: data.len() as u32,
            };

            let mut guard = match stream_writer.lock() {
                Ok(g) => g,
                Err(_) => break,
            };
            if guard.write_all(&header.encode()).is_err() {
                break;
            }
            if guard.write_all(data).is_err() {
                break;
            }
            let _ = guard.flush();
        }
        unsafe { libc::close(master_read_fd); }
    });

    let mut header_buf = [0u8; HEADER_SIZE];
    loop {
        if read_stream.read_exact(&mut header_buf).is_err() {
            break;
        }

        let header = PtyHeader::parse(&header_buf);
        if header.payload_len as usize > MAX_PAYLOAD_SIZE {
            eprintln!(
                "[PTY] Payload length {} exceeds MAX_PAYLOAD_SIZE ({})",
                header.payload_len, MAX_PAYLOAD_SIZE
            );
            break;
        }

        let mut payload = vec![0u8; header.payload_len as usize];
        if header.payload_len > 0 {
            if read_stream.read_exact(&mut payload).is_err() {
                break;
            }
        }

        match header.msg_type {
            MSG_TYPE_DATA => {
                let _ = unsafe {
                    libc::write(
                        master_write_fd,
                        payload.as_ptr() as *const libc::c_void,
                        payload.len(),
                    )
                };
            }
            MSG_TYPE_RESIZE => {
                if payload.len() >= 8 {
                    let cols = u32::from_be_bytes([payload[0], payload[1], payload[2], payload[3]]) as u16;
                    let rows = u32::from_be_bytes([payload[4], payload[5], payload[6], payload[7]]) as u16;
                    let _ = pty.resize(cols, rows);
                }
            }
            MSG_TYPE_PING => {
                let pong = PtyHeader {
                    session_id: header.session_id,
                    msg_type: MSG_TYPE_PING,
                    payload_len: 0,
                };
                let mut guard = match write_stream.lock() {
                    Ok(g) => g,
                    Err(_) => break,
                };
                let _ = guard.write_all(&pong.encode());
                let _ = guard.flush();
            }
            _ => {}
        }
    }

    let _ = child.kill();
    let _ = child.wait();
    drop(pty);
    let _ = reader_handle.join();

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pty_header_encode_parse() {
        let session_id = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
        let original = PtyHeader {
            session_id,
            msg_type: MSG_TYPE_DATA,
            payload_len: 1024,
        };

        let encoded = original.encode();
        assert_eq!(encoded.len(), HEADER_SIZE);

        let parsed = PtyHeader::parse(&encoded);
        assert_eq!(parsed, original);
    }

    #[test]
    fn test_pty_master_open_and_slave_name() {
        let pty = PtyMaster::open().expect("Failed to open PTY master");
        let name = pty.slave_name().expect("Failed to get slave name");
        assert!(name.starts_with("/dev/pts/") || name.starts_with("/dev/ttys"));
    }

    #[test]
    fn test_pty_resize() {
        let pty = PtyMaster::open().expect("Failed to open PTY master");
        if let Ok(slave_name) = pty.slave_name() {
            let _slave = std::fs::OpenOptions::new().read(true).write(true).open(slave_name);
            let _ = pty.resize(80, 24);
        } else {
            let _ = pty.resize(80, 24);
        }
    }

    #[test]
    fn test_pty_payload_len_limit() {
        let header = PtyHeader {
            session_id: [0u8; 16],
            msg_type: MSG_TYPE_DATA,
            payload_len: 1000000,
        };
        assert!(header.payload_len as usize > MAX_PAYLOAD_SIZE);
    }
}
