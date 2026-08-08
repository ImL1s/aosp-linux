# Investigation Report: Defect 1 — Host Portal TCP Fallback & Payload Format in `LinuxPortalService.java`

**Agent ID**: `teamwork_preview_explorer_r2_1`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_1`  
**Date**: 2026-08-08  
**Target File**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`

---

## 1. 觀察結果 (Observation)

經對 `LinuxPortalService.java` 原始碼全面審查，直接確認以下缺陷程式碼與行號：

### 1.1 相機 Capture 緩衝區被直接丟棄 (Line 338)
在 `openHardwareCamera(int width, int height)` 函式中：
```java
333: if (mActiveImageReader == null) {
334:     mActiveImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
335:     mActiveImageReader.setOnImageAvailableListener(reader -> {
336:         try (android.media.Image img = reader.acquireNextImage()) {
337:             if (img != null) {
338:                 sendVsockFrame("/dev/video0", width, height);
339:             }
340:         } catch (Exception ignored) {}
341:     }, mCameraHandler);
342: }
```
- **具體問題**: 在 Line 336 成功透過 `ImageReader` 取得 `android.media.Image` 物件 `img` 後，完全沒有調用 `img.getPlanes()` 提取 YUV_420_888 / NV21 像素緩衝區數據，亦未轉化為 HardwareBuffer 描述符。取而代之的是在 Line 338 直接調用 `sendVsockFrame("/dev/video0", width, height)` 發送純文字檔名與解析度標記。

### 1.2 相機 Portal TCP Localhost 回退與虛設字串 Payload (Lines 711 - 718)
`sendVsockFrame` 輔助函式定義如下：
```java
711: // Vsock streaming helper routines (Port 5000)
712: private void sendVsockFrame(String devNode, int width, int height) {
713:     try (Socket s = new Socket("localhost", VSOCK_PORTAL_PORT)) {
714:         OutputStream out = s.getOutputStream();
715:         String msg = "CAM_FRAME:" + devNode + ":" + width + "x" + height + "\n";
716:         out.write(msg.getBytes(StandardCharsets.UTF_8));
717:         out.flush();
718:     } catch (Exception ignored) {}
719: }
```
- **具體問題**:
  1. **TCP Localhost 違規 (Line 713)**: 採用 `new java.net.Socket("localhost", 5000)` 建立 IPv4 TCP 本機迴路連線。此舉完全繞過了 Linux AF_VSOCK 位址族群 (AF_VSOCK=40) 與 VM 隔離邊界，嚴重違反 Rule 5 規範 ("No localhost TCP fallback")。
  2. **偽 Payload 標頭 (Line 715)**: 傳送內容為固定格式 ASCII 字串 `"CAM_FRAME:/dev/video0:1920x1080\n"`，包含 0 位元組真實影像畫面數據。

### 1.3 麥克風 (Audio) Stream TCP Localhost 回退 (Lines 720 - 733)
`sendVsockAudioPayload` 輔助函式定義如下：
```java
720: private void sendVsockAudioPayload(byte[] pcmData) {
721:     synchronized (mAudioSocketLock) {
722:         try {
723:             if (mAudioSocket == null || mAudioSocket.isClosed() || !mAudioSocket.isConnected()) {
724:                 mAudioSocket = new Socket("localhost", VSOCK_PORTAL_PORT);
725:                 mAudioOutputStream = mAudioSocket.getOutputStream();
726:             }
727:             mAudioOutputStream.write(pcmData);
728:             mAudioOutputStream.flush();
729:         } catch (Exception e) {
730:             closeAudioSocketLocked();
731:         }
732:     }
733: }
```
- **具體問題**: Line 724 在傳送 PCM 音訊數據時，同樣使用 `new Socket("localhost", VSOCK_PORTAL_PORT)` 建立 TCP Socket 連線。在 48kHz 採樣率下，每 10~20ms 的 PCM 音訊塊都會經由本機 TCP 發送，缺乏 AF_VSOCK 封包標頭封裝與身份驗證。

### 1.4 地理位置 (Location) GeoClue TCP Localhost 回退 (Lines 746 - 753)
`sendGeoClueLocationUpdate` 輔助函式定義如下：
```java
746: private void sendGeoClueLocationUpdate(double lat, double lon, float accuracy) {
747:     try (Socket s = new Socket("localhost", VSOCK_PORTAL_PORT)) {
748:         OutputStream out = s.getOutputStream();
749:         String json = "{\"Latitude\":" + lat + ",\"Longitude\":" + lon + ",\"Accuracy\":" + accuracy + "}\n";
750:         out.write(json.getBytes(StandardCharsets.UTF_8));
751:         out.flush();
752:         } catch (Exception ignored) {}
753: }
```
- **具體問題**: Line 747 同樣硬編碼 `new Socket("localhost", VSOCK_PORTAL_PORT)` 以 TCP 方式發送 JSON 地理位置訊息，繞過了 AF_VSOCK 通道與標頭封裝。

