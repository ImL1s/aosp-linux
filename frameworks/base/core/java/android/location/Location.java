package android.location;

public class Location {
    private double mLatitude;
    private double mLongitude;
    private float mAccuracy;

    public Location(String provider) {}

    public double getLatitude() { return mLatitude; }
    public void setLatitude(double latitude) { mLatitude = latitude; }

    public double getLongitude() { return mLongitude; }
    public void setLongitude(double longitude) { mLongitude = longitude; }

    public float getAccuracy() { return mAccuracy; }
    public void setAccuracy(float accuracy) { mAccuracy = accuracy; }
}
