# Java Architecture & Binder IPC Survey Report

**Project**: AOSP Dual-OS System Architecture  
**Role**: `survey_explorer_1` (Java Architecture & Binder Explorer)  
**Date**: 2026-08-14  
**Status**: Completed Read-Only Investigation  

---

## Executive Summary

A comprehensive audit was performed across all Java (`.java`) and AIDL (`.aidl`) source files in the codebase. The investigation focused on Java syntax and compilation errors, app-system decoupling (removing reflection in favor of canonical Binder IPC via `ILinuxWindowBridge.aidl`), AppOps permission flow in `LinuxPermissionActivity.java`, and signature matching between AIDL interfaces and Java consumers.

Key Findings:
1. **Compilation Syntax Error**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java` lines 264–274 contain a duplicate, unclosed `attachSurfaceControlToBridge` method declaration that prevents Java compilation.
2. **SystemServer Reflection Boundary Violation**: `LinuxAppProxyActivity.java` lines 276–307 use Java reflection (`Class.forName("com.android.server.linux.LinuxWindowBridgeService")`) to call private framework methods. Meanwhile, `LinuxWindowBridgeService.java` does NOT implement `ILinuxWindowBridge.Stub` or publish a Binder service.
3. **Stubbed Permission Activity**: `LinuxPermissionActivity.java` immediately calls `finish()` in `onCreate()` without extracting Intent extras (`app_id`, `op`), displaying user prompt dialogs, or invoking `LinuxPortalService.setAppOp(...)` / `AppOpsManager`.
4. **AIDL Interface Mismatches**:
   - `ILinuxWindowBridge.aidl` is un-implemented by `LinuxWindowBridgeService.java`.
   - `ILinuxPortalService.aidl` (`getCameraStatus()`, `getAudioStatus()`, `getLocation()`) is un-implemented by `LinuxPortalService.java`.

---

## 1. Inventory of Java & AIDL Files

The workspace contains **72 Java & AIDL files** distributed across Framework, SystemServer, Application, Native System, and Test directories.

### 1.1 AIDL Interface Definitions (9 Files)
| File Path | Package | Purpose |
|---|---|---|
| `frameworks/base/core/java/android/system/linux/ILinuxBridge.aidl` | `android.system.linux` | IPC interface for host native bridge daemon control |
| `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl` | `android.system.linux` | System-private interface for VM lifecycle, PTY, & app launching |
| `frameworks/base/core/java/android/system/linux/ILinuxPortalService.aidl` | `android.system.linux` | Hardware portal status interface for XDG portals |
| `frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl` | `android.system.linux` | Oneway callback interface for VM state and resource usage updates |
| `frameworks/base/core/java/android/system/linux/ILinuxStorageProvider.aidl` | `android.system.linux` | Interface for SAF storage provider mount & CE key status |
| `frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl` | `android.system.linux` | Oneway callback interface for terminal PTY stream data & events |
| `frameworks/base/core/java/android/system/linux/ILinuxWindowBridge.aidl` | `android.system.linux` | Interface for Wayland window surface forwarding & lifecycle |
| `frameworks/base/core/java/android/system/linux/LinuxAppInfo.aidl` | `android.system.linux` | Parcelable declaration for Linux application metadata |
| `system/linux_bridge/ILinuxBridgeDaemon.aidl` | `android.system.linux` | AIDL interface for native daemon process isolation |

### 1.2 Framework Public / System API Java Classes (3 Files)
| File Path | Package | Purpose |
|---|---|---|
| `frameworks/base/core/java/android/system/linux/LinuxAppInfo.java` | `android.system.linux` | Parcelable model holding desktop app metadata |
| `frameworks/base/core/java/android/system/linux/LinuxManager.java` | `android.system.linux` | Public SDK facade for `ILinuxManager` service |
| `frameworks/base/core/java/android/system/linux/LinuxWindowBridge.java` | `android.system.linux` | Public SDK facade wrapper for `ILinuxWindowBridge` |

### 1.3 SystemServer Implementation Classes (15 Files)
| File Path | Package | Purpose |
|---|---|---|
| `frameworks/base/services/core/java/com/android/server/linux/LinuxAppOpsPolicy.java` | `com.android.server.linux` | AppOps security check stub |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxAppProxyActivity.java` | `com.android.server.linux` | System server duplicate copy of app proxy activity |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicy.java` | `com.android.server.linux` | Audio policy routing handler |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java` | `com.android.server.linux` | Vsock socket connection client & daemon manager |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxCameraPolicy.java` | `com.android.server.linux` | Camera contention policy handler |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxLocationPolicy.java` | `com.android.server.linux` | Location obfuscation & GeoClue handler |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxLuksProvider.java` | `com.android.server.linux` | Guest LUKS2 storage mapper provider |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java` | `com.android.server.linux` | In-process local service interface for cross-system communication |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` | `com.android.server.linux` | Core SystemServer service implementing `ILinuxManager.Stub` |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java` | `com.android.server.linux` | Activity prompt for user permission dialogs |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` | `com.android.server.linux` | SystemServer service for Camera2, AudioRecord, & Location portals |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxStorageProvider.java` | `com.android.server.linux` | SAF `DocumentsProvider` exposing `/home/user` & `/mnt/shared` |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxVirtiofsService.java` | `com.android.server.linux` | Virtiofs mount orchestration service |
| `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java` | `com.android.server.linux` | Wayland surface registry & Task ID allocation service |
| `frameworks/base/services/core/java/com/android/server/linux/VsockPortalClient.java` | `com.android.server.linux` | Vsock 5000 portal transport packet framing client |

### 1.4 Application Layer Java Classes (22 Files)
- `packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java`
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java`
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/ime/*` (6 files: CJKImeHandler, CjkComposingTextManager, CjkComposingWindow, ComposingTextSpan, TerminalInputConnection, TerminalKeyEncoder)
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/*` (3 files: PtySender, VsockPtyFramer, VsockTerminalClient)
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/parser/*` (1 file: VTermParser)
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/*` (6 files: ColorPalette, GlyphCache, NativeSurfaceCanvasRenderer, TerminalCell, TerminalScreenMatrix, TerminalSurfaceView)
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/touch/*` (1 file: SgrMouseProtocolGenerator)
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/window/*` (1 file: WindowResizePacer)

---

## 2. Syntax Error Analysis: `LinuxAppProxyActivity.java`

**File Target**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`

### 2.1 Code Inspection (Lines 264–290)
```java
264: private void attachSurfaceControlToBridge(int surfaceId, SurfaceControl surfaceControl) {
265:     if (surfaceId <= 0) {
266:         Log.w(TAG, "Invalid surfaceId: " + surfaceId + ", skipping attachSurfaceControl");
267:         return;
268:     }
269: 
270: private void attachSurfaceControlToBridge(int surfaceId, SurfaceControl surfaceControl) {
271:     if (surfaceId <= 0) {
272:         Log.w(TAG, "Invalid surfaceId: " + surfaceId + ", skipping attachSurfaceControl");
273:         return;
274:     }
275: 
276:     try {
277:         Class<?> bridgeClass = Class.forName("com.android.server.linux.LinuxWindowBridgeService");
...
```

### 2.2 Root Cause
Line 264 opens `attachSurfaceControlToBridge`. Lines 265–268 check `surfaceId <= 0`. Missing closing brace `}` before line 270. Line 270 duplicates the method signature inside the outer method body.

### 2.3 Required Remediation
Remove lines 264–268 so that only a single, well-formed method header exists.

---

## 3. Reflection Calls vs. Canonical Binder IPC Analysis

### 3.1 Existing Reflection Pattern
In `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`:
```java
// Line 276-289 (attach)
Class<?> bridgeClass = Class.forName("com.android.server.linux.LinuxWindowBridgeService");
java.lang.reflect.Method getInstanceMethod = bridgeClass.getMethod("getInstance");
Object instance = getInstanceMethod.invoke(null);
if (instance != null) {
    java.lang.reflect.Method attachMethod = bridgeClass.getMethod("attachSurfaceControl", int.class, SurfaceControl.class);
    attachMethod.invoke(instance, surfaceId, surfaceControl);
}

// Line 295-305 (detach)
Class<?> bridgeClass = Class.forName("com.android.server.linux.LinuxWindowBridgeService");
java.lang.reflect.Method getInstanceMethod = bridgeClass.getMethod("getInstance");
Object instance = getInstanceMethod.invoke(null);
if (instance != null) {
    java.lang.reflect.Method attachMethod = bridgeClass.getMethod("attachSurfaceControl", int.class, SurfaceControl.class);
    attachMethod.invoke(instance, surfaceId, (Object) null);
}
```

### 3.2 Architectural Flaws
1. **App-System Decoupling Violation**: Standard Android applications running under `packages/apps/` must not access internal SystemServer classes (`com.android.server.*`) via reflection.
2. **Missing Binder Service Implementation**: `LinuxWindowBridgeService.java` does NOT extend `ILinuxWindowBridge.Stub` nor is it registered in `ServiceManager` under `"linux_window_bridge"`.
3. **AIDL Interface Definition**:
   `frameworks/base/core/java/android/system/linux/ILinuxWindowBridge.aidl` contains:
   ```aidl
   package android.system.linux;

   interface ILinuxWindowBridge {
       void onSurfaceCreated(int surfaceId, in android.view.Surface surface);
       void onSurfaceChanged(int surfaceId, int width, int height);
       void onSurfaceDestroyed(int surfaceId);
   }
   ```

### 3.3 Outline of Exact Changes for Canonical Binder IPC

#### Step 1: SystemServer Binder Service (`LinuxWindowBridgeService.java`)
1. Add an inner `BinderService` class extending `ILinuxWindowBridge.Stub` inside `LinuxWindowBridgeService.java` (or have `LinuxWindowBridgeService` extend `ILinuxWindowBridge.Stub`).
2. Implement `ILinuxWindowBridge.Stub` methods:
   ```java
   public final class BinderService extends ILinuxWindowBridge.Stub {
       @Override
       public void onSurfaceCreated(int surfaceId, android.view.Surface surface) {
           // Obtain SurfaceControl from Surface or attach Surface
           attachSurfaceToBridge(surfaceId, surface);
       }

       @Override
       public void onSurfaceChanged(int surfaceId, int width, int height) {
           configureSurface(surfaceId, width, height);
       }

       @Override
       public void onSurfaceDestroyed(int surfaceId) {
           detachSurfaceFromBridge(surfaceId);
       }
   }
   ```
3. Register `"linux_window_bridge"` service with `ServiceManager.addService("linux_window_bridge", mBinderService)`.

#### Step 2: SDK Facade (`LinuxWindowBridge.java`)
Expose methods delegating to `ILinuxWindowBridge`:
```java
public void onSurfaceCreated(int surfaceId, Surface surface) throws RemoteException {
    mService.onSurfaceCreated(surfaceId, surface);
}
public void onSurfaceChanged(int surfaceId, int width, int height) throws RemoteException {
    mService.onSurfaceChanged(surfaceId, width, height);
}
public void onSurfaceDestroyed(int surfaceId) throws RemoteException {
    mService.onSurfaceDestroyed(surfaceId);
}
```

#### Step 3: Application Activity (`LinuxAppProxyActivity.java`)
Replace reflection calls with canonical Binder IPC calls:
```java
private ILinuxWindowBridge getWindowBridge() {
    IBinder binder = android.os.ServiceManager.getService("linux_window_bridge");
    return ILinuxWindowBridge.Stub.asInterface(binder);
}

@Override
public void surfaceCreated(SurfaceHolder holder) {
    try {
        ILinuxWindowBridge bridge = getWindowBridge();
        if (bridge != null) {
            bridge.onSurfaceCreated(mSurfaceId, holder.getSurface());
        }
    } catch (RemoteException e) {
        Log.e(TAG, "Failed to call onSurfaceCreated via Binder", e);
    }
}

@Override
public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    try {
        ILinuxWindowBridge bridge = getWindowBridge();
        if (bridge != null) {
            bridge.onSurfaceChanged(mSurfaceId, width, height);
        }
    } catch (RemoteException e) {
        Log.e(TAG, "Failed to call onSurfaceChanged via Binder", e);
    }
}

@Override
public void surfaceDestroyed(SurfaceHolder holder) {
    try {
        ILinuxWindowBridge bridge = getWindowBridge();
        if (bridge != null) {
            bridge.onSurfaceDestroyed(mSurfaceId);
        }
    } catch (RemoteException e) {
        Log.e(TAG, "Failed to call onSurfaceDestroyed via Binder", e);
    }
}
```

---

## 4. `LinuxPermissionActivity.java` & AppOps Integration Analysis

**File Target**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java`

### 4.1 Current Implementation (Lines 1–23)
```java
package com.android.server.linux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class LinuxPermissionActivity extends Activity {
    public static void launchPrompt(Context context, String appId, String op) {
        Intent intent = new Intent(context, LinuxPermissionActivity.class);
        intent.putExtra("app_id", appId);
        intent.putExtra("op", op);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
```

### 4.2 Defect Analysis
1. `onCreate` immediately calls `finish()`. It never displays a dialog or UI prompt to the user.
2. Extras `app_id` and `op` are sent in `launchPrompt`, but never retrieved in `onCreate()`.
3. Does not record user decisions back into `LinuxPortalService.getInstance().setAppOp(appId, op, MODE_ALLOWED/MODE_DENIED)` or update `AppOpsManager`.

### 4.3 Required Remediation Outline
1. In `onCreate(Bundle savedInstanceState)`:
   - Extract `appId = getIntent().getStringExtra("app_id")` and `op = getIntent().getStringExtra("op")`.
   - Create an `AlertDialog.Builder(this)` dialog asking:  
     *"Allow Linux Application [appId] to access [op]?"*
   - Positive button ("Allow"):  
     `LinuxPortalService.getInstance().setAppOp(appId, op, LinuxPortalService.MODE_ALLOWED); finish();`
   - Negative button ("Deny") / Cancellation:  
     `LinuxPortalService.getInstance().setAppOp(appId, op, LinuxPortalService.MODE_DENIED); finish();`

---

## 5. AIDL Method & Java Consumer Matching Audit

| AIDL File | Java Service Implementation | Java SDK / Consumer | Audit Status | Findings & Notes |
|---|---|---|---|---|
| `ILinuxManager.aidl` (14 methods) | `LinuxManagerService$BinderService` | `LinuxManager.java` | **MATCH** | All 14 method signatures match parameter types, counts, and return values perfectly across Binder call stack. |
| `ILinuxBridge.aidl` (2 methods) | `LinuxBridgeService.java` | `LinuxManagerService.java` | **MATCH** | `isDaemonConnected()` and `sendControlMessage(String)` match. |
| `ILinuxStatusCallback.aidl` (2 methods) | `LinuxManagerService.java` (dispatch) | `LinuxManager.java` (`Stub`) | **MATCH** | Oneway callbacks `onStateChanged` and `onResourceUsageUpdated` match. |
| `ILinuxTerminalCallback.aidl` (4 methods) | `LinuxManagerService.java` (dispatch) | `LinuxManager.java` (`Stub`) | **MATCH** | Oneway callbacks `onDataReceived`, `onTitleChanged`, `onBell`, `onSessionClosed` match. |
| `ILinuxStorageProvider.aidl` (2 methods) | `LinuxStorageProvider.java` | SAF callers | **MATCH** | Methods `isStorageMounted()` and `isCeKeyAvailable()` match underlying status checks. |
| `ILinuxWindowBridge.aidl` (3 methods) | `LinuxWindowBridgeService.java` | `LinuxAppProxyActivity.java` | **MISMATCH** | `LinuxWindowBridgeService` does not implement `ILinuxWindowBridge.Stub`. `LinuxAppProxyActivity` uses reflection instead of calling AIDL. |
| `ILinuxPortalService.aidl` (3 methods) | `LinuxPortalService.java` | Framework callers | **MISMATCH** | `ILinuxPortalService.aidl` defines `getCameraStatus()`, `getAudioStatus()`, `getLocation()`. `LinuxPortalService.java` does NOT extend `ILinuxPortalService.Stub` and lacks these getters. |
| `ILinuxBridgeDaemon.aidl` (8 methods) | Host C++ `linux_bridge` daemon | `LinuxBridgeService.java` | **MATCH** | Native daemon AIDL definitions match host bridge service RPC calls. |
| `LinuxAppInfo.aidl` | `LinuxAppInfo.java` | `LinuxManager.java` | **MATCH** | Parcelable data type definition matches. |

---

## 6. Summary of Actionable Implementation Tasks (For Implementer Agents)

1. **Fix Syntax Error**: Clean up duplicate `attachSurfaceControlToBridge` method declaration in `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`.
2. **Implement Binder IPC for Window Bridge**:
   - Make `LinuxWindowBridgeService` implement `ILinuxWindowBridge.Stub` and register with `ServiceManager`.
   - Wire `ILinuxWindowBridge.aidl` methods (`onSurfaceCreated`, `onSurfaceChanged`, `onSurfaceDestroyed`) to SurfaceView lifecycle in `LinuxAppProxyActivity.java`.
   - Remove reflection code (`Class.forName("com.android.server.linux.LinuxWindowBridgeService")`).
3. **Implement Functional Permission Activity**:
   - Update `LinuxPermissionActivity.java` to parse `app_id` and `op`, show an `AlertDialog`, and update `LinuxPortalService.setAppOp(...)`.
4. **Implement Missing AIDL Interfaces**:
   - Update `LinuxPortalService.java` to implement `ILinuxPortalService.Stub` (`getCameraStatus()`, `getAudioStatus()`, `getLocation()`).
