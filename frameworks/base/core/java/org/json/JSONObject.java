package org.json;

import java.util.HashMap;
import java.util.Map;

public class JSONObject {
    private final Map<String, String> mMap = new HashMap<>();

    public JSONObject() {}

    public JSONObject(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) return;
        String content = json.trim();
        if (content.startsWith("{") && content.endsWith("}")) {
            content = content.substring(1, content.length() - 1);
        }
        String[] pairs = content.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = cleanToken(kv[0]);
                String val = cleanToken(kv[1]);
                mMap.put(key, val);
            }
        }
    }

    public String optString(String key, String fallback) {
        String val = mMap.get(key);
        return val != null ? val : fallback;
    }

    public String optString(String key) {
        return optString(key, "");
    }

    public String getString(String key) throws Exception {
        String val = mMap.get(key);
        if (val == null) throw new Exception("No value for " + key);
        return val;
    }

    public boolean has(String key) {
        return mMap.containsKey(key);
    }

    public JSONObject put(String key, Object value) {
        mMap.put(key, value != null ? value.toString() : null);
        return this;
    }

    private String cleanToken(String token) {
        String t = token.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }
}
