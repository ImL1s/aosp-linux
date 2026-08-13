use std::env;
use std::fs;
use std::io::{Read, Write};

/// Dynamic extraction of authentication secret key.
/// Checks in order:
/// 1. LINUX_AUTH_SECRET environment variable
/// 2. /etc/linux_auth_secret file
/// 3. /proc/cmdline (kernel cmdline parameter linux_auth_secret=... or auth_secret=...)
pub fn extract_auth_secret() -> Result<Vec<u8>, String> {
    // 1. Environment variable
    if let Ok(val) = env::var("LINUX_AUTH_SECRET") {
        let trimmed = val.trim();
        if !trimmed.is_empty() {
            return Ok(trimmed.as_bytes().to_vec());
        }
    }

    // 2. File /etc/linux_auth_secret
    if let Ok(content) = fs::read_to_string("/etc/linux_auth_secret") {
        let trimmed = content.trim();
        if !trimmed.is_empty() {
            return Ok(trimmed.as_bytes().to_vec());
        }
    }

    // 3. /proc/cmdline
    if let Ok(cmdline) = fs::read_to_string("/proc/cmdline") {
        if let Some(secret) = parse_secret_from_cmdline(&cmdline) {
            return Ok(secret);
        }
    }

    Err("No auth secret key found in LINUX_AUTH_SECRET, /etc/linux_auth_secret, or /proc/cmdline".to_string())
}

fn decode_hex_or_raw(val: &str) -> Vec<u8> {
    let trimmed = val.trim();
    if trimmed.len() == 64 && trimmed.chars().all(|c| c.is_ascii_hexdigit()) {
        let mut bytes = Vec::with_capacity(32);
        for i in (0..64).step_by(2) {
            if let Ok(b) = u8::from_str_radix(&trimmed[i..i+2], 16) {
                bytes.push(b);
            } else {
                return trimmed.as_bytes().to_vec();
            }
        }
        bytes
    } else {
        trimmed.as_bytes().to_vec()
    }
}

/// Helper function to parse secret from a cmdline string directly (useful for testing).
pub fn parse_secret_from_cmdline(cmdline: &str) -> Option<Vec<u8>> {
    for token in cmdline.split_whitespace() {
        if let Some(val) = token.strip_prefix("android_bridge.token=") {
            let decoded = decode_hex_or_raw(val);
            if !decoded.is_empty() {
                return Some(decoded);
            }
        } else if let Some(val) = token.strip_prefix("linux_auth_secret=") {
            let decoded = decode_hex_or_raw(val);
            if !decoded.is_empty() {
                return Some(decoded);
            }
        } else if let Some(val) = token.strip_prefix("auth_secret=") {
            let decoded = decode_hex_or_raw(val);
            if !decoded.is_empty() {
                return Some(decoded);
            }
        }
    }
    None
}

/// Verifies client token and HMAC signature against the expected secret key.
/// Rejects all-zero tokens, empty tokens, empty secrets, or mismatched signatures.
pub fn verify_token(token: &[u8], signature: &[u8], secret: &[u8]) -> bool {
    if token.is_empty() || signature.is_empty() || secret.is_empty() {
        return false;
    }

    if token.len() != 32 || signature.len() != 32 {
        return false;
    }

    // Reject all-zero token
    if token.iter().all(|&b| b == 0) {
        return false;
    }

    let expected_sig = HmacSha256::compute_hmac_response(secret, token);

    // Constant-time byte comparison
    let mut diff = 0u8;
    for (a, b) in signature.iter().zip(expected_sig.iter()) {
        diff |= a ^ b;
    }
    diff == 0
}

/// Pure Rust SHA-256 implementation without external dependencies.
pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];
    let k: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    let bit_len = (data.len() as u64) * 8;
    let mut padded = data.to_vec();
    padded.push(0x80);
    while (padded.len() + 8) % 64 != 0 {
        padded.push(0x00);
    }
    padded.extend_from_slice(&bit_len.to_be_bytes());

    for chunk in padded.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[i * 4], chunk[i * 4 + 1], chunk[i * 4 + 2], chunk[i * 4 + 3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }

        let mut a = h[0];
        let mut b = h[1];
        let mut c = h[2];
        let mut d = h[3];
        let mut e = h[4];
        let mut f = h[5];
        let mut g = h[6];
        let mut h_val = h[7];

        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = h_val.wrapping_add(s1).wrapping_add(ch).wrapping_add(k[i]).wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);

            h_val = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }

        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(h_val);
    }

    let mut result = [0u8; 32];
    for i in 0..8 {
        result[i * 4..(i + 1) * 4].copy_from_slice(&h[i].to_be_bytes());
    }
    result
}