### 1.5 專案規範與 Phase 要求對照
- **Rule 5 要求**: "No TEST_MODE, simulated-success path, localhost TCP fallback, swallowed transport exception, or prebuilt artifact may count toward production verification."
- **Phase 3 要求**: 統一 AF_VSOCK 位址族群 (`AF_VSOCK=40`)，Host/Guest CID 隔離，Port 5000/5001/5002 隔離，HMAC-SHA256 握手驗證，完全移除生產環境中的 TCP 備用機制。
- **Phase 6 要求**: 替換 Host localhost TCP 為經過認證的 AF_VSOCK (`cid=guestCid, port=5000`)；相機 Portal 必須傳送真實的影像 byte 數據/NV21 緩衝區描述符，而非 `"CAM_FRAME:/dev/video0"` 偽字串。

---

## 2. 推論鏈 (Logic Chain)

1. **推論 1 (基於 1.1, 1.2)**:
   `LinuxPortalService.java` 中現有的相機串流機制只是佔位符 (Placeholder) 實作。當 Android `Camera2` API 在 `onImageAvailable` 觸發時，系統雖然獲取了包含像素數據的 `android.media.Image` 物件，但未做任何像素讀取，反而將其關閉並轉而呼叫 `sendVsockFrame`。

2. **推論 2 (基於 1.2, 1.3, 1.4)**:
   `sendVsockFrame`、`sendVsockAudioPayload` 及 `sendGeoClueLocationUpdate` 全部依賴 `new Socket("localhost", 5000)`。這意味著：
   - Host Portal 傳輸層並未真正連線至 AVF crosvm Guest VM 的 AF_VSOCK 埠號 (Port 5000)。
   - 繞過了 `system/linux_bridge/vsock_framing.h` 所定義的 13-byte packed VSOK header 格式 (`magic = 0x56534F4B`)。
   - 繞過了 Phase 3 所要求的 HMAC-SHA256 挑戰-應答安全握手驗證 (Challenge-Response Handshake)。

3. **推論 3 (重構必要性與目標)**:
   為達成 Phase 3 與 Phase 6 驗證標準，必須在 Host 端 (`frameworks/base/services/core/java/com/android/server/linux/`)：
   - (A) 徹底刪除所有 `new Socket("localhost", ...)` 程式碼。
   - (B) 封裝或引入原生 `VsockPortalClient`（使用 `android.system.Os.socket(40, SOCK_STREAM, 0)` 與 `VmSocketAddress(5000, guestCid)`）。
   - (C) 實作 13-byte Big-Endian VSOK 標頭打包與 HMAC-SHA256 握手。
   - (D) 在 `ImageReader` 中實作真實的 `YUV_420_888` 至 `NV21` / Planar 影像 byte 陣列轉碼，並建立包含真實影像 payload 的二進制封包。

---

## 3. Caveats (注意事項與假設)

1. **唯讀調查範疇**: 本次任務為 Explorer 唯讀調查與重構策略規劃，未對 `frameworks/base` 原始碼進行直接修改。
2. **Guest CID 假設**: 假設 Guest Linux VM 的 CID 為動態配置或預設值（crosvm/AVF 預設 CID 通常為 3），Host 端發起 AF_VSOCK 連線時需從 `LinuxManagerService` 取得作用中 Guest VM 的 CID。
3. **Guest 端與協議對齊**: Guest 端 `bridge-agent` (`guest/bridge-agent/src/portal.rs`) 需要同時解析具備 13-byte VSOK 打包標頭的二進制傳輸封包。二進制欄位位元組序必須全數採用 Big-Endian (Network Byte Order)。

---

## 4. 結論與精確重構策略 (Conclusion & Refactoring Strategy)

### 4.1 精確重構策略 1：實現專屬 `VsockPortalClient` 替代 TCP Socket

建立 `VsockPortalClient.java`（或在 `LinuxPortalService` 中實作 `VsockPortalConnection` 內部類別），使用 Android POSIX Socket API 直接建立 `AF_VSOCK` 連線：

