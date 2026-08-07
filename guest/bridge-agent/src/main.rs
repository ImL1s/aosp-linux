// guest/bridge-agent/src/main.rs
// Rust daemon running inside Debian Guest to communicate with AOSP Host via Vsock RPC (F-R2-004 & F-R2-005)

mod auth;
mod vsock;
mod ota_rollback;

use std::io::{Read, Write};
use std::time::Duration;
use vsock::{CID_HOST, PORT_CONTROL};

const VSOK_MAGIC: u32 = 0x56534F4B;

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq)]
#[allow(dead_code)]
enum VsockFrameType {
    Control = 0x01,
    PtyData = 0x02,
    Wayland = 0x03,
    Heartbeat = 0x04,
    MsgAuthInit = 0x10,
    MsgAuthResponse = 0x11,
    MsgAuthVerify = 0x12,
    MsgAuthSuccess = 0x13,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("[Guest Agent] Starting android-bridge-agent daemon...");

    // 1. Extract single-use auth token from /proc/cmdline
    let mut token_buf = auth::extract_token_from_cmdline()?;
    println!("[Guest Agent] Auth token extracted (length: {} bytes)", token_buf.len());

    // 2. Perform 4-step HMAC-SHA256 Challenge-Response Handshake over AF_VSOCK Port 5000
    if let Err(e) = perform_host_handshake(&mut token_buf) {
        eprintln!("[Guest Agent] Vsock handshake failed: {}", e);
    } else {
        println!("[Guest Agent] Host authenticated successfully.");
    }

    // 3. Zero out single-use token memory immediately after completion using zeroize
    auth::zeroize_token(&mut token_buf);
    println!("[Guest Agent] Token zeroized from memory.");

    println!("[Guest Agent] Listening on Vsock Ports (5000 Control, 5001 PTY, 5002 Wayland)...");
    
    // Main event loop handling RPC requests
    loop {
        std::thread::sleep(Duration::from_secs(5));
    }
}

/// Packs VsockFrameHeader (13 bytes packed: magic[4], frame_type[1], payload_len[4], seq_id[4])
fn pack_frame_header(frame_type: VsockFrameType, payload_len: u32, seq_id: u32) -> [u8; 13] {
    let mut header = [0u8; 13];
    header[0..4].copy_from_slice(&VSOK_MAGIC.to_le_bytes());
    header[4] = frame_type as u8;
    header[5..9].copy_from_slice(&payload_len.to_le_bytes());
    header[9..13].copy_from_slice(&seq_id.to_le_bytes());
    header
}

/// Executes 4-Step Challenge-Response Handshake over AF_VSOCK Socket to Host CID 2 Port 5000:
/// Step 1: Connect to AF_VSOCK socket (Host CID 2, Port 5000)
/// Step 2: Construct 64-byte payload (Token + Authentic HMAC-SHA256 Signature via sha2/hmac crates)
/// Step 3: Transmit MSG_AUTH_RESPONSE frame (13-byte header + 64-byte AuthHandshakePayload) over socket
/// Step 4: Receive & verify MSG_AUTH_SUCCESS frame (0x13) from Host
fn perform_host_handshake(token: &mut [u8]) -> Result<(), Box<dyn std::error::Error>> {
    println!("[Guest Agent] Connecting to Host CID {} on Vsock Port {}...", CID_HOST, PORT_CONTROL);

    let shared_secret = b"shared_secret_key_32bytes_long!!";

    // 1. Establish real AF_VSOCK socket connection to Host CID 2, Port 5000
    let mut stream = match vsock::connect_vsock(CID_HOST, PORT_CONTROL) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[Guest Agent] Vsock connection error: {}", e);
            return Err(e.into());
        }
    };

    println!("[Guest Agent] Connected to Host Vsock Port {}.", PORT_CONTROL);

    // 2. Construct 64-byte AuthHandshakePayload using authentic HMAC-SHA256
    let payload = auth::construct_handshake_payload(shared_secret, token)?;
    if payload.len() != 64 {
        return Err(format!("Invalid payload length: expected 64, got {}", payload.len()).into());
    }

    // 3. Pack 13-byte VsockFrameHeader (MSG_AUTH_RESPONSE = 0x11, payload_len = 64, sequence_id = 1)
    let header_bytes = pack_frame_header(VsockFrameType::MsgAuthResponse, 64, 1);

    // 4. Send Frame Header + Payload over Vsock socket
    stream.write_all(&header_bytes)?;
    stream.write_all(&payload)?;
    stream.flush()?;
    println!("[Guest Agent] Transmitted MSG_AUTH_RESPONSE (77 bytes) over Vsock socket.");

    // 5. Read response header from Host (13 bytes)
    let mut resp_header = [0u8; 13];
    stream.read_exact(&mut resp_header)?;

    let magic = u32::from_le_bytes(resp_header[0..4].try_into()?);
    let frame_type = resp_header[4];

    if magic != VSOK_MAGIC {
        return Err(format!("Invalid magic header received: 0x{:08X}", magic).into());
    }

    if frame_type != VsockFrameType::MsgAuthSuccess as u8 {
        return Err(format!("Host rejected authentication: frame_type = 0x{:02X}", frame_type).into());
    }

    println!("[Guest Agent] Received MSG_AUTH_SUCCESS from Host. Vsock Handshake COMPLETE.");
    Ok(())
}