/// HMAC-SHA256 signature calculator for authentication challenge responses.
pub struct HmacSha256;

impl HmacSha256 {
    pub fn compute_hmac_response(secret: &[u8], challenge: &[u8]) -> Vec<u8> {
        let mut key = [0u8; 64];
        if secret.len() > 64 {
            let hash = sha256(secret);
            key[..32].copy_from_slice(&hash);
        } else {
            key[..secret.len()].copy_from_slice(secret);
        }

        let mut o_key_pad = [0x5c; 64];
        let mut i_key_pad = [0x36; 64];
        for i in 0..64 {
            o_key_pad[i] ^= key[i];
            i_key_pad[i] ^= key[i];
        }

        let mut inner_input = Vec::new();
        inner_input.extend_from_slice(&i_key_pad);
        inner_input.extend_from_slice(challenge);
        let inner_hash = sha256(&inner_input);

        let mut outer_input = Vec::new();
        outer_input.extend_from_slice(&o_key_pad);
        outer_input.extend_from_slice(&inner_hash);
        sha256(&outer_input).to_vec()
    }
}

pub trait SetReadTimeout {
    fn set_read_timeout(&self, dur: Option<std::time::Duration>) -> std::io::Result<()>;
}

impl SetReadTimeout for crate::vsock::VsockStream {
    fn set_read_timeout(&self, dur: Option<std::time::Duration>) -> std::io::Result<()> {
        self.set_read_timeout(dur)
    }
}

impl SetReadTimeout for std::os::unix::net::UnixStream {
    fn set_read_timeout(&self, dur: Option<std::time::Duration>) -> std::io::Result<()> {
        self.set_read_timeout(dur)
    }
}

impl SetReadTimeout for std::net::TcpStream {
    fn set_read_timeout(&self, dur: Option<std::time::Duration>) -> std::io::Result<()> {
        self.set_read_timeout(dur)
    }
}

impl<T> SetReadTimeout for std::io::Cursor<T> {
    fn set_read_timeout(&self, _dur: Option<std::time::Duration>) -> std::io::Result<()> {
        Ok(())
    }
}

pub const STATUS_SUCCESS: u32 = 0x00000200;
pub const STATUS_UNAUTHORIZED: u32 = 0x00000401;

