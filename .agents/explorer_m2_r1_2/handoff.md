# Handoff Report: Explorer 2 (Milestone M2) — Authentication Remediation Analysis

## 1. Observation

Direct code inspection of `guest/bridge-agent/src/` revealed the following specific defects in `auth.rs` and `main.rs`:

1. **Hardcoded Secret Key in `src/main.rs:72`**:
   ```rust
   let shared_secret = b"shared_secret_key_32bytes_long!!";
   ```
   *Tool Command*: `view_file` on `guest/bridge-agent/src/main.rs` (lines 72, 86).
   *Finding*: The HMAC-SHA256 secret key is hardcoded as a static 32-byte byte string slice in binary source code, allowing static binary extraction and providing zero dynamic session key security.

2. **Zero-Token Fallback Default in `src/auth.rs:21-22`**:
   ```rust
   // Fallback default 32-byte zero token for testing environment when /proc/cmdline does not contain token
   Ok(vec![0u8; 32])
   ```
   *Tool Command*: `view_file` on `guest/bridge-agent/src/auth.rs` (lines 11-23).
   *Finding*: When `/proc/cmdline` does not contain `linux_auth_token=` or `android_bridge.token=`, `extract_token_from_cmdline()` returns `Ok(vec![0u8; 32])` (a 32-byte zero token) instead of returning an `Err`. This permits mock zero tokens to pass authentication checks without a valid token parameter.

3. **Absence of Token Validation for Zero Tokens & Invalid Lengths in `src/auth.rs`**:
   *Tool Command*: `view_file` on `guest/bridge-agent/src/auth.rs` (lines 11-40).
   *Finding*: If `/proc/cmdline` contains `linux_auth_token=0000000000000000000000000000000000000000000000000000000000000000` (64 hex zeros), `hex::decode` succeeds and produces `vec![0u8; 32]`. There is no check to reject tokens containing all zero bytes or tokens that are not 32 bytes long.

4. **Missing Process Abort (`std::process::exit(1)`) on Auth Failure in `src/main.rs:36-51`**:
   ```rust
   if let Err(e) = perform_host_handshake(&mut token_buf) {
       eprintln!("[Guest Agent] Vsock handshake failed: {}", e);
   } else {
       println!("[Guest Agent] Host authenticated successfully.");
   }

   // 3. Zero out single-use token memory immediately after completion using zeroize
   auth::zeroize_token(&mut token_buf);
   println!("[Guest Agent] Token zeroized from memory.");

   println!("[Guest Agent] Listening on Vsock Ports (5000 Control, 5001 PTY, 5002 Wayland)...");
   
   loop {
       std::thread::sleep(Duration::from_secs(5));
   }
   ```
   *Tool Command*: `view_file` on `guest/bridge-agent/src/main.rs` (lines 36-51).
   *Finding*: When `perform_host_handshake` returns `Err(e)` (handshake failure), the daemon prints the error message but continues execution, zeroizes the token, logs listening ports, and enters the infinite event loop. The daemon continues to run and accept connections after authentication failure.

5. **Missing Secret Key Zeroization in Memory**:
   *Tool Command*: `view_file` on `guest/bridge-agent/src/main.rs` (line 43).
   *Finding*: Only `token_buf` is zeroized after handshake completion. The secret key is not zeroized from volatile memory.

---

## 2. Logic Chain

1. **From Observation 1 (Hardcoded Secret Key)**:
   Static secret keys in source code compromise cryptographic isolation across host-guest sessions. To eliminate hardcoded secrets, `src/auth.rs` must provide a dynamic secret extraction mechanism (`extract_secret_key()`) that checks:
   - Environment variables (`LINUX_AUTH_SECRET`, `ANDROID_BRIDGE_SECRET`).
   - Kernel command-line parameters (`linux_auth_secret=`, `android_bridge.secret=`).
   - Secure filesystem paths (`/etc/linux_auth_secret`, `/etc/bridge_secret`, `/var/run/secrets/linux_auth_secret`).
   If none of these sources supply a valid non-zero 32-byte key, `extract_secret_key()` must fail and return an `Err`.

