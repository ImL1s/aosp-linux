package android.system.linux;

/** {@hide} */
interface ILinuxBridge {
    int startGuestVm(in byte[] authPayload);
    int stopGuestVm(boolean force);
    int getGuestVmState();
}