```java
package com.android.server.linux;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.VmSocketAddress;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VsockPortalClient {
    private static final String TAG = "VsockPortalClient";
    private static final int AF_VSOCK = 40;
    private static final int VSOCK_PORTAL_PORT = 5000;
    private static final int VSOK_MAGIC = 0x56534F4B; // "VSOK"

    private FileDescriptor mSocketFd;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private int mSequenceId = 0;
    private boolean mConnected = false;

    public synchronized void connect(int guestCid, byte[] authToken) throws IOException {
        close();
        try {
            mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);
            VmSocketAddress address = new VmSocketAddress(VSOCK_PORTAL_PORT, guestCid);
            Os.connect(mSocketFd, address);

            mInputStream = new FileInputStream(mSocketFd);
            mOutputStream = new FileOutputStream(mSocketFd);
            mConnected = true;

            // 執行 Phase 3 HMAC-SHA256 Handshake 驗證
            performHmacHandshake(authToken);
            Slog.i(TAG, "Connected authenticated AF_VSOCK Portal socket to CID " + guestCid + ":" + VSOCK_PORTAL_PORT);
        } catch (Exception e) {
            close();
            throw new IOException("Failed to connect AF_VSOCK Portal socket to CID " + guestCid, e);
        }
    }

    private void performHmacHandshake(byte[] authToken) throws IOException {
        // 傳送 MSG_AUTH_INIT (0x10) 並校驗 MSG_AUTH_SUCCESS (0x13)
        // 遵循 Phase 3 Golden Vector 驗證合約
    }

    public synchronized void sendPortalFrame(byte frameType, byte[] payload) throws IOException {
        if (!mConnected || mOutputStream == null) {
            throw new IOException("VsockPortalClient is not connected");
        }
        int payloadLen = payload != null ? payload.length : 0;

        // 構造 13-byte Packed VSOK Header (Big-Endian / Network Order)
        ByteBuffer headerBuf = ByteBuffer.allocate(13);
        headerBuf.order(ByteOrder.BIG_ENDIAN);
        headerBuf.putInt(VSOK_MAGIC);      // 4 bytes: Magic 0x56534F4B
        headerBuf.put(frameType);          // 1 byte : FrameType (e.g. 0x01 CONTROL/PORTAL)
        headerBuf.putInt(payloadLen);      // 4 bytes: Payload Length
        headerBuf.putInt(++mSequenceId);   // 4 bytes: Sequence ID

        mOutputStream.write(headerBuf.array());
        if (payloadLen > 0) {
            mOutputStream.write(payload);
        }
        mOutputStream.flush();
    }

    public synchronized void close() {
        mConnected = false;
        try {
            if (mInputStream != null) mInputStream.close();
            if (mOutputStream != null) mOutputStream.close();
            if (mSocketFd != null && mSocketFd.valid()) Os.close(mSocketFd);
        } catch (Exception ignored) {}
        mInputStream = null;
        mOutputStream = null;
        mSocketFd = null;
    }
}
```

### 4.2 精確重構策略 2：真實 YUV_420_888 至 NV21 影像 byte 陣列轉碼與封包格式

重構 `LinuxPortalService.java` 中的 `openHardwareCamera` 與 `sendVsockFrame`：

#### A. 影像緩衝區轉碼 (`convertYuv420ToNv21`)
```java
private byte[] convertYuv420ToNv21(android.media.Image image) {
    int width = image.getWidth();
    int height = image.getHeight();
    byte[] nv21 = new byte[width * height * 3 / 2];

    android.media.Image.Plane[] planes = image.getPlanes();
    ByteBuffer yBuffer = planes[0].getBuffer();
    ByteBuffer uBuffer = planes[1].getBuffer();
    ByteBuffer vBuffer = planes[2].getBuffer();

    int ySize = yBuffer.remaining();
    int uSize = uBuffer.remaining();
    int vSize = vBuffer.remaining();

    // 複製 Y 平面
    yBuffer.get(nv21, 0, ySize);

    // 複製/交錯 UV 平面為 NV21 (VU 交錯)
    int uvPos = width * height;
    byte[] vBytes = new byte[vSize];
    byte[] uBytes = new byte[uSize];
    vBuffer.get(vBytes);
    uBuffer.get(uBytes);

    int rowStride = planes[1].getRowStride();
    int pixelStride = planes[1].getPixelStride();

    for (int row = 0; row < height / 2; row++) {
        for (int col = 0; col < width / 2; col++) {
            int index = row * rowStride + col * pixelStride;
            if (uvPos < nv21.length - 1) {
                nv21[uvPos++] = vBytes[Math.min(index, vBytes.length - 1)];
                nv21[uvPos++] = uBytes[Math.min(index, uBytes.length - 1)];
            }
        }
    }
    return nv21;
}
```

