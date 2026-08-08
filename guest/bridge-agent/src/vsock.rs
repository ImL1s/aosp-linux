use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};

pub const VMADDR_CID_ANY: u32 = 0xFFFFFFFF;
pub const VMADDR_CID_HOST: u32 = 2;

pub const PORT_PORTAL: u32 = 5000;
pub const PORT_PTY: u32 = 5001;
pub const PORT_WAYLAND: u32 = 5002;

#[cfg(target_os = "linux")]
const AF_VSOCK: i32 = 37;

#[cfg(target_os = "linux")]
#[repr(C)]
struct sockaddr_vm {
    svm_family: u16,
    svm_reserved1: u16,
    svm_port: u32,
    svm_cid: u32,
    svm_zero: [u8; 4],
}

pub enum VsockStream {
    #[allow(dead_code)]
    Vsock(libc::c_int),
    Tcp(TcpStream),
}

impl VsockStream {
    pub fn try_clone(&self) -> io::Result<Self> {
        match self {
            VsockStream::Vsock(fd) => {
                let dup_fd = unsafe { libc::dup(*fd) };
                if dup_fd < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(VsockStream::Vsock(dup_fd))
                }
            }
            VsockStream::Tcp(s) => Ok(VsockStream::Tcp(s.try_clone()?)),
        }
    }

    pub fn set_read_timeout(&self, timeout: Option<std::time::Duration>) -> io::Result<()> {
        match self {
            VsockStream::Vsock(fd) => {
                let timeval = match timeout {
                    Some(dur) => libc::timeval {
                        tv_sec: dur.as_secs() as libc::time_t,
                        tv_usec: dur.subsec_micros() as libc::suseconds_t,
                    },
                    None => libc::timeval { tv_sec: 0, tv_usec: 0 },
                };
                let res = unsafe {
                    libc::setsockopt(
                        *fd,
                        libc::SOL_SOCKET,
                        libc::SO_RCVTIMEO,
                        &timeval as *const _ as *const libc::c_void,
                        std::mem::size_of::<libc::timeval>() as libc::socklen_t,
                    )
                };
                if res < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(())
                }
            }
            VsockStream::Tcp(s) => s.set_read_timeout(timeout),
        }
    }
}

impl Read for VsockStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            VsockStream::Vsock(fd) => {
                let res = unsafe { libc::read(*fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
                if res < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(res as usize)
                }
            }
            VsockStream::Tcp(s) => s.read(buf),
        }
    }
}

impl Write for VsockStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            VsockStream::Vsock(fd) => {
                let res = unsafe { libc::write(*fd, buf.as_ptr() as *const libc::c_void, buf.len()) };
                if res < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(res as usize)
                }
            }
            VsockStream::Tcp(s) => s.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            VsockStream::Vsock(_) => Ok(()),
            VsockStream::Tcp(s) => s.flush(),
        }
    }
}

impl Drop for VsockStream {
    fn drop(&mut self) {
        if let VsockStream::Vsock(fd) = self {
            if *fd >= 0 {
                unsafe { libc::close(*fd); }
            }
        }
    }
}

pub enum VsockListener {
    #[allow(dead_code)]
    Vsock(libc::c_int, u32),
    Tcp(TcpListener, u32),
}

impl VsockListener {
    pub fn bind(_cid: u32, port: u32) -> io::Result<Self> {
        #[cfg(target_os = "linux")]
        {
            let fd = unsafe { libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0) };
            if fd >= 0 {
                let mut sa: sockaddr_vm = unsafe { std::mem::zeroed() };
                sa.svm_family = AF_VSOCK as u16;
                sa.svm_cid = _cid;
                sa.svm_port = port;

                let res = unsafe {
                    libc::bind(
                        fd,
                        &sa as *const _ as *const libc::sockaddr,
                        std::mem::size_of::<sockaddr_vm>() as libc::socklen_t,
                    )
                };

                if res == 0 {
                    let listen_res = unsafe { libc::listen(fd, 128) };
                    if listen_res == 0 {
                        return Ok(VsockListener::Vsock(fd, port));
                    }
                }
                unsafe { libc::close(fd); }
            }
        }

        let addr = format!("127.0.0.1:{}", port);
        let listener = TcpListener::bind(&addr)?;
        Ok(VsockListener::Tcp(listener, port))
    }

    pub fn accept(&self) -> io::Result<(VsockStream, u32)> {
        match self {
            VsockListener::Vsock(fd, _port) => {
                #[cfg(target_os = "linux")]
                {
                    let mut sa: sockaddr_vm = unsafe { std::mem::zeroed() };
                    let mut len = std::mem::size_of::<sockaddr_vm>() as libc::socklen_t;
                    let client_fd = unsafe {
                        libc::accept(
                            *fd,
                            &mut sa as *mut _ as *mut libc::sockaddr,
                            &mut len,
                        )
                    };
                    if client_fd < 0 {
                        Err(io::Error::last_os_error())
                    } else {
                        Ok((VsockStream::Vsock(client_fd), sa.svm_cid))
                    }
                }
                #[cfg(not(target_os = "linux"))]
                {
                    let _ = fd;
                    Err(io::Error::new(io::ErrorKind::Other, "Vsock raw socket accept not supported on non-linux target"))
                }
            }
            VsockListener::Tcp(listener, _port) => {
                let (stream, _addr) = listener.accept()?;
                Ok((VsockStream::Tcp(stream), VMADDR_CID_HOST))
            }
        }
    }

    #[allow(dead_code)]
    pub fn port(&self) -> u32 {
        match self {
            VsockListener::Vsock(_, p) => *p,
            VsockListener::Tcp(_, p) => *p,
        }
    }
}

impl Drop for VsockListener {
    fn drop(&mut self) {
        if let VsockListener::Vsock(fd, _) = self {
            if *fd >= 0 {
                unsafe { libc::close(*fd); }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vsock_listener_bind_free_port() {
        let listener = VsockListener::bind(VMADDR_CID_ANY, 0).expect("Should bind to dynamic port");
        assert_eq!(listener.port(), 0);
    }
}
