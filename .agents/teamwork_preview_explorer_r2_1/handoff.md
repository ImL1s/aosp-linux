# Handoff Report — Defect 1: Host Portal TCP Fallback & Payload Format Investigation & Remediation Plan

## 1. Observation (直接觀察)

針對 Round 2 Victory Audit Report 所提報告中 Defect 1 的程式碼鑑識與實體觀察結果如下：

1. **Host Portal TCP Fallback 程式碼位置**：
   - 檔案：`frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
   - **Line 65**：定義 `private static final int VSOCK_PORTAL_PORT = 5000;`
   - **Line 712** (`sendVsockFrame`)：使用 `try (Socket s = new Socket("localhost", VSOCK_PORTAL_PORT))`
   - **Line 724** (`sendVsockAudioPayload`)：使用 `mAudioSocket = new Socket("localhost", VSOCK_PORTAL_PORT)`
   - **Line 747** (`sendGeoClueLocationUpdate`)：使用 `try (Socket s = new Socket("localhost", VSOCK_PORTAL_PORT))`
   - **問題結論**：Host 端所有 Portal 傳輸皆直接使用 Java `java.net.Socket("localhost", 5000)` 通過 TCP loopback (127.0.0.1) 進行連線，而非使用真實的 `AF_VSOCK` 專屬 socket。

2. **相機影格 Payload 格式字串化問題**：
   - 檔案：`frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
   - **Line 714**：`sendVsockFrame` 傳輸純文字字串 `"CAM_FRAME:" + devNode + ":" + width + "x" + height + "\n"`（例如 `"CAM_FRAME:/dev/video0:1920x1080\n"`）。
   - **問題結論**：`ImageReader` 接收到 Camera2 HAL 的真實 `android.media.Image` 畫面後，只傳送描述性文字字串，未包含任何真實相機 Pixel 影像數據（YUV_420_888 / NV21 / JPEG）或 Buffer Metadata（Timestamp, Format, Stride, Buffer Length）。

3. **Guest 端 Bridge Agent 驗證與連線接收邏輯**：
   - 檔案：`guest/bridge-agent/src/main.rs` (Lines 28-34, 56-70) 及 `guest/bridge-agent/src/auth.rs` (Lines 223-264)
   - Guest `bridge-agent` 於 Port 5000 (`PORT_PORTAL`) 監聽 AF_VSOCK 連線。
   - 每當收到 Portal 連線請求時，`bridge-agent` 會立即執行 `auth::perform_handshake(&mut stream, &secret_local)`：
     1. 由 Client 端（Host）先傳送 16-byte nonce/challenge。
     2. Client 端計算 `HMAC-SHA256(secret, challenge)` 並傳送 32-byte 簽名。
     3. Guest 驗證成功後回傳 `AUTH_OK\n`；若未執行 Handshake 或驗證失敗，Guest 會立刻中斷 Socket 連線。
   - 目前 `LinuxPortalService.java` 傳送的 TCP Socket 未包含 HMAC Handshake 流程，在實際 VM 環境中連線會直接被 Guest 拒絕。

---

## 2. Logic Chain (推理鏈)

從觀察事實推導至修復方案的邏輯鏈如下：

1. **TCP Fallback 違規分析**：
   - Java 標準庫 `new Socket("localhost", port)` 僅建立 IPv4/IPv6 loopback 通道，無法走 Linux `AF_VSOCK`（socket family 40）。
   - TCP 監聽會將主機與 Guest VM 間的硬體 Portal 數據暴露於 Host 網路介面上，存在安全隱患與本機攔截風險。
   - 這直接違反專案規範中「Rule 5: No localhost TCP fallback」與「Phase 6: Replace Host localhost TCP with authenticated AF_VSOCK」。

2. **認證握手（HMAC Handshake）缺失分析**：
   - Guest VM 的 `bridge-agent`（Port 5000）強制要求連線建立後的前 48 位元組必須為 16-byte challenge + 32-byte HMAC-SHA256 signature。
   - `LinuxPortalService.java` 若不進行 HMAC 握手，Guest 便無法確認 Host 身份，致使 Hardware Portal 通道無法在開啟 HMAC 驗證的生產環境中運作。

3. **相機影格 Payload 缺失分析**：
   - 字串 `"CAM_FRAME:/dev/video0:1920x1080\n"` 不具備相機點陣影像數據。Guest 端的 Linux 視訊應用程式（如 Cheese/v4l2loopback）無法憑字串呈現相機畫面。
   - 必須替換為結構化的 Binary Framing Protocol：包含 Header（Magic, Sequence, Timestamp, Width, Height, Pixel Format, Payload Size）與真實的 YUV/RAW Pixel Buffer 數據。