#### B. 相機 Portal 封包構造與傳送 (`sendVsockCameraFramePayload`)
定義二進制相機 Header Format：
- `subType`: `0x43414D46` ("CAMF") — 4 bytes
- `width`: uint32 — 4 bytes
- `height`: uint32 — 4 bytes
- `format`: uint32 (`ImageFormat.NV21` / 0x11) — 4 bytes
- `timestampNs`: uint64 — 8 bytes
- `payloadSizeBytes`: uint32 — 4 bytes
- `pixelBytes`: byte[payloadSizeBytes]

```java
private void sendVsockCameraFramePayload(int width, int height, long timestampNs, byte[] nv21Bytes) {
    if (mVsockPortalClient == null || nv21Bytes == null) return;

    ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + 4 + 8 + 4 + nv21Bytes.length);
    buf.order(ByteOrder.BIG_ENDIAN);
    buf.putInt(0x43414D46);          // SubType "CAMF"
    buf.putInt(width);               // 影像寬度
    buf.putInt(height);              // 影像高度
    buf.putInt(ImageFormat.NV21);    // 影像格式
    buf.putLong(timestampNs);        // 納秒時間戳
    buf.putInt(nv21Bytes.length);    // 影像 Buffer 長度
    buf.put(nv21Bytes);              // 實質 NV21 影像 byte 陣列

    try {
        mVsockPortalClient.sendPortalFrame((byte) 0x01, buf.array());
    } catch (IOException e) {
        Slog.w(TAG, "Failed to send camera frame over AF_VSOCK: " + e.getMessage());
    }
}
```

#### C. 修改 `openHardwareCamera` 監聽器處置 logic
```java
mActiveImageReader.setOnImageAvailableListener(reader -> {
    try (android.media.Image img = reader.acquireNextImage()) {
        if (img != null) {
            byte[] nv21Frame = convertYuv420ToNv21(img);
            sendVsockCameraFramePayload(img.getWidth(), img.getHeight(), img.getTimestamp(), nv21Frame);
        }
    } catch (Exception e) {
        Slog.w(TAG, "Error acquiring or sending camera frame: " + e.getMessage());
    }
}, mCameraHandler);
```

### 4.3 精確重構策略 3：修改 Audio 與 Location 發送輔助函式
- 將 `sendVsockAudioPayload(byte[] pcmData)` 修改為構造二進制標頭 (`subType = 0x4155444F` "AUDO") 後經由 `mVsockPortalClient.sendPortalFrame((byte) 0x01, payload)` 發送，刪除 `new Socket("localhost", 5000)`。
- 將 `sendGeoClueLocationUpdate(double lat, double lon, float accuracy)` 修改為構造二進制標頭 (`subType = 0x47454F43` "GEOC") 後經由 `mVsockPortalClient.sendPortalFrame((byte) 0x01, payload)` 發送，刪除 `new Socket("localhost", 5000)`。

---

## 5. 驗證方法 (Verification Method)

### 5.1 靜態與單元驗證
1. **零 TCP Socket 檢測**:
   執行指令：
   ```bash
   grep -rn "new Socket(" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java
   ```
   *通過條件*: 回傳結果必須完全為空（0 行匹配）。

2. **單元測試執行**:
   執行指令：
   ```bash
   javac -d /tmp/classes frameworks/base/services/core/java/com/android/server/linux/*.java tests/unit/LinuxPortalServiceTest.java
   java -cp /tmp/classes tests.unit.LinuxPortalServiceTest
   ```
   *通過條件*: `LinuxPortalServiceTest` 輸出 `PASS: LinuxPortalServiceTest executed successfully.`。

### 5.2 端到端 (E2E) 生產關卡驗證
1. **建立與啟動 VM**:
   在 Target 環境執行 `cmd linux start` 啟動 Guest Linux VM。
2. **觸發相機 Portal 串流**:
   在 Guest 端執行相機測試指令或使用 Phase 7 測試腳本觸發 Portal 相機數據流。
3. **數據包與抓包觀察**:
   觀察 Guest 端 `bridge-agent` 日誌與 AF_VSOCK socket (Port 5000)：
   - 確認接收到的每一個封包開頭前 4 位元組均為 `0x56534F4B` ("VSOK")。
   - 確認相機 Payload 的 SubType 為 `0x43414D46` ("CAMF")，且內含與解析度匹配（如 $1920 \times 1080 \times 1.5 = 3,110,400$ 位元組）的真實 NV21 影像資料流。
   - 檢查 Linux 系統 netstat/ss，無任何 5000 埠號的 TCP localhost 連線存在。
