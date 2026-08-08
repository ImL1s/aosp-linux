use std::io::{self, Read, Write};
use std::sync::{Arc, Mutex, mpsc};
use std::thread;
use std::time::Duration;

// Dummy blocking stream to simulate network socket that waits for read
struct BlockingPipeRead {
    read_rx: Arc<Mutex<mpsc::Receiver<Vec<u8>>>>,
    write_tx: mpsc::Sender<Vec<u8>>,
}

impl Read for BlockingPipeRead {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let rx = self.read_rx.lock().unwrap();
        match rx.recv() {
            Ok(data) => {
                let n = data.len().min(buf.len());
                buf[..n].copy_from_slice(&data[..n]);
                Ok(n)
            }
            Err(_) => Ok(0),
        }
    }
}

impl Write for BlockingPipeRead {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.write_tx.send(buf.to_vec()).map_err(|_| io::ErrorKind::BrokenPipe.into())?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn create_pipe_pair() -> (BlockingPipeRead, BlockingPipeRead) {
    let (tx1, rx1) = mpsc::channel();
    let (tx2, rx2) = mpsc::channel();

    let s1 = BlockingPipeRead {
        read_rx: Arc::new(Mutex::new(rx1)),
        write_tx: tx2,
    };
    let s2 = BlockingPipeRead {
        read_rx: Arc::new(Mutex::new(rx2)),
        write_tx: tx1,
    };
    (s1, s2)
}

fn main() {
    println!("=== CHALLENGER EMPIRICAL HARNESS ===");

    // Test proxy_bi_directional deadlock:
    // Create socket 1 pair (client <-> vsock side) and socket 2 pair (wayland side <-> server)
    let (c_vsock, proxy_vsock) = create_pipe_pair();
    let (proxy_wayland, server_wayland) = create_pipe_pair();

    // Spawn proxy_bi_directional in background thread
    let h_proxy = thread::spawn(move || {
        bridge_agent::wayland::proxy_bi_directional(proxy_vsock, proxy_wayland)
    });

    println!("[Test Wayland Full-Duplex] Attempting server -> client write while client -> server is idle (waiting for read)...");
    
    // Server tries to send data to client via server_wayland
    let mut server = server_wayland;
    let mut client = c_vsock;

    let (done_tx, done_rx) = mpsc::channel();
    thread::spawn(move || {
        let res = server.write(b"WAYLAND_SERVER_EVENT");
        let _ = done_tx.send(res);
    });

    // Check if write completes within 1 second while client is idle (read is blocking)
    match done_rx.recv_timeout(Duration::from_millis(1500)) {
        Ok(Ok(_)) => {
            println!("[FAIL Deadlock Test] Write completed (no deadlock)");
        }
        Ok(Err(e)) => {
            println!("[FAIL Deadlock Test] Write error: {}", e);
        }
        Err(_) => {
            println!("[CONFIRMED BUG 3 DEADLOCK] Server write to proxy BLOCKED indefinitely because proxy_bi_directional holds s1 Mutex lock inside blocking read()!");
        }
    }
}
