package android.content;

public interface SharedPreferences {
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        void apply();
    }

    String getString(String key, String defValue);
    boolean getBoolean(String key, boolean defValue);
    int getInt(String key, int defValue);
    Editor edit();
}
