package android.system.linux;

/** {@hide} */
interface ILinuxStorageProvider {
    boolean isStorageMounted();
    String getStorageMountPath();
}
