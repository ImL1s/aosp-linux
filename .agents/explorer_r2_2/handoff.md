# Handoff Report — Defect 2: Guest Portal Hardcoded Mock Responses Investigation & Refactoring Strategy

## 1. Observation (觀察事實)

Direct verbatim code evidence from `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/portal.rs`:

### Observation 1.1: Hardcoded Mock JSON Responses in `dispatch_portal_request`
In `guest/bridge-agent/src/portal.rs` (lines 42–63):
```rust
42: pub fn dispatch_portal_request(req: PortalRequest) -> PortalResponse {
43:     match req.method.as_str() {
44:         "camera.request" | "camera.status" => {
45:             PortalResponse::ok(req.id, serde_json::json!({
46:                 "status": "available",
47:                 "device": "/dev/video0"
48:             }))
49:         }
50:         "audio.request" | "audio.status" => {
51:             PortalResponse::ok(req.id, serde_json::json!({
52:                 "status": "available",
53:                 "backend": "pipewire"
54:             }))
55:         }
56:         "location.get" | "location.request" => {
57:             PortalResponse::ok(req.id, serde_json::json!({
58:                 "latitude": 0.0,
59:                 "longitude": 0.0,
60:                 "accuracy": "mock"
61:             }))
62:         }
```

Specific audit findings:
1. **Location Mock** (lines 56–62): `location.get` / `location.request` unconditionally returns hardcoded latitude `0.0`, longitude `0.0`, and accuracy `"mock"`. It completely ignores location updates sent from Host `LinuxPortalService.java` via `sendGeoClueLocationUpdate`.
2. **Camera Mock** (lines 44–49): `camera.status` / `camera.request` unconditionally returns fixed `"status": "available"` and `"device": "/dev/video0"`. It does not check whether Host `LinuxPortalService` has an active camera session or whether camera hardware is available/busy.
3. **Audio Mock** (lines 50–55): `audio.status` / `audio.request` unconditionally returns fixed `"status": "available"` and `"backend": "pipewire"`. It does not reflect Host microphone recording state, privacy toggle state, or active audio sessions.

### Observation 1.2: Mock-Validating Unit Tests
In `guest/bridge-agent/src/portal.rs` (lines 156–190):
```rust
156:     #[test]
157:     fn test_dispatch_camera_status() {
...
165:         assert_eq!(resp.result.get("status").unwrap(), "available");
166:     }
...
168:     #[test]
169:     fn test_dispatch_audio_status() {
...
177:         assert_eq!(resp.result.get("backend").unwrap(), "pipewire");
178:     }
...
180:     #[test]
181:     fn test_dispatch_location_get() {
...
188:         assert!(resp.success);
189:     }
```
These unit tests hard-code expectations for mock strings `"available"`, `"pipewire"`, and static success on `location.get`, perpetuating the mock facade.

### Observation 1.3: Host Sender Behavior in `LinuxPortalService.java`
In `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`:
- Line 65: `private static final int VSOCK_PORTAL_PORT = 5000;`
- Lines 746–753 (`sendGeoClueLocationUpdate`):
```java
746:     private void sendGeoClueLocationUpdate(double lat, double lon, float accuracy) {
747:         try (Socket s = new Socket("localhost", VSOCK_PORTAL_PORT)) {
748:             OutputStream out = s.getOutputStream();
749:             String json = "{\"Latitude\":" + lat + ",\"Longitude\":" + lon + ",\"Accuracy\":" + accuracy + "}\n";
750:             out.write(json.getBytes(StandardCharsets.UTF_8));
751:             out.flush();
752:         } catch (Exception ignored) {}
753:     }
```
Host sends events over AF_VSOCK (Port 5000). Currently, Guest `portal.rs` simply reads incoming lines in `handle_portal_session` and treats every line strictly as a `PortalRequest`. When a Host event arrives as JSON/string on port 5000, `serde_json::from_str::<PortalRequest>(trimmed)` fails with `Invalid JSON request: ...` because Host events do not have `id`, `method`, `params` fields.

---

## 2. Logic Chain (推理邏輯鏈)