4. **AF_VSOCK 替代方案**：
   - 在 Android Java 中，可透過 `android.system.Os.socket(40 /* AF_VSOCK */, OsConstants.SOCK_STREAM, 0)` 建立原生 VSOCK Socket。
   - 透過 `android.system.VmSocketAddress(5000, guestCid)` 或反射 `android.system.SocketAddressVmSockets(5000, guestCid)` 呼叫 `Os.connect(fd, address)`。
   - 取得 `FileDescriptor` 後，構造 `FileInputStream` / `FileOutputStream` 並完成 HMAC-SHA256 握手。

---

## 3. Caveats (注意事項)

1. **Guest CID 定義**：
   - 在 AVF (Android Virtualization Framework) / crosvm 架構下，Guest VM CID 固定為 `3`（Host CID 為 2）。修復程式應優先從 `LinuxManagerService` / `LinuxBridgeService` 取得當前運作中 VM 的 CID，預設回退值為 `3`。
2. **HMAC Auth Secret 取得**：
   - 認證金鑰由 `LinuxManagerService.generateHmacAuthToken()` 於 VM 啟動時動態生成，`LinuxPortalService` 需透過 `LinuxManagerService.getInstance()` 取得 `activeAuthToken`。
3. **相機影格傳送效能與記憶體回收**：
   - 1080p (1920x1080) YUV_420_888 影格大小約為 3,110,400 位元組（約 3.1 MB）。在 30 fps 傳輸下，頻寬約為 93 MB/s。
   - `ImageReader` 需保持短生命週期讀取（`acquireNextImage()`），且 Pixel buffer byte array 在傳送後應即時丟棄，避免 SystemServer 產生頻繁 GC 停頓。

---

## 4. Conclusion & Worker Remediation Plan (結論與 Worker 重構計畫)

### 結論
Defect 1 確為嚴重的架構性缺陷。必須完全移除 `LinuxPortalService.java` 中所有的 `new Socket("localhost", 5000)` 通道，替換為原生 `AF_VSOCK` 握手通道，並將相機傳送機制重構成帶有二進位 Header 與真實 YUV Pixel Payload 的二進位傳輸協定。

---

### Recommended Worker Remediation Plan (Worker 重構計畫)

Worker 應針對 `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` 進行以下重構：

#### 步驟 1：新增 AF_VSOCK 連線與 HMAC 握手 Helper 方法

在 `LinuxPortalService.java` 中新增專用 AF_VSOCK 握手連線內部類別與方法：

```java
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.VmSocketAddress;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

private static final int AF_VSOCK = 40;
private static final int DEFAULT_GUEST_CID = 3;

private static class VsockPortalChannel implements AutoCloseable {
    public final FileDescriptor fd;
    public final InputStream in;
    public final OutputStream out;

    public VsockPortalChannel(FileDescriptor fd, InputStream in, OutputStream out) {
        this.fd = fd;
        this.in = in;
        this.out = out;
    }

    @Override
    public void close() {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        if (fd != null && fd.valid()) {
            try { Os.close(fd); } catch (Exception ignored) {}
        }
    }
}

private VsockPortalChannel openAuthenticatedVsockChannel(int port) throws Exception {
    int guestCid = DEFAULT_GUEST_CID;
    byte[] authToken = null;
    
    LinuxManagerService mgr = (LinuxManagerService) LocalServices.getService(LinuxManagerInternal.class);
    if (mgr != null && mgr.isCeKeyAvailable()) {
        // Retrieve dynamic auth token from LinuxManagerService
    }

    FileDescriptor fd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
    SocketAddress address;
    try {
        address = new VmSocketAddress(port, guestCid);
    } catch (Throwable t) {
        Class<?> clazz = Class.forName("android.system.SocketAddressVmSockets");
        java.lang.reflect.Constructor<?> ctor = clazz.getConstructor(int.class, int.class);
        address = (SocketAddress) ctor.newInstance(port, guestCid);
    }

    Os.connect(fd, address);
    InputStream in = new FileInputStream(fd);
    OutputStream out = new FileOutputStream(fd);

    // HMAC-SHA256 Authentication Handshake
    if (authToken != null && authToken.length > 0) {
        byte[] challenge = new byte[16];
        new SecureRandom().nextBytes(challenge);
        out.write(challenge);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(authToken, "HmacSHA256"));
        byte[] signature = mac.doFinal(challenge);
        out.write(signature);
        out.flush();

        byte[] respBuf = new byte[16];
        int read = in.read(respBuf);
        String respStr = (read > 0) ? new String(respBuf, 0, read, StandardCharsets.UTF_8) : "";
        if (!respStr.startsWith("AUTH_OK")) {
            try { Os.close(fd); } catch (Exception ignored) {}
            throw new IOException("Vsock portal authentication rejected: " + respStr.trim());
        }
    }

    return new VsockPortalChannel(fd, in, out);
}
```

#### 步驟 2：重構相機二進位 Framed Payload 傳送 (`sendVsockCameraFrame`)

修改 `openHardwareCamera()` 與 `sendVsockFrame`，實作真實相機 Pixel 數據與 Metadata 序列化：

