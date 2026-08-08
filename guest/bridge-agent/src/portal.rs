use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub const MAX_PAYLOAD_SIZE: usize = 65536; // 64 KB limit

fn current_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn default_available() -> String {
    "available".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct LocationEvent {
    #[serde(alias = "Latitude")]
    pub latitude: f64,
    #[serde(alias = "Longitude")]
    pub longitude: f64,
    #[serde(alias = "Accuracy")]
    pub accuracy: f32,
    #[serde(default = "current_timestamp")]
    pub timestamp: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CameraFrameEvent {
    #[serde(default, alias = "device")]
    pub device: String,
    #[serde(default)]
    pub width: u32,
    #[serde(default)]
    pub height: u32,
    #[serde(default)]
    pub fps: u32,
    #[serde(default = "default_available", alias = "status")]
    pub status: String,
    #[serde(default = "current_timestamp")]
    pub timestamp: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AudioPcmEvent {
    #[serde(default, alias = "backend")]
    pub backend: String,
    #[serde(default)]
    pub sample_rate: u32,
    #[serde(default)]
    pub channels: u16,
    #[serde(default = "default_available", alias = "status")]
    pub status: String,
    #[serde(default = "current_timestamp")]
    pub timestamp: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum HostPortalEvent {
    #[serde(rename = "location", alias = "location_update")]
    Location(LocationEvent),
    #[serde(rename = "camera", alias = "camera_update")]
    Camera(CameraFrameEvent),
    #[serde(rename = "audio", alias = "audio_update")]
    Audio(AudioPcmEvent),
}

#[derive(Debug, Default, Clone)]
pub struct PortalState {
    pub last_location: Option<LocationEvent>,
    pub last_camera: Option<CameraFrameEvent>,
    pub last_audio: Option<AudioPcmEvent>,
}

pub static GLOBAL_PORTAL_STATE: OnceLock<Arc<RwLock<PortalState>>> = OnceLock::new();

pub fn get_portal_state() -> &'static Arc<RwLock<PortalState>> {
    GLOBAL_PORTAL_STATE.get_or_init(|| Arc::new(RwLock::new(PortalState::default())))
}

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
    let state = match get_portal_state().read() {
        Ok(guard) => guard,
        Err(e) => return PortalResponse::err(req.id, format!("PortalState lock error: {}", e)),
    };

    match req.method.as_str() {
        "camera.request" | "camera.status" => {
            if let Some(cam) = &state.last_camera {
                PortalResponse::ok(req.id, serde_json::json!({
                    "status": cam.status,
                    "device": if cam.device.is_empty() { "/dev/video0" } else { &cam.device },
                    "width": cam.width,
                    "height": cam.height,
                    "fps": cam.fps,
                    "timestamp": cam.timestamp
                }))
            } else {
                PortalResponse::err(req.id, "Camera unavailable: No active Host camera stream".to_string())
            }
        }
        "audio.request" | "audio.status" => {
            if let Some(aud) = &state.last_audio {
                PortalResponse::ok(req.id, serde_json::json!({
                    "status": aud.status,
                    "backend": if aud.backend.is_empty() { "pipewire" } else { &aud.backend },
                    "sample_rate": aud.sample_rate,
                    "channels": aud.channels,
                    "timestamp": aud.timestamp
                }))
            } else {
                PortalResponse::err(req.id, "Audio unavailable: No active Host audio stream".to_string())
            }
        }
        "location.get" | "location.request" => {
            if let Some(loc) = &state.last_location {
                PortalResponse::ok(req.id, serde_json::json!({
                    "latitude": loc.latitude,
                    "longitude": loc.longitude,
                    "accuracy": loc.accuracy,
                    "timestamp": loc.timestamp
                }))
            } else {
                PortalResponse::err(req.id, "Location unavailable: No Host location update received".to_string())
            }
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

        // 1. Demux Serde-tagged HostPortalEvent
        if let Ok(event) = serde_json::from_str::<HostPortalEvent>(trimmed) {
            if let Ok(mut state) = get_portal_state().write() {
                match event {
                    HostPortalEvent::Location(loc) => state.last_location = Some(loc),
                    HostPortalEvent::Camera(cam) => state.last_camera = Some(cam),
                    HostPortalEvent::Audio(aud) => state.last_audio = Some(aud),
                }
            }
            continue;
        }

        // 2. Demux untagged Host location event format: {"Latitude": 25.033, "Longitude": 121.565, "Accuracy": 5.0}
        if let Ok(loc) = serde_json::from_str::<LocationEvent>(trimmed) {
            if loc.latitude != 0.0 || loc.longitude != 0.0 {
                if let Ok(mut state) = get_portal_state().write() {
                    state.last_location = Some(loc);
                }
                continue;
            }
        }

        // 3. Dispatch Guest RPC PortalRequest
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
        {
            let mut state = get_portal_state().write().unwrap();
            state.last_camera = Some(CameraFrameEvent {
                device: "/dev/video0".to_string(),
                width: 1920,
                height: 1080,
                fps: 30,
                status: "available".to_string(),
                timestamp: current_timestamp(),
            });
        }
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
        {
            let mut state = get_portal_state().write().unwrap();
            state.last_audio = Some(AudioPcmEvent {
                backend: "pipewire".to_string(),
                sample_rate: 44100,
                channels: 2,
                status: "available".to_string(),
                timestamp: current_timestamp(),
            });
        }
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
        {
            let mut state = get_portal_state().write().unwrap();
            state.last_location = Some(LocationEvent {
                latitude: 25.033,
                longitude: 121.565,
                accuracy: 5.0,
                timestamp: current_timestamp(),
            });
        }
        let req = PortalRequest {
            id: 3,
            method: "location.get".to_string(),
            params: serde_json::json!({}),
        };
        let resp = dispatch_portal_request(req);
        assert!(resp.success);
        assert_eq!(resp.result.get("latitude").unwrap(), 25.033);
        assert_eq!(resp.result.get("longitude").unwrap(), 121.565);
    }

    #[test]
    fn test_dispatch_location_uninitialized_returns_error() {
        {
            let mut state = get_portal_state().write().unwrap();
            state.last_location = None;
        }
        let req = PortalRequest {
            id: 30,
            method: "location.get".to_string(),
            params: serde_json::json!({}),
        };
        let resp = dispatch_portal_request(req);
        assert!(!resp.success);
        assert!(resp.error.is_some());
    }

    #[test]
    fn test_dispatch_location_with_host_event() {
        let stream_bytes = b"{\"type\":\"location\",\"latitude\":25.033,\"longitude\":121.565,\"accuracy\":5.0}\n{\"id\":99,\"method\":\"location.get\",\"params\":{}}\n";
        let mut cursor = Cursor::new(stream_bytes.to_vec());
        let res = handle_portal_session(&mut cursor);
        assert!(res.is_ok());

        let req = PortalRequest {
            id: 100,
            method: "location.get".to_string(),
            params: serde_json::json!({}),
        };
        let resp = dispatch_portal_request(req);
        assert!(resp.success);
        assert_eq!(resp.result.get("latitude").unwrap(), 25.033);
        assert_eq!(resp.result.get("longitude").unwrap(), 121.565);
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
        {
            let mut state = get_portal_state().write().unwrap();
            state.last_camera = Some(CameraFrameEvent {
                device: "/dev/video0".to_string(),
                width: 1920,
                height: 1080,
                fps: 30,
                status: "available".to_string(),
                timestamp: current_timestamp(),
            });
        }
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