2. **From Observation 2 & 3 (Zero-Token Fallback & Unvalidated Tokens)**:
   Returning `Ok(vec![0u8; 32])` when `/proc/cmdline` lacks token parameters allows unauthenticated processes to authenticate with a zero token. Removing `Ok(vec![0u8; 32])` ensures missing tokens fail fast. Adding a validation helper `validate_key_material(data, name)` ensures that:
   - Length must be exactly 32 bytes (`data.len() == 32`).
   - Token must not be all zeros (`!data.iter().all(|&b| b == 0)`).
   Any zero token or invalid length token is explicitly rejected with `Err(...)`.

3. **From Observation 4 (Missing Process Abort on Auth Failure)**:
   In security-critical daemons, an unauthenticated state must terminate execution immediately to prevent unauthorized RPC invocation or port listening. Matching error results from:
   - Token extraction (`auth::extract_token_from_cmdline()`),
   - Secret key extraction (`auth::extract_secret_key()`), and
   - Host handshake (`perform_host_handshake(...)`),
   must log the failure, wipe volatile memory buffers (`token_buf`, `secret_buf`), and call `std::process::exit(1)`.

4. **From Observation 5 (Volatile Memory Wiping)**:
   Both single-use tokens and dynamic secret keys must be wiped from volatile memory using `zeroize` (`auth::wipe_memory(...)` / `auth::zeroize_token(...)`) immediately following handshake completion or before exiting on failure.

---

## 3. Caveats

- **Host-Side Coordination**: Host launch scripts (`guest/scripts/launch_vm.sh`) and host bridge daemon (`system/linux_bridge/`) must pass non-zero tokens and secret keys (e.g. via `LINUX_AUTH_SECRET` environment variable or `/proc/cmdline` `linux_auth_secret=`) matching the guest agent's dynamic extraction priorities.
- **AF_VSOCK Environment**: Unit tests run in non-vsock host environments will test token/secret parsing, validation, HMAC computation, and error exit behavior. Full end-to-end socket handshakes require host vsock support or mock sockets.

---

## 4. Conclusion

To achieve complete authentication security in `guest/bridge-agent`, the following exact changes must be applied to `src/auth.rs` and `src/main.rs`:

### A. Proposed Code Changes for `guest/bridge-agent/src/auth.rs`

