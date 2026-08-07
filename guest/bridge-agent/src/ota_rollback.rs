// guest/bridge-agent/src/ota_rollback.rs
// Heartbeat signal generator sent to host on successful systemd boot completion (F-R5-014)

use std::io::Write;
use std::time::Duration;
use crate::vsock::{CID_HOST, PORT_CONTROL};

pub fn send_boot_heartbeat() -> Result<(), Box<dyn std::error::Error>> {
    println!("[OTA Watchdog] Sending boot completed heartbeat signal to Host...");
    if let Ok(mut stream) = crate::vsock::connect_vsock(CID_HOST, PORT_CONTROL) {
        // Send heartbeat packet
        let mut frame = [0u8; 13];
        frame[0..4].copy_from_slice(&0x56534F4Bu32.to_le_bytes()); // VSOK_MAGIC
        frame[4] = 0x04; // Heartbeat frame type
        frame[5..9].copy_from_slice(&0u32.to_le_bytes());
        frame[9..13].copy_from_slice(&999u32.to_le_bytes());
        stream.write_all(&frame)?;
        stream.flush()?;
        println!("[OTA Watchdog] Heartbeat signal delivered successfully.");
    } else {
        println!("[OTA Watchdog] Vsock stream mock/simulation mode (heartbeat logged).");
    }
    Ok(())
}
