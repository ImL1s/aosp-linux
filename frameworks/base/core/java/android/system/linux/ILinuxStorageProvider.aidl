package android.system.linux;

/**
 * Interface for guest storage SAF provider management.
 * {@hide}
 */
interface ILinuxStorageProvider {
    boolean isStorageMounted();
    boolean isCeKeyAvailable();
}
