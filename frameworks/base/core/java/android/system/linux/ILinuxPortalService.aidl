package android.system.linux;

/**
 * Interface for XDG Portals (Camera, Audio, Location, File Sharing).
 * {@hide}
 */
interface ILinuxPortalService {
    String getCameraStatus();
    String getAudioStatus();
    String getLocation();
}
