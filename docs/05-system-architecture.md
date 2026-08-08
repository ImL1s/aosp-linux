# 第五章：完整系統架構圖

## 5.1 完整元件架構圖

```mermaid
graph TD
    subgraph "User Layer"
        U1[使用者觸控 / 實體鍵盤]
        U2[Android IME]
    end

    subgraph "Android Apps (APK/ART)"
        A1[Android Native Apps]
        A2[Native Touch Terminal App]
        A3[LinuxAppProxyActivity]
        A4[Linux Settings App]
        A5[LinuxFiles SAF Provider UI]
    end

    subgraph "Android Launcher & UI"
        L1[Launcher3 / HomeActivity]
        L2[Recents / Overview]
        L3[SystemUI]
    end

    subgraph "Android Framework"
        F1[ActivityManager / ActivityTaskManager]
        F2[WindowManager / SurfaceFlinger]
        F3[PackageManager / AppOps]
        F4[DisplayManager]
        F5[InputManager / InputDispatcher]
        F6[StorageManager / DocumentsProvider]
        F7[UserManager / Credential Encrypted]
        F8[PowerManager / Doze]
        F9[ThermalManager]
        F10[AudioService / AudioFocus]
        F11[ConnectivityService / NetworkPolicyManager]
        F12[LocationManager]
        F13[CameraManager / Camera2 HAL]
        F14[KeyMint / Keystore]
    end

    subgraph "LinuxManager Framework Services [NEW]"
        LMS[LinuxManagerService<br/>- VM lifecycle state machine<br/>- per-user Linux instance<br/>- resource management]
        LBS[LinuxBridgeService<br/>- vsock connection manager<br/>- RPC routing]
        LPS[LinuxPortalService<br/>- XDG portal bridge<br/>- AppOps gatekeeper]
        LWS[LinuxWindowBridgeService<br/>- Wayland surface mapping<br/>- Task/Window bridge]
        LAR[LinuxAppRegistryService<br/>- .desktop file sync<br/>- icon/MIME mapping]
        LIM[LinuxImageManager<br/>- image download/verify<br/>- A/B update]
    end

    subgraph "AVF / VirtualizationService [EXISTING + EXTEND]"
        VS[VirtualizationService<br/>IVirtualizationService AIDL]
        VM[virtmgr (Rust)<br/>per-app VM manager]
        CV[crosvm<br/>/apex/com.android.virt/bin/crosvm]
        KV[KVM]
    end

    subgraph "VirtIO Devices [EXISTING + NEW]"
        VD1[virtio-vsock<br/>AF_VSOCK]
        VD2[virtio-blk<br/>disk images]
        VD3[virtio-fs<br/>shared directory]
        VD4[virtio-snd<br/>audio]
        VD5[virtio-gpu<br/>virglrenderer / gfxstream]
        VD6[virtio-input<br/>keyboard/mouse/touch]
        VD7[virtio-net<br/>TAP/NAT]
    end

    subgraph "Linux Guest VM [Debian 12 ARM64]"
        GK[Linux Kernel 6.6+<br/>ARM64, KVM guest optimized]
        GS[systemd PID 1]

        subgraph "Guest Agents [NEW]"
            BA[android-bridge-agent (Rust)<br/>Port 5000 control RPC<br/>HMAC-SHA256 auth]
            PA[pty-agent<br/>Port 5001 terminal PTY<br/>openpty() / bash / zsh]
            WA[wayland-agent / Sommelier<br/>Port 5002 + virtio-gpu<br/>Wayland compositor]
            XA[xdg-portal-agent<br/>XDG Desktop Portal impl<br/>D-Bus bridge]
            UA[update-agent<br/>Guest A/B OTA<br/>apt integration]
        end

        subgraph "Guest Services"
            GN[Guest Network (NAT)]
            GD[D-Bus daemon]
            GW[Weston / Mutter (optional)]
        end

        subgraph "Guest Storage"
            GS1[EROFS base_a.img<br/>read-only, signed]
            GS2[EROFS base_b.img<br/>A/B slot B]
            GS3[ext4 overlay_rw.img<br/>writable overlay]
            GS4[LUKS2 user_home.img<br/>CE key encrypted]
            GS5[swap.img<br/>optional]
        end

        subgraph "Linux Applications"
            LA1[Bash / Zsh / Fish]
            LA2[Git / GCC / Clang / Rust]
            LA3[Python / Node.js]
            LA4[SSH server/client]
            LA5[VS Code / GIMP / LibreOffice]
            LA6[APT Package Manager]
        end
    end

    U1 --> A2
    U2 --> A2
    L1 --> A2 & A3
    L2 --> A3
    A2 --> LMS
    A3 --> LWS
    A4 --> LMS
    A5 --> F6

    LMS --> VS
    LMS --> F7
    LMS --> F14
    LBS --> VD1
    LPS --> F3 & F12 & F13
    LWS --> F2 & VD5
    LAR --> LBS
    LIM --> F6

    VS --> VM --> CV --> KV

    CV --- VD1 & VD2 & VD3 & VD4 & VD5 & VD6 & VD7

    VD1 <--> BA & PA & WA & XA & UA
    VD3 <--> F6
    VD5 <--> WA
    VD4 <--> F10
    VD7 <--> F11

    GK --> GS --> BA & PA & WA & XA & UA & GN & GD
    BA --> LA1 & LA6
    PA --> LA1
    WA --> LA5
    GS1 & GS2 & GS3 & GS4 --> GK
```

