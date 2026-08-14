package android.system.linux;

import android.os.ParcelFileDescriptor;
import android.system.linux.ILinuxStatusCallback;
import android.system.linux.ILinuxTerminalCallback;
import android.system.linux.LinuxAppInfo;

/** {@hide} */
interface ILinuxManager {
    boolean startVm();
    boolean stopVm(boolean force);
    int getState();
    boolean suspendVm();
    boolean resumeVm();
    void registerStatusCallback(in ILinuxStatusCallback callback);
    void unregisterStatusCallback(in ILinuxStatusCallback callback);
    String createTerminalSession(int width, int height, in ILinuxTerminalCallback callback);
    void closeTerminalSession(String sessionId);
    void writeTerminalInput(String sessionId, in byte[] data);
    void resizeTerminalSession(String sessionId, int width, int height);
    List<LinuxAppInfo> getInstalledApps();
    boolean launchLinuxApp(String appId, int displayId);
    boolean installGuestImage(in ParcelFileDescriptor imageFd, long size);
}
