package android.system.linux;

public class LinuxManager {
    public static final int STATE_STOPPED = 0;
    public static final int STATE_RUNNING = 1;

    public int getState() {
        return STATE_STOPPED;
    }

    public void startVm() {}
    public void stopVm() {}
}