## 5.2 Trust Boundary 圖

```
╔═══════════════════════════════════════════════════════════════╗
║                ANDROID TRUST DOMAIN                           ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │  system_server (uid=1000)                               │  ║
║  │  LinuxManagerService / LinuxBridgeService               │  ║
║  │  Full access to Android Binder services                 │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │  linux_bridge daemon (uid=linux_bridge)                 │  ║
║  │  SELinux: linux_bridge.te                               │  ║
║  │  Access: AF_VSOCK only, NO Binder, NO /dev/raw          │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │  crosvm (uid=crosvm, SELinux: crosvm.te)                │  ║
║  │  Access: KVM, vsock, blk, virtio devices only           │  ║
║  │  NO Android Binder, NO /data direct                     │  ║
║  └─────────────────────────────────────────────────────────┘  ║
╠═══════════════════════════════╤═══════════════════════════════╣
║                               │ AF_VSOCK (VM boundary)        ║
║                               │ physmem isolated by KVM       ║
╠═══════════════════════════════╪═══════════════════════════════╣
║                LINUX GUEST TRUST DOMAIN                       ║
║                               │                               ║
║  ┌────────────────────────────▼────────────────────────────┐  ║
║  │  android-bridge-agent (uid=1000 in Guest)               │  ║
║  │  Authenticates all incoming RPC                         │  ║
║  │  Gateway to all guest capabilities                      │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │  Guest root (uid=0 IN GUEST ONLY)                       │  ║
║  │  Can: install packages, manage services, full /         │  ║
║  │  Cannot: escape VM, access Host /data, /dev/real_hw     │  ║
║  └─────────────────────────────────────────────────────────┘  ║
╚═══════════════════════════════════════════════════════════════╝

TRUST ASSERTIONS:
1. VM memory boundary: KVM page tables (hardware enforced)
2. Communication: ONLY through AF_VSOCK with HMAC-SHA256 auth
3. Guest root privilege: DOES NOT propagate to Host
4. All hardware access: MEDIATED by Android Framework (Portal)
5. File access: ONLY through virtiofs with SAF restrictions
6. Network: ONLY through NAT, subject to Android VPN policy
```

## 5.3 Process / UID / SELinux Domain 圖

```
UID=0    (root)       → init, kernel services
UID=1000 (system)     → system_server [LinuxManagerService inside]
UID=linux_bridge      → linux_bridge daemon [NEW SELinux domain]
UID=crosvm            → crosvm process [EXISTING SELinux: crosvm.te]
UID=virtmgr           → virtmgr process [EXISTING SELinux: early_virtmgr.te]
UID=linux_terminal    → Native Terminal App [NEW SELinux domain]
UID=linux_portal      → LinuxPortalService [NEW SELinux domain]
UID=linux_window      → LinuxWindowBridgeService [NEW SELinux domain]
UID=<app>             → Per-app sandbox [Android standard]

SELinux Domains (NEW):
├── linux_manager     → system_server 中的 Linux 管理部分
├── linux_vm_launcher → VM 啟動器
├── linux_bridge      → vsock 橋接 daemon
├── linux_image_manager → image 管理
├── linux_portal      → XDG portal bridge
├── linux_window_bridge → Wayland surface 管理
├── linux_file_bridge → virtiofs 橋接
├── linux_terminal    → 原生 Terminal App
└── linux_update_service → Guest 更新服務
```

## 5.4 序列圖：Android 開啟 Terminal