```rust
// guest/bridge-agent/src/auth.rs
// HMAC-SHA256 Challenge Response & Token/Secret Extraction

use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::env;
use std::fs;
use zeroize::Zeroize;

type HmacSha256 = Hmac<Sha256>;

/// Validates that key material (token or secret) is exactly 32 bytes and NOT all zeros.
pub fn validate_key_material(data: &[u8], name: &str) -> Result<(), String> {
    if data.len() != 32 {
        return Err(format!("{} must be exactly 32 bytes, got {}", name, data.len()));
    }
    if data.iter().all(|&b| b == 0) {
        return Err(format!("{} cannot be all zeros (mock/zero tokens rejected)", name));
    }
    Ok(())
}

/// Extract single-use auth token from /proc/cmdline.
/// Returns error if token parameter is missing, invalid hex, non-32-bytes, or all zeros.
pub fn extract_token_from_cmdline() -> Result<Vec<u8>, String> {
    let cmdline = fs::read_to_string("/proc/cmdline").unwrap_or_default();
    for param in cmdline.split_whitespace() {
        let hex_str = if let Some(val) = param.strip_prefix("linux_auth_token=") {
            Some(val)
        } else if let Some(val) = param.strip_prefix("android_bridge.token=") {
            Some(val)
        } else {
            None
        };

        if let Some(val) = hex_str {
            let token = hex::decode(val).map_err(|e| format!("Invalid hex token: {}", e))?;
            validate_key_material(&token, "Auth token")?;
            return Ok(token);
        }
    }
    
    Err("No valid auth token parameter (linux_auth_token= / android_bridge.token=) found in /proc/cmdline".to_string())
}

/// Dynamically extract secret key from environment variables, /proc/cmdline, or config files.
pub fn extract_secret_key() -> Result<Vec<u8>, String> {
    // 1. Check environment variables
    for env_var in &["LINUX_AUTH_SECRET", "ANDROID_BRIDGE_SECRET"] {
        if let Ok(val) = env::var(env_var) {
            if let Ok(key) = parse_key_string(&val) {
                if validate_key_material(&key, "Secret key").is_ok() {
                    return Ok(key);
                }
            }
        }
    }

    // 2. Check /proc/cmdline
    let cmdline = fs::read_to_string("/proc/cmdline").unwrap_or_default();
    for param in cmdline.split_whitespace() {
        let hex_str = if let Some(val) = param.strip_prefix("linux_auth_secret=") {
            Some(val)
        } else if let Some(val) = param.strip_prefix("android_bridge.secret=") {
            Some(val)
        } else {
            None
        };

        if let Some(val) = hex_str {
            if let Ok(key) = parse_key_string(val) {
                if validate_key_material(&key, "Secret key").is_ok() {
                    return Ok(key);
                }
            }
        }
    }

    // 3. Check secure key file paths
    let file_paths = [
        "/etc/linux_auth_secret",
        "/etc/bridge_secret",
        "/var/run/secrets/linux_auth_secret",
    ];

    for path in &file_paths {
        if let Ok(content) = fs::read_to_string(path) {
            let trimmed = content.trim();
            if let Ok(key) = parse_key_string(trimmed) {
                if validate_key_material(&key, "Secret key").is_ok() {
                    return Ok(key);
                }
            }
        }
    }

    Err("No valid secret key provided via LINUX_AUTH_SECRET, /proc/cmdline, or config files".to_string())
}

/// Helper to parse a key string (hex encoded or raw 32-byte ascii) into byte vector.
fn parse_key_string(val: &str) -> Result<Vec<u8>, String> {
    if val.len() == 64 {
        hex::decode(val).map_err(|e| format!("Invalid hex secret key: {}", e))
    } else if val.len() == 32 {
        Ok(val.as_bytes().to_vec())
    } else {
        Err(format!("Key string length must be 64 hex chars or 32 raw bytes, got {}", val.len()))
    }
}

pub fn compute_hmac_response(secret: &[u8], token: &[u8]) -> Result<Vec<u8>, String> {
    validate_key_material(secret, "Secret key")?;
    validate_key_material(token, "Auth token")?;
    let mut mac = HmacSha256::new_from_slice(secret).map_err(|e| e.to_string())?;
    mac.update(token);
    Ok(mac.finalize().into_bytes().to_vec())
}

pub fn construct_handshake_payload(secret: &[u8], token: &[u8]) -> Result<Vec<u8>, String> {
    validate_key_material(secret, "Secret key")?;
    validate_key_material(token, "Auth token")?;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_zero_token_rejection() {
        let zero_token = vec![0u8; 32];
        assert!(validate_key_material(&zero_token, "Auth token").is_err());
    }

    #[test]
    fn test_invalid_length_token_rejection() {
        let short_token = vec![1u8; 16];
        assert!(validate_key_material(&short_token, "Auth token").is_err());
    }

    #[test]
    fn test_valid_token_validation() {
        let valid_token = vec![0xABu8; 32];
        assert!(validate_key_material(&valid_token, "Auth token").is_ok());
    }

    #[test]
    fn test_hmac_computation() {
        let secret = vec![0x01u8; 32];
        let token = vec![0x02u8; 32];
        let sig = compute_hmac_response(&secret, &token);
        assert!(sig.is_ok());
        assert_eq!(sig.unwrap().len(), 32);
    }

    #[test]
    fn test_handshake_payload_construction() {
        let secret = vec![0x01u8; 32];
        let token = vec![0x02u8; 32];
        let payload = construct_handshake_payload(&secret, &token);
        assert!(payload.is_ok());
        let payload = payload.unwrap();
        assert_eq!(payload.len(), 64);
        assert_eq!(&payload[0..32], &token[..]);
    }

    #[test]
    fn test_parse_key_string() {
        let hex_64 = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
        let parsed = parse_key_string(hex_64);
        assert!(parsed.is_ok());
        assert_eq!(parsed.unwrap().len(), 32);
    }
}
```

