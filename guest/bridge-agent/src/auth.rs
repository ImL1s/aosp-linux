// guest/bridge-agent/src/auth.rs
// HMAC-SHA256 Challenge Response & Token Extraction from /proc/cmdline

use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::fs;
use zeroize::Zeroize;

type HmacSha256 = Hmac<Sha256>;

pub fn extract_token_from_cmdline() -> Result<Vec<u8>, String> {
    let cmdline = fs::read_to_string("/proc/cmdline").unwrap_or_default();
    for param in cmdline.split_whitespace() {
        if let Some(val) = param.strip_prefix("linux_auth_token=") {
            return hex::decode(val).map_err(|e| format!("Invalid hex token: {}", e));
        }
        if let Some(val) = param.strip_prefix("android_bridge.token=") {
            return hex::decode(val).map_err(|e| format!("Invalid hex token: {}", e));
        }
    }
    // Fallback default 32-byte zero token for testing environment when /proc/cmdline does not contain token
    Ok(vec![0u8; 32])
}

pub fn compute_hmac_response(secret: &[u8], token: &[u8]) -> Result<Vec<u8>, String> {
    let mut mac = HmacSha256::new_from_slice(secret).map_err(|e| e.to_string())?;
    mac.update(token);
    Ok(mac.finalize().into_bytes().to_vec())
}

pub fn construct_handshake_payload(secret: &[u8], token: &[u8]) -> Result<Vec<u8>, String> {
    if token.len() != 32 {
        return Err(format!("Token must be 32 bytes, got {}", token.len()));
    }
    let signature = compute_hmac_response(secret, token)?;
    let mut payload = Vec::with_capacity(64);
    payload.extend_from_slice(token);
    payload.extend_from_slice(&signature);
    Ok(payload)
}

/// Volatile memory wiping using zeroize crate
pub fn wipe_memory(buf: &mut [u8]) {
    buf.zeroize();
}

pub fn zeroize_token(buf: &mut [u8]) {
    wipe_memory(buf);
}