/// Performs authentication handshake over a stream with a 5-second socket read timeout.
/// Reads 64-byte AuthHandshakePayload (32-byte token + 32-byte HMAC-SHA256 signature).
/// Writes big-endian 0x00000200 (SUCCESS) or 0x00000401 (UNAUTHORIZED).
pub fn perform_handshake<S: Read + Write + SetReadTimeout>(stream: &mut S, secret: &[u8]) -> bool {
    if secret.is_empty() {
        return false;
    }

    // Set 5-second socket read timeout
    let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(5)));

    let mut payload_buf = [0u8; 64];
    if stream.read_exact(&mut payload_buf).is_err() {
        let _ = stream.set_read_timeout(None);
        return false;
    }

    let token = &payload_buf[0..32];
    let signature = &payload_buf[32..64];

    if !verify_token(token, signature, secret) {
        let _ = stream.write_all(&STATUS_UNAUTHORIZED.to_be_bytes());
        let _ = stream.flush();
        let _ = stream.set_read_timeout(None);
        return false;
    }

    if stream.write_all(&STATUS_SUCCESS.to_be_bytes()).is_err() || stream.flush().is_err() {
        let _ = stream.set_read_timeout(None);
        return false;
    }

    // Reset read timeout to None after successful authentication
    let _ = stream.set_read_timeout(None);
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn test_rfc2104_golden_vector() {
        // RFC 4231 Test Case 2 / RFC 2104 HMAC specification golden vector
        // Key = "Jefe" (4 bytes)
        // Data = "what do ya want for nothing?" (28 bytes)
        // HMAC-SHA256 = 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843
        let key = b"Jefe";
        let data = b"what do ya want for nothing?";
        let expected_hex = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
        let computed = HmacSha256::compute_hmac_response(key, data);
        let computed_hex: String = computed.iter().map(|b| format!("{:02x}", b)).collect();
        assert_eq!(computed_hex, expected_hex);
    }

    #[test]
    fn test_verify_token_valid() {
        let secret = b"my_super_secret_key_123456789012";
        let token = [1u8; 32];
        let signature = HmacSha256::compute_hmac_response(secret, &token);
        assert!(verify_token(&token, &signature, secret));
    }

    #[test]
    fn test_verify_token_all_zero_rejected() {
        let secret = b"my_super_secret_key_123456789012";
        let token = [0u8; 32];
        let signature = HmacSha256::compute_hmac_response(secret, &token);
        assert!(!verify_token(&token, &signature, secret));
    }

    #[test]
    fn test_verify_token_empty_rejected() {
        let secret = b"secret";
        let token = [1u8; 32];
        let signature = HmacSha256::compute_hmac_response(secret, &token);
        assert!(!verify_token(&[], &signature, secret));
        assert!(!verify_token(&token, &[], secret));
        assert!(!verify_token(&token, &signature, &[]));
    }

    #[test]
    fn test_verify_token_mismatch_rejected() {
        let secret = b"secret_key_12345678";
        let token = [1u8; 32];
        let wrong_signature = [2u8; 32];
        assert!(!verify_token(&token, &wrong_signature, secret));
    }

    #[test]
    fn test_hmac_sha256_computation() {
        let secret = b"key";
        let challenge = b"The quick brown fox jumps over the lazy dog";
        let hmac = HmacSha256::compute_hmac_response(secret, challenge);
        assert_eq!(hmac.len(), 32);
    }

    #[test]
    fn test_parse_secret_from_cmdline() {
        let cmdline = "BOOT_IMAGE=/vmlinuz root=/dev/sda1 linux_auth_secret=cmdline_secret_key_999 ro quiet";
        let secret = parse_secret_from_cmdline(cmdline).unwrap();
        assert_eq!(secret, b"cmdline_secret_key_999");
    }

    #[test]
    fn test_parse_secret_from_cmdline_android_bridge_token_hex() {
        let expected_secret = [0x12u8; 32];
        let hex_secret: String = expected_secret.iter().map(|b| format!("{:02x}", b)).collect();
        let cmdline = format!("console=ttyS0 root=/dev/vda ro init=/sbin/init android_bridge.token={} panic=1 quiet", hex_secret);
        let secret = parse_secret_from_cmdline(&cmdline).unwrap();
        assert_eq!(secret.len(), 32);
        assert_eq!(secret, expected_secret);
    }

    #[test]
    fn test_perform_handshake_success() {
        let secret = b"valid_secret_key_32bytes_long!!";
        let token = [7u8; 32];
        let signature = HmacSha256::compute_hmac_response(secret, &token);

        let mut buffer = Vec::new();
        buffer.extend_from_slice(&token);
        buffer.extend_from_slice(&signature);
        let mut cursor = Cursor::new(buffer);

        let success = perform_handshake(&mut cursor, secret);
        assert!(success);

        let output = cursor.into_inner();
        assert_eq!(&output[64..], &STATUS_SUCCESS.to_be_bytes());
    }

    #[test]
    fn test_perform_handshake_failure() {
        let secret = b"valid_secret_key_32bytes_long!!";
        let token = [7u8; 32];
        let wrong_signature = [9u8; 32];

        let mut buffer = Vec::new();
        buffer.extend_from_slice(&token);
        buffer.extend_from_slice(&wrong_signature);
        let mut cursor = Cursor::new(buffer);

        let success = perform_handshake(&mut cursor, secret);
        assert!(!success);

        let output = cursor.into_inner();
        assert_eq!(&output[64..], &STATUS_UNAUTHORIZED.to_be_bytes());
    }

    #[test]
    fn test_perform_handshake_timeout() {
        use std::os::unix::net::UnixStream;
        let (_client, mut server) = UnixStream::pair().unwrap();
        let secret = b"secret_key_for_timeout_test_123";
        server.set_read_timeout(Some(std::time::Duration::from_millis(100))).unwrap();

        let handle = std::thread::spawn(move || {
            perform_handshake(&mut server, secret)
        });

        // Client does not send anything
        let success = handle.join().unwrap();
        assert!(!success, "Handshake should return false on timeout");
        drop(_client);
    }
}