### B. Proposed Code Changes for `guest/bridge-agent/src/main.rs`

```rust
// guest/bridge-agent/src/main.rs
// Rust daemon running inside Debian Guest to communicate with AOSP Host via Vsock RPC

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

    // 1. Extract single-use auth token from /proc/cmdline (reject mock/zero tokens & missing parameters)
    let mut token_buf = match auth::extract_token_from_cmdline() {
        Ok(t) => t,
        Err(e) => {
            eprintln!("[Guest Agent] Auth token extraction failed: {}", e);
            eprintln!("[Guest Agent] AUTHENTICATION FAILURE - ABORTING DAEMON IMMEDIATELY.");
            std::process::exit(1);
        }
    };
    println!("[Guest Agent] Auth token extracted successfully (length: {} bytes)", token_buf.len());

    // 2. Extract secret key dynamically from environment/cmdline/config (no hardcoded secrets)
    let mut secret_buf = match auth::extract_secret_key() {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[Guest Agent] Secret key extraction failed: {}", e);
            eprintln!("[Guest Agent] AUTHENTICATION FAILURE - ABORTING DAEMON IMMEDIATELY.");
            auth::wipe_memory(&mut token_buf);
            std::process::exit(1);
        }
    };
    println!("[Guest Agent] Dynamic secret key loaded successfully.");

    // 3. Perform 4-step HMAC-SHA256 Challenge-Response Handshake over AF_VSOCK Port 5000
    if let Err(e) = perform_host_handshake(&mut token_buf, &secret_buf) {
        eprintln!("[Guest Agent] Vsock handshake failed: {}", e);
        eprintln!("[Guest Agent] AUTHENTICATION FAILURE - ABORTING DAEMON IMMEDIATELY.");
        auth::wipe_memory(&mut token_buf);
        auth::wipe_memory(&mut secret_buf);
        std::process::exit(1);
    }

    println!("[Guest Agent] Host authenticated successfully.");

    // 4. Zero out sensitive token and secret memory buffers immediately after completion
    auth::zeroize_token(&mut token_buf);
    auth::wipe_memory(&mut secret_buf);
    println!("[Guest Agent] Token and secret key zeroized from volatile memory.");

    println!("[Guest Agent] Listening on Vsock Ports (5000 Control, 5001 PTY, 5002 Wayland)...");
    
    // Main event loop handling RPC requests
    loop {
        std::thread::sleep(Duration::from_secs(5));
    }
}

fn pack_frame_header(frame_type: VsockFrameType, payload_len: u32, seq_id: u32) -> [u8; 13] {
    let mut header = [0u8; 13];
    header[0..4].copy_from_slice(&VSOK_MAGIC.to_le_bytes());
    header[4] = frame_type as u8;
    header[5..9].copy_from_slice(&payload_len.to_le_bytes());
    header[9..13].copy_from_slice(&seq_id.to_le_bytes());
    header
}

fn perform_host_handshake(token: &mut [u8], secret: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
    println!("[Guest Agent] Connecting to Host CID {} on Vsock Port {}...", CID_HOST, PORT_CONTROL);

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
    let payload = auth::construct_handshake_payload(secret, token)?;
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
```

---

## 5. Verification Method

To verify these changes independently:

1. **Compilation Check**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
   export PATH="$HOME/.cargo/bin:$PATH"
   cargo check
   ```
   *Expected Output*: Exit code 0, cleanly compiled without errors.

2. **Unit Test Execution**:
   ```bash
   cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent
   export PATH="$HOME/.cargo/bin:$PATH"
   cargo test
   ```
   *Expected Output*: Exit code 0, unit tests in `src/auth.rs` pass (verifying zero token rejection, valid token validation, secret parsing, HMAC, payload construction).

3. **Runtime Auth Failure Verification**:
   - Run `target/debug/android-bridge-agent` without setting `LINUX_AUTH_SECRET` or token in `/proc/cmdline`.
   - *Expected Output*: Prints `[Guest Agent] Auth token extraction failed: ...` and `AUTHENTICATION FAILURE - ABORTING DAEMON IMMEDIATELY.`, exiting immediately with exit code 1.
