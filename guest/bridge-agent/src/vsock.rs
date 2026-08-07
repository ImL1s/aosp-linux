// guest/bridge-agent/src/vsock.rs
// Vsock IPC Connection module connecting to Host CID 2 across Ports 5000, 5001, 5002

use std::fs::File;
use std::os::unix::io::{FromRawFd, RawFd};

pub const CID_HOST: u32 = 2;
pub const PORT_CONTROL: u32 = 5000;
pub const PORT_PTY: u32 = 5001;
pub const PORT_WAYLAND: u32 = 5002;

pub const AF_VSOCK: i32 = 40;

#[repr(C)]
struct SockAddrVm {
    svm_family: u16,
    svm_reserved1: u16,
    svm_port: u32,
    svm_cid: u32,
    svm_zero: [u8; 4],
}

#[allow(dead_code)]
pub struct VsockEndpoint {
    pub cid: u32,
    pub port: u32,
}

#[allow(dead_code)]
impl VsockEndpoint {
    pub fn control() -> Self {
        Self { cid: CID_HOST, port: PORT_CONTROL }
    }

    pub fn pty() -> Self {
        Self { cid: CID_HOST, port: PORT_PTY }
    }

    pub fn wayland() -> Self {
        Self { cid: CID_HOST, port: PORT_WAYLAND }
    }
}

/// Connects to a Host Vsock endpoint (CID 2, Port) returning a File descriptor wrapper supporting Read/Write.
pub fn connect_vsock(cid: u32, port: u32) -> Result<File, String> {
    unsafe {
        let fd: RawFd = libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return Err(format!(
                "Failed to create AF_VSOCK socket: {}",
                std::io::Error::last_os_error()
            ));
        }

        let mut addr: SockAddrVm = std::mem::zeroed();
        addr.svm_family = AF_VSOCK as u16;
        addr.svm_cid = cid;
        addr.svm_port = port;

        let res = libc::connect(
            fd,
            &addr as *const _ as *const libc::sockaddr,
            std::mem::size_of::<SockAddrVm>() as u32,
        );

        if res < 0 {
            libc::close(fd);
            return Err(format!(
                "Failed to connect AF_VSOCK CID {} Port {}: {}",
                cid,
                port,
                std::io::Error::last_os_error()
            ));
        }

        Ok(File::from_raw_fd(fd))
    }
}