```
User            Terminal App    LinuxManagerService    crosvm/VM       Guest
 │                  │                   │                  │             │
 │ tap Terminal     │                   │                  │             │
 │─────────────────►│                   │                  │             │
 │                  │ getVmState()      │                  │             │
 │                  │──────────────────►│                  │             │
 │                  │                   │                  │             │
 │                  │ [State=STOPPED]   │                  │             │
 │                  │◄──────────────────│                  │             │
 │                  │                   │                  │             │
 │                  │ ensureRunning()   │                  │             │
 │                  │──────────────────►│                  │             │
 │                  │                   │ createVm()       │             │
 │                  │                   │─────────────────►│             │
 │                  │                   │ start()          │             │
 │                  │                   │─────────────────►│             │
 │                  │                   │                  │ KVM boot    │
 │                  │                   │                  │────────────►│
 │                  │                   │                  │             │ systemd starts
 │                  │                   │                  │             │ bridge-agent starts
 │                  │                   │                  │◄────────────│
 │                  │                   │ onPayloadReady() │             │
 │                  │                   │◄─────────────────│             │
 │                  │                   │                  │             │
 │                  │                   │ HMAC handshake   │             │
 │                  │                   │─────────────────────────────►  │
 │                  │                   │◄─────────────────────────────  │
 │                  │                   │                                │
 │                  │ createPtySession()│                                │
 │                  │──────────────────►│                                │
 │                  │                   │ vsock 5001 connect             │
 │                  │                   │───────────────────────────────►│
 │                  │                   │                                │ openpty()
 │                  │                   │                                │ bash spawn
 │                  │ TerminalSession   │                                │
 │                  │◄──────────────────│                                │
 │ Terminal ready   │                   │                                │
 │◄─────────────────│                   │                                │
```

## 5.5 序列圖：Linux App 請求相機

```
Linux App    portal-agent    LinuxPortalService    PermissionController    CameraManager
    │              │                  │                    │                    │
    │ openCamera() │                  │                    │                    │
    │ (XDG Portal) │                  │                    │                    │
    │─────────────►│                  │                    │                    │
    │              │ vsock RPC        │                    │                    │
    │              │ CAMERA_REQUEST   │                    │                    │
    │              │─────────────────►│                    │                    │
    │              │                  │ checkPermission()  │                    │
    │              │                  │───────────────────►│                    │
    │              │                  │                    │ showDialog()       │
    │              │                  │ [User grants]      │                    │
    │              │                  │◄───────────────────│                    │
    │              │                  │                    │                    │
    │              │                  │ openCamera()       │                    │
    │              │                  │───────────────────────────────────────►│
    │              │                  │◄───────────────────────────────────────│
    │              │                  │ stream frames via vsock                 │
    │              │◄─────────────────│                    │                    │
    │◄─────────────│                  │                    │                    │
    │ camera frames│                  │                    │                    │
```

## 5.6 序列圖：VM Suspend / Resume

```
PowerManager    LinuxManagerService    crosvm      Guest
    │                   │                │           │
    │ screen off        │                │           │
    │──────────────────►│                │           │
    │                   │ prepareFreeze()│           │
    │                   │───────────────────────────►│
    │                   │                │           │ flush PTY buffers
    │                   │                │           │ sync filesystem
    │                   │◄──────────────────────────│
    │                   │ suspend()      │           │
    │                   │───────────────►│           │
    │                   │                │ VM pause  │
    │                   │                │ save state│
    │                   │◄───────────────│           │
    │◄──────────────────│                │           │
    │ [screen off, VM suspended]         │           │
    │                                    │           │
    │ screen on         │                │           │
    │──────────────────►│                │           │
    │                   │ resume()       │           │
    │                   │───────────────►│           │
    │                   │                │ VM resume │
    │                   │                │──────────►│
    │                   │                │           │ restore PTY
    │                   │◄───────────────│           │
    │◄──────────────────│                │           │
    │ [Terminal reconnected]             │           │
```

## 5.7 序列圖：OTA 更新 Host 與 Guest

```
OTA Service    LinuxManagerService    GuestUpdateAgent    ADB/Update Server
    │                   │                   │                    │
    │ Host OTA avail    │                   │                    │
    │──────────────────►│                   │                    │
    │                   │ notifyGuestOTA()  │                    │
    │                   │──────────────────►│                    │
    │                   │                   │ downloadGuestImg() │
    │                   │                   │───────────────────►│
    │                   │                   │◄───────────────────│
    │                   │                   │ verifySignature()  │
    │                   │                   │ [AVB RSA-4096]     │
    │                   │                   │ stageToSlotB()     │
    │                   │                   │ markReadyForOTA()  │
    │                   │──────────────────►│                    │
    │                   │ Host OTA reboot   │                    │
    │◄──────────────────│                   │                    │
    │ [Host reboots, applies OTA]           │                    │
    │                                       │                    │
    │ [Next boot: switchToGuestSlotB()]     │                    │
    │──────────────────►│                   │                    │
    │                   │ setActiveSlot(B)  │                    │
    │                   │──────────────────►│                    │
    │                   │                   │ [Boot with base_b] │
    │ [Success: mark B as active]           │                    │
    │ [Failure: rollback to A]              │                    │
```