1. **Step 1 (Root Cause Analysis)**:
   - `portal.rs` currently implements a static RPC mock. `dispatch_portal_request` matches `"location.get"`, `"camera.status"`, and `"audio.status"` and returns fixed JSON literals.
   - `handle_portal_session` only knows how to parse `PortalRequest` (`{"id": ..., "method": ..., "params": ...}`). When Host `LinuxPortalService` pushes events (such as location updates `{"Latitude": ..., "Longitude": ..., "Accuracy": ...}`) over AF_VSOCK Port 5000, `serde_json::from_str::<PortalRequest>` fails and returns an error response (`Invalid JSON request`), discarding the Host event without updating Guest state.

2. **Step 2 (State Management Requirement)**:
   - Guest `bridge-agent` is multi-threaded. Multiple client connections or session handlers can execute concurrently.
   - Therefore, Guest `portal.rs` requires a thread-safe, shared state store (`PortalState`) protected by `Arc<RwLock<PortalState>>` or `Arc<Mutex<PortalState>>` to hold the latest `LocationEvent`, `CameraFrameEvent`, and `AudioPcmEvent`.

3. **Step 3 (Host Event Ingestion & Parsing Strategy)**:
   - Incoming messages on Port 5000 must be parsed in a dual-mode pipeline:
     - First, check if the incoming payload matches a Host Portal Event (Serde tagged/untagged enum `HostPortalEvent` or legacy fields `Latitude`/`Longitude`, `device`, `status`).
     - If it is a Host Portal Event, update `PortalState` (`last_location`, `last_camera`, `last_audio`).
     - If it is a Guest `PortalRequest`, route to `dispatch_portal_request(req)`.

4. **Step 4 (Purging Hardcoded Mock Responses)**:
   - In `dispatch_portal_request`:
     - For `"location.get"` / `"location.request"`: Query `PortalState.last_location`.
       - If `Some(loc)`: Return `PortalResponse::ok(req.id, json!({"latitude": loc.latitude, "longitude": loc.longitude, "accuracy": loc.accuracy, "timestamp": loc.timestamp}))`.
       - If `None` (no Host event received): Return `PortalResponse::err(req.id, "Location unavailable: No Host location update received".to_string())`.
     - For `"camera.status"` / `"camera.request"`: Query `PortalState.last_camera`.
       - If `Some(cam)`: Return `PortalResponse::ok(req.id, json!({"status": cam.status, "device": cam.device, "width": cam.width, "height": cam.height, "fps": cam.fps, "timestamp": cam.timestamp}))`.
       - If `None`: Return `PortalResponse::err(req.id, "Camera unavailable: No active Host camera stream".to_string())`.
     - For `"audio.status"` / `"audio.request"`: Query `PortalState.last_audio`.
       - If `Some(aud)`: Return `PortalResponse::ok(req.id, json!({"status": aud.status, "backend": aud.backend, "sample_rate": aud.sample_rate, "channels": aud.channels, "timestamp": aud.timestamp}))`.
       - If `None`: Return `PortalResponse::err(req.id, "Audio unavailable: No active Host audio stream".to_string())`.

5. **Step 5 (Unit Test & Integration Parity)**:
   - All unit tests in `portal.rs` must be updated to test both uninitialized state (expecting `success: false` / error message) and populated state (verifying dynamic response matches injected event data).

---

## 3. Caveats (注意事項與未涵蓋範圍)

1. **Host-Side Protocol Unification (Phase 3 & Phase 6 Dependency)**:
   - Host `LinuxPortalService.java` currently uses ad-hoc JSON strings (`{"Latitude":..., "Longitude":...}`) for location, text lines (`CAM_FRAME:...`) for camera, and raw binary bytes for audio.
   - For clean Serde parsing in Rust, Host and Guest should share a unified JSON event structure (`type: "location"|"camera"|"audio"`). Serde field aliases (`#[serde(alias = "Latitude")]`) in `portal.rs` allow backward compatibility with legacy Host formats during transition.
