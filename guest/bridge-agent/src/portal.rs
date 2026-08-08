use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{self, BufRead, BufReader, Read, Write};

pub const MAX_PAYLOAD_SIZE: usize = 65536; // 64 KB limit

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct PortalRequest {
    pub id: u64,
    pub method: String,
    pub params: serde_json::Value,
}

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct PortalResponse {
    pub id: u64,
    pub success: bool,
    pub result: serde_json::Value,
    pub error: Option<String>,
}

impl PortalResponse {
    pub fn ok(id: u64, result: serde_json::Value) -> Self {
        Self {
            id,
            success: true,
            result,
            error: None,
        }
    }

    pub fn err(id: u64, error: String) -> Self {
        Self {
            id,
            success: false,
            result: serde_json::Value::Null,
            error: Some(error),
        }
    }
}

pub fn dispatch_portal_request(req: PortalRequest) -> PortalResponse {
    match req.method.as_str() {
        "camera.request" | "camera.status" => {
            PortalResponse::ok(req.id, serde_json::json!({
                "status": "available",
                "device": "/dev/video0"
            }))
        }
        "audio.request" | "audio.status" => {
            PortalResponse::ok(req.id, serde_json::json!({
                "status": "available",
                "backend": "pipewire"
            }))
        }
        "location.get" | "location.request" => {
            PortalResponse::ok(req.id, serde_json::json!({
                "latitude": 0.0,
                "longitude": 0.0,
                "accuracy": "mock"
            }))
        }
        "file.read" => {
            if let Some(path_str) = req.params.get("path").and_then(|v| v.as_str()) {
                match fs::read_to_string(path_str) {
                    Ok(content) => PortalResponse::ok(req.id, serde_json::json!({ "content": content })),
                    Err(e) => PortalResponse::err(req.id, format!("Failed to read file: {}", e)),
                }
            } else {
                PortalResponse::err(req.id, "Missing 'path' parameter".to_string())
            }
        }
        "file.write" => {
            if let (Some(path_str), Some(content)) = (
                req.params.get("path").and_then(|v| v.as_str()),
                req.params.get("content").and_then(|v| v.as_str()),
            ) {
                match fs::write(path_str, content) {
                    Ok(_) => PortalResponse::ok(req.id, serde_json::json!({ "status": "written" })),
                    Err(e) => PortalResponse::err(req.id, format!("Failed to write file: {}", e)),
                }
            } else {
                PortalResponse::err(req.id, "Missing 'path' or 'content' parameter".to_string())
            }
        }
        "file.list" => {
            if let Some(path_str) = req.params.get("path").and_then(|v| v.as_str()) {
                match fs::read_dir(path_str) {
                    Ok(entries) => {
                        let names: Vec<String> = entries
                            .filter_map(|e| e.ok().map(|de| de.file_name().to_string_lossy().into_owned()))
                            .collect();
                        PortalResponse::ok(req.id, serde_json::json!({ "files": names }))
                    }
                    Err(e) => PortalResponse::err(req.id, format!("Failed to list directory: {}", e)),
                }
            } else {
                PortalResponse::err(req.id, "Missing 'path' parameter".to_string())
            }
        }
        _ => PortalResponse::err(req.id, format!("Unknown portal method: {}", req.method)),
    }
}

pub fn handle_portal_session<S>(stream: S) -> io::Result<()>
where
    S: Read + Write,
{
    let mut reader = BufReader::new(stream);
    let mut line = String::new();

    loop {
        line.clear();
        let bytes_read = reader.read_line(&mut line)?;
        if bytes_read == 0 {
            break;
        }

        if bytes_read > MAX_PAYLOAD_SIZE {
            eprintln!("[Portal] Request line length {} exceeds MAX_PAYLOAD_SIZE ({})", bytes_read, MAX_PAYLOAD_SIZE);
            let response = PortalResponse::err(0, "Payload length exceeds MAX_PAYLOAD_SIZE".to_string());
            let resp_bytes = serde_json::to_vec(&response)?;
            let writer = reader.get_mut();
            let _ = writer.write_all(&resp_bytes)?;
            let _ = writer.write_all(b"\n")?;
            let _ = writer.flush()?;
            break;
        }

        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let response = match serde_json::from_str::<PortalRequest>(trimmed) {
            Ok(req) => dispatch_portal_request(req),
            Err(e) => PortalResponse::err(0, format!("Invalid JSON request: {}", e)),
        };

        let resp_bytes = serde_json::to_vec(&response)?;
        let writer = reader.get_mut();
        writer.write_all(&resp_bytes)?;
        writer.write_all(b"\n")?;
        writer.flush()?;
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use tempfile::NamedTempFile;

    #[test]
    fn test_dispatch_camera_status() {
        let req = PortalRequest {
            id: 1,
            method: "camera.status".to_string(),
            params: serde_json::json!({}),
        };
        let resp = dispatch_portal_request(req);
        assert!(resp.success);
        assert_eq!(resp.result.get("status").unwrap(), "available");
    }

    #[test]
    fn test_dispatch_audio_status() {
        let req = PortalRequest {
            id: 2,
            method: "audio.status".to_string(),
            params: serde_json::json!({}),
        };
        let resp = dispatch_portal_request(req);
        assert!(resp.success);
        assert_eq!(resp.result.get("backend").unwrap(), "pipewire");
    }

    #[test]
    fn test_dispatch_location_get() {
        let req = PortalRequest {
            id: 3,
            method: "location.get".to_string(),
            params: serde_json::json!({}),
        };
        let resp = dispatch_portal_request(req);
        assert!(resp.success);
    }

    #[test]
    fn test_dispatch_file_write_and_read() {
        let tmp = NamedTempFile::new().unwrap();
        let tmp_path = tmp.path().to_str().unwrap().to_string();

        let write_req = PortalRequest {
            id: 4,
            method: "file.write".to_string(),
            params: serde_json::json!({
                "path": tmp_path,
                "content": "hello portal rpc"
            }),
        };
        let write_resp = dispatch_portal_request(write_req);
        assert!(write_resp.success);

        let read_req = PortalRequest {
            id: 5,
            method: "file.read".to_string(),
            params: serde_json::json!({
                "path": tmp_path
            }),
        };
        let read_resp = dispatch_portal_request(read_req);
        assert!(read_resp.success);
        assert_eq!(read_resp.result.get("content").unwrap(), "hello portal rpc");
    }

    #[test]
    fn test_handle_portal_session_stream() {
        let req = PortalRequest {
            id: 10,
            method: "camera.status".to_string(),
            params: serde_json::json!({}),
        };
        let mut req_bytes = serde_json::to_vec(&req).unwrap();
        req_bytes.push(b'\n');

        let mut cursor = Cursor::new(req_bytes);
        let res = handle_portal_session(&mut cursor);
        assert!(res.is_ok());
    }

    #[test]
    fn test_handle_portal_session_payload_size_limit() {
        let large_req = vec![b'a'; MAX_PAYLOAD_SIZE + 100];
        let mut cursor = Cursor::new(large_req);
        let res = handle_portal_session(&mut cursor);
        assert!(res.is_ok());
    }
}
