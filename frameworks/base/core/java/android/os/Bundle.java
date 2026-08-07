package android.os;

import java.util.HashMap;
import java.util.Map;

public class Bundle {
    private final Map<String, Object> mMap = new HashMap<>();

    public void putString(String key, String value) { mMap.put(key, value); }
    public String getString(String key) { return (String) mMap.get(key); }
    public void putInt(String key, int value) { mMap.put(key, value); }
    public int getInt(String key, int defaultValue) {
        Object val = mMap.get(key);
        return val instanceof Integer ? (Integer) val : defaultValue;
    }
}
