# Handoff Report — Explorer 2 for Milestone M2 (Iteration 2)

## 1. Observation

### Verified Context & Files Inspected
1. `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md` — Identified core requirement R2 (Production Guest Agent Loop in `guest/bridge-agent`).
2. `/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md` — Milestone M2 specification and layout.
3. `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md` — Defined scope boundary including `guest/bridge-agent/src/pty.rs`.
4. `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_r1_1/handoff.md` & `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_r1_2/handoff.md` — Challenger reports identifying Defects 1 and 2 in `src/pty.rs`.
5. `guest/bridge-agent-m2/src/pty.rs` — Implementation containing the defective PTY shell spawn and file descriptor handling logic.

### Verbatim Code Analysis of Defects

#### Defect 1: PTY IO Safety Violation in `spawn_shell`
- **Location**: `guest/bridge-agent-m2/src/pty.rs`, lines 104–116:
  ```rust
  let slave_file = std::fs::OpenOptions::new()
      .read(true)
      .write(true)
      .open(slave_path)?;

  let slave_fd = slave_file.as_raw_fd();

  let mut cmd = Command::new(shell);
  cmd.stdin(unsafe { Stdio::from_raw_fd(slave_fd) })
     .stdout(unsafe { Stdio::from_raw_fd(slave_fd) })
     .stderr(unsafe { Stdio::from_raw_fd(slave_fd) });

  cmd.spawn()
  ```
- **Observed Behavior & Error**: Connecting to PTY Port 5001 triggers an immediate process crash:
  ```text
  fatal runtime error: IO Safety violation: owned file descriptor already closed, aborting
  ```
  Process terminates with signal `-6` (SIGABRT).

#### Defect 2: File Descriptor Use-After-Close & Master/Slave Lifecycle in `PtyMaster`
- **Location**: `guest/bridge-agent-m2/src/pty.rs`, lines 41–95 (`PtyMaster` drop) and lines 130–163 (`_reader_thread`):
  ```rust
  impl Drop for PtyMaster {
      fn drop(&mut self) {
          unsafe { libc::close(self.master_fd); }
      }
  }

  let master_fd = pty.raw_fd();
  let _reader_thread = thread::spawn(move || {
      let mut pty_buf = [0u8; 4096];
      loop {
          let n = unsafe { libc::read(master_fd, pty_buf.as_mut_ptr() as *mut libc::c_void, pty_buf.len()) };
          if n <= 0 { break; }
          ...
      }
  });
  ```
- **Observed Behavior**: `master_fd` (raw integer `i32`) is copied into `_reader_thread`. When `handle_pty_session` terminates, `pty` drops and calls `libc::close(master_fd)`. Meanwhile `_reader_thread` continues looping in the background, executing `libc::read(master_fd, ...)` on a closed file descriptor integer. If another thread opens a new file descriptor that gets assigned the same integer, `_reader_thread` reads from an unrelated file/socket (FD recycling use-after-close race condition).

---

## 2. Logic Chain

### Logic Chain for Defect 1 (PTY IO Safety Violation)
1. `slave_file` opens `/dev/pts/N` and holds primitive integer `slave_fd` (e.g., FD `5`).
2. Rust 1.63+ introduced I/O Safety guarantees (`OwnedFd`). `Stdio::from_raw_fd(slave_fd)` constructs a `Stdio` object that takes exclusive ownership of file descriptor `5`.
3. Calling `Stdio::from_raw_fd(slave_fd)` 3 times (for `stdin`, `stdout`, `stderr`) creates 3 separate `Stdio` instances, each claiming exclusive ownership of the same file descriptor integer `5`. `slave_file` also claims ownership.
4. When `cmd.spawn()` executes and the stdio handles/files drop:
   - `slave_file` drops -> calls `close(5)`.
   - `stdin` drops -> calls `close(5)`.
   - `stdout` drops -> calls `close(5)`.
   - `stderr` drops -> calls `close(5)`.
5. The Rust standard library runtime detects that an `OwnedFd` attempted to close a file descriptor that was already closed, triggering `fatal runtime error: IO Safety violation: owned file descriptor already closed, aborting` and calling `std::process::abort()` (SIGABRT).

### Logic Chain for Defect 2 (FD Use-After-Close & Master/Slave Lifecycle)
1. In `handle_pty_session`, `PtyMaster::open()` returns `pty` containing `master_fd` (primitive `i32`, e.g. FD `4`).
2. `master_fd` integer is copied into `_reader_thread`, which loops executing `libc::read(master_fd, ...)`.
3. When `handle_pty_session` finishes (client disconnects or network stream closes), `pty` drops out of scope.
4. `PtyMaster::drop` executes `libc::close(master_fd)`, closing FD `4`.
5. `_reader_thread` is still running asynchronously in the background. Between `libc::close(4)` and `_reader_thread`'s next loop iteration, if any other thread in the process opens a socket or file, OS assigns FD `4` to that new socket/file.
6. `_reader_thread` invokes `libc::read(4, ...)` on the recycled FD `4`, reading data from an unrelated socket/file.
7. Furthermore, `_child` (`std::process::Child`) drops without calling `wait()` or `kill()`, resulting in a zombie shell process.

---

## 3. Caveats

1. **OS Environment Differences**: On Linux, closing master PTY FD causes `libc::read` on master PTY to return `-1` with `errno == EIO` (Input/output error). On macOS, closing master PTY can return `-1` with `EBADF`. The fix must handle all `n <= 0` conditions safely.
2. **Payload Bound Safety**: In addition to fixing FD bugs, `handle_pty_session` must bound `header.payload_len` (e.g. `MAX_PAYLOAD_LEN = 1 * 1024 * 1024` bytes) to prevent OOM panic vectors.
3. **Child Process Cleanup**: Explicitly sending `child.kill()` and `child.wait()` on session exit ensures child shell processes are reaped clean without leaving zombie processes.