2. **Audio PCM Data Handling**:
   - Audio PCM data consists of streaming binary byte buffers. Audio status/metadata can be updated via JSON control events, while raw PCM bytes can be buffered or routed to PipeWire/ALSA virtual devices in the Guest.
3. **Read-Only Investigation Role**:
   - As an explorer agent, this report provides complete analysis and exact refactoring design. Implementation will be executed by implementation workers.

---

## 4. Conclusion (結論與重構方案)

### 4.1 Defect Summary
Lines 44–62 of `guest/bridge-agent/src/portal.rs` contain hardcoded mock JSON responses returning coordinates `(0.0, 0.0)` with accuracy `"mock"`, and fixed status `"available"` for camera and audio. This violates Phase 6 and Rule 4 requirements.

### 4.2 Detailed Refactoring Code Design

#### A. Data Models & Serde Enums (`portal.rs`)
```rust
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

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
    pub device: String,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub status: String,
    #[serde(default = "current_timestamp")]
    pub timestamp: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AudioPcmEvent {
    pub backend: String,
    pub sample_rate: u32,
    pub channels: u16,
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

fn current_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
```

#### B. Thread-Safe `PortalState` Container
```rust
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
```

#### C. Refactored `dispatch_portal_request`
```rust
pub fn dispatch_portal_request(req: PortalRequest) -> PortalResponse {
    let state = get_portal_state().read().unwrap();
    match req.method.as_str() {
        "camera.request" | "camera.status" => {
            if let Some(cam) = &state.last_camera {
                PortalResponse::ok(req.id, serde_json::json!({
                    "status": cam.status,
                    "device": cam.device,
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
                    "backend": aud.backend,
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
        "file.read" => { ... }
        "file.write" => { ... }
        "file.list" => { ... }
        _ => PortalResponse::err(req.id, format!("Unknown portal method: {}", req.method)),
    }
}
```

#### D. Message Demuxing in `handle_portal_session`
```rust
// Inside handle_portal_session loop:
// 1. Try parsing Host Event
if let Ok(event) = serde_json::from_str::<HostPortalEvent>(trimmed) {
    let mut state = get_portal_state().write().unwrap();
    match event {
        HostPortalEvent::Location(loc) => state.last_location = Some(loc),
        HostPortalEvent::Camera(cam) => state.last_camera = Some(cam),
        HostPortalEvent::Audio(aud) => state.last_audio = Some(aud),
    }
    continue;
}

// Support legacy Host location update format: {"Latitude": 25.03, "Longitude": 121.56, "Accuracy": 5.0}
if let Ok(loc) = serde_json::from_str::<LocationEvent>(trimmed) {
    if loc.latitude != 0.0 || loc.longitude != 0.0 {
        get_portal_state().write().unwrap().last_location = Some(loc);
        continue;
    }
}

// 2. Try parsing Guest Portal RPC Request
let response = match serde_json::from_str::<PortalRequest>(trimmed) {
    Ok(req) => dispatch_portal_request(req),
    Err(e) => PortalResponse::err(0, format!("Invalid JSON request: {}", e)),
};
```

---

## 5. Verification Method (驗證方法)

1. **Unit Verification Command**:
   ```bash
   cargo test --manifest-path guest/bridge-agent/Cargo.toml
   ```
2. **Key Verification Tests**:
   - `test_dispatch_location_uninitialized_returns_error`: Confirm `location.get` returns `success: false` and error message when no Host event has been received.
   - `test_dispatch_location_with_host_event`: Ingest a `HostPortalEvent::Location` (e.g. lat `25.033`, lon `121.565`, accuracy `5.0`), invoke `location.get`, verify returned JSON contains exact `25.033` and `121.565` (not `0.0, 0.0` or `"mock"`).
   - `test_dispatch_camera_audio_dynamic_state`: Ingest camera/audio events, verify status changes dynamically.
3. **Invalidation Conditions**:
   - Any occurrence of hardcoded `0.0`, `"mock"`, or static `"available"` strings in `portal.rs`.
   - Failure to update `PortalState` when Host pushes JSON messages over VSOCK Port 5000.
