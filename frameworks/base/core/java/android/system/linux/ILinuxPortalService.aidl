package android.system.linux;

/** {@hide} */
interface ILinuxPortalService {
    void registerPortalClient(int clientId, in IBinder callback);
    void unregisterPortalClient(int clientId);
}
