package android.location;

import android.os.Looper;

public class LocationManager {
    public static final String GPS_PROVIDER = "gps";
    public static final String NETWORK_PROVIDER = "network";

    public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceMeters, LocationListener listener) {}
    public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceMeters, LocationListener listener, Looper looper) {}
    public void removeUpdates(LocationListener listener) {}
}