---

## 4. Conclusion & Proposed Fix Design

### Summary of Fix Design
1. **Fix Defect 1 (`spawn_shell`)**: Duplicate `slave_fd` using `libc::dup(slave_fd)` 3 times (`stdin_fd`, `stdout_fd`, `stderr_fd`) before calling `Stdio::from_raw_fd`. Each stdio stream receives its own distinct owned file descriptor.
2. **Fix Defect 2 (Master FD Lifecycle)**: Duplicate `master_write_fd` using `libc::dup(master_write_fd)` to create `master_read_fd` for `reader_thread`. `reader_thread` owns `master_read_fd` and closes it when its loop finishes. When main thread drops `PtyMaster` (`master_write_fd`), kernel signals EOF/EIO to `master_read_fd`, allowing `reader_thread` to exit cleanly. Because `master_read_fd` remains a valid open descriptor owned by `reader_thread` until thread exit, no FD recycling race condition can occur.

### Complete Proposed Patch Code for `src/pty.rs`

```rust
use std::io::{self, Read, Write};
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;

pub const HEADER_SIZE: usize = 21;
pub const MSG_TYPE_DATA: u8 = 0x01;
pub const MSG_TYPE_RESIZE: u8 = 0x02;
pub const MSG_TYPE_PING: u8 = 0x03;

pub const MAX_PAYLOAD_LEN: u32 = 1024 * 1024; // 1 MB upper bound limit

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

    // Fix Defect 1: Duplicate slave_fd into distinct owned file descriptors for stdin, stdout, and stderr
    let stdin_fd = unsafe { libc::dup(slave_fd) };
    let stdout_fd = unsafe { libc::dup(slave_fd) };
    let stderr_fd = unsafe { libc::dup(slave_fd) };

    if stdin_fd < 0 || stdout_fd < 0 || stderr_fd < 0 {
        if stdin_fd >= 0 { unsafe { libc::close(stdin_fd); } }
        if stdout_fd >= 0 { unsafe { libc::close(stdout_fd); } }
        if stderr_fd >= 0 { unsafe { libc::close(stderr_fd); } }
        return Err(io::Error::last_os_error());
    }

    // slave_file drops when spawn_shell returns, closing slave_fd cleanly.
    // stdin_fd, stdout_fd, and stderr_fd remain valid distinct owned descriptors.
    let mut cmd = Command::new(shell);
    cmd.stdin(unsafe { Stdio::from_raw_fd(stdin_fd) })
       .stdout(unsafe { Stdio::from_raw_fd(stdout_fd) })
       .stderr(unsafe { Stdio::from_raw_fd(stderr_fd) });

    cmd.spawn()
}

pub fn handle_pty_session<S>(stream: S) -> io::Result<()>
where
    S: Read + Write + Send + 'static,
{
    let pty = match PtyMaster::open() {
        Ok(p) => p,
        Err(e) => return Err(e),
    };

    let slave_name = pty.slave_name()?;
    let mut child = spawn_shell(&slave_name)?;
    let master_write_fd = pty.raw_fd();

    // Fix Defect 2: Dup master_fd specifically for the background reader thread.
    let master_read_fd = unsafe { libc::dup(master_write_fd) };
    if master_read_fd < 0 {
        let _ = child.kill();
        let _ = child.wait();
        return Err(io::Error::last_os_error());
    }

    let stream_arc = Arc::new(Mutex::new(stream));
    let stream_writer = Arc::clone(&stream_arc);

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
        let mut guard = match stream_arc.lock() {
            Ok(g) => g,
            Err(_) => break,
        };
        if guard.read_exact(&mut header_buf).is_err() {
            break;
        }
        drop(guard);

        let header = PtyHeader::parse(&header_buf);
        if header.payload_len > MAX_PAYLOAD_LEN {
            eprintln!("[PTY] Payload length {} exceeds MAX_PAYLOAD_LEN", header.payload_len);
            break;
        }

        let mut payload = vec![0u8; header.payload_len as usize];
        if header.payload_len > 0 {
            let mut guard = match stream_arc.lock() {
                Ok(g) => g,
                Err(_) => break,
            };
            if guard.read_exact(&mut payload).is_err() {
                break;
            }
            drop(guard);
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
                let mut guard = match stream_arc.lock() {
                    Ok(g) => g,
                    Err(_) => break,
                };
                let _ = guard.write_all(&pong.encode());
            }
            _ => {}
        }
    }

    // Teardown: kill child process, drop pty master (closing master_write_fd)
    let _ = child.kill();
    let _ = child.wait();
    drop(pty);

    // Wait for reader thread to notice EOF/EIO and terminate cleanly
    let _ = reader_handle.join();

    Ok(())
}
```

---

## 5. Verification Method

### Command Verification
1. **Unit Testing**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   cargo test --manifest-path guest/bridge-agent/Cargo.toml
   ```
2. **Empirical Shell Spawn & Termination Test**:
   - Spawn PTY session, perform writes/reads, and terminate socket connection.
   - Assert exit code is `0` (no SIGABRT/SIGSEGV).
   - Assert `lsof` / FD table contains no leaked file descriptors.

### Invalidation Conditions
- If calling `Stdio::from_raw_fd` without prior `libc::dup()` occurs, Rust runtime will panic with `IO Safety violation`.
- If `master_read_fd` is closed before `reader_thread` finishes, `libc::read` on recycled FD integer will occur.