```java
// Frame Header Spec (32 bytes):
// 0..3: MAGIC (0x43414D46 = "CAMF")
// 4..5: VERSION (0x0001)
// 6..7: FRAME_TYPE (0x0001 = DATA_FRAME)
// 8..15: TIMESTAMP_NS (long)
// 16..19: WIDTH (int)
// 20..23: HEIGHT (int)
// 24..27: FORMAT (int, ImageFormat.YUV_420_888 = 35)
// 28..31: PAYLOAD_SIZE (int)

private void sendVsockCameraFrame(android.media.Image image, int width, int height) {
    if (image == null) return;
    try {
        long timestampNs = image.getTimestamp();
        int format = image.getFormat();
        android.media.Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return;

        // Calculate total YUV payload size
        int totalPayload = 0;
        for (android.media.Image.Plane plane : planes) {
            totalPayload += plane.getBuffer().remaining();
        }

        byte[] pixelData = new byte[totalPayload];
        int offset = 0;
        for (android.media.Image.Plane plane : planes) {
            java.nio.ByteBuffer buf = plane.getBuffer();
            int len = buf.remaining();
            buf.get(pixelData, offset, len);
            offset += len;
        }

        // Construct 32-byte binary frame header
        java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(32);
        header.putInt(0x43414D46);               // MAGIC "CAMF"
        header.putShort((short) 1);              // VERSION 1
        header.putShort((short) 1);              // FRAME_TYPE 1
        header.putLong(timestampNs);             // TIMESTAMP_NS
        header.putInt(width);                    // WIDTH
        header.putInt(height);                   // HEIGHT
        header.putInt(format);                   // FORMAT (YUV_420_888)
        header.putInt(pixelData.length);          // PAYLOAD_SIZE

        try (VsockPortalChannel channel = openAuthenticatedVsockChannel(VSOCK_PORTAL_PORT)) {
            channel.out.write(header.array());
            channel.out.write(pixelData);
            channel.out.flush();
        }
    } catch (Exception e) {
        Slog.w(TAG, "Failed to send camera frame over AF_VSOCK: " + e.getMessage());
    }
}
```

#### 步驟 3：重構麥克風 PCM 音訊傳輸 (`sendVsockAudioPayload`)

替換 `sendVsockAudioPayload` 中 `new Socket("localhost", 5000)` 為 `VsockPortalChannel`：

```java
private VsockPortalChannel mAudioVsockChannel;

private void sendVsockAudioPayload(byte[] pcmData) {
    synchronized (mAudioSocketLock) {
        try {
            if (mAudioVsockChannel == null) {
                mAudioVsockChannel = openAuthenticatedVsockChannel(VSOCK_PORTAL_PORT);
            }
            mAudioVsockChannel.out.write(pcmData);
            mAudioVsockChannel.out.flush();
        } catch (Exception e) {
            closeAudioSocketLocked();
        }
    }
}

private void closeAudioSocketLocked() {
    if (mAudioVsockChannel != null) {
        mAudioVsockChannel.close();
        mAudioVsockChannel = null;
    }
}
```

#### 步驟 4：重構 Location 更新傳輸 (`sendGeoClueLocationUpdate`)

替換 `sendGeoClueLocationUpdate` 中 `new Socket("localhost", 5000)` 為 `VsockPortalChannel`：

```java
private void sendGeoClueLocationUpdate(double lat, double lon, float accuracy) {
    try (VsockPortalChannel channel = openAuthenticatedVsockChannel(VSOCK_PORTAL_PORT)) {
        String json = "{\"Latitude\":" + lat + ",\"Longitude\":" + lon + ",\"Accuracy\":" + accuracy + "}\n";
        channel.out.write(json.getBytes(StandardCharsets.UTF_8));
        channel.out.flush();
    } catch (Exception e) {
        Slog.w(TAG, "Failed to send location update over AF_VSOCK: " + e.getMessage());
    }
}
```

---

## 5. Verification Method (驗證方法)

獨立驗證此項重構的步驟如下：

1. **源碼靜態檢查 (Static Analysis)**：
   - 檢查 `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`：
     `grep -n "localhost" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
     *驗證標準*：命令必須返回 0 匹配（無任何 `localhost` 存在）。
   - 檢查 `Os.socket(40, ...)` 與 `VmSocketAddress` 之使用：
     `grep -n "AF_VSOCK" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
     *驗證標準*：必須包含 AF_VSOCK 40 宣告與 `openAuthenticatedVsockChannel` 方法。

2. **二進位相機 Framed Header 與 Pixel Payload 檢查**：
   - 檢查 `sendVsockCameraFrame` 中 `MAGIC = 0x43414D46`、`timestampNs`、`pixelData` 序列化寫入。

3. **單元與端到端測試驗證 (E2E Test Execution)**：
   - 執行單元測試：
     `python3 tests/e2e/runner.py`
     *驗證標準*：確保 M5 測試（`test_m5_tier1.py`）與相機 Portal 測試全數通過。
