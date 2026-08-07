// frameworks/base/core/java/android/system/linux/ILinuxManager.aidl
package android.system.linux;

import android.system.linux.LinuxAppInfo;
import android.system.linux.ILinuxStatusCallback;
import android.system.linux.ILinuxTerminalCallback;
import android.os.ParcelFileDescriptor;

/**
 * System-private interface to the LinuxManagerService.
 * {@hide}
 */
interface ILinuxManager {
    int getState();
    boolean startVm();
    boolean stopVm(boolean force);
    boolean suspendVm();
    boolean resumeVm();
    
    // Terminal Session Management
    String createTerminalSession(int width, int height, ILinuxTerminalCallback callback);
    void resizeTerminalSession(String sessionId, int width, int height);
    void closeTerminalSession(String sessionId);
    void writeTerminalInput(String sessionId, in byte[] data);
    
    // Linux Application Integration
    List<LinuxAppInfo> getInstalledApps();
    boolean launchLinuxApp(String appId, int displayId);
    
    // System Image & Storage Administration
    boolean installGuestImage(in ParcelFileDescriptor imageFd, long size);
    
    // Callback Registration
    void registerStatusCallback(ILinuxStatusCallback callback);
    void unregisterStatusCallback(ILinuxStatusCallback callback);
}
