package android.content;

import java.util.HashMap;
import java.util.Map;

public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
    public static final int FLAG_ACTIVITY_REORDER_TO_FRONT = 0x00020000;
    public static final String ACTION_MAIN = "android.intent.action.MAIN";

    private String mAction;
    private String mPackageName;
    private String mClassName;
    private int mFlags;
    private final Map<String, Object> mExtras = new HashMap<>();

    public Intent() {}

    public Intent(String action) {
        mAction = action;
    }

    public Intent(Context packageContext, Class<?> cls) {
        mPackageName = packageContext != null ? packageContext.getPackageName() : "";
        mClassName = cls != null ? cls.getName() : "";
    }

    public Intent setClassName(String packageName, String className) {
        mPackageName = packageName;
        mClassName = className;
        return this;
    }

    public Intent addFlags(int flags) {
        mFlags |= flags;
        return this;
    }

    public Intent putExtra(String name, String value) {
        mExtras.put(name, value);
        return this;
    }

    public Intent putExtra(String name, int value) {
        mExtras.put(name, value);
        return this;
    }

    public Intent putExtra(String name, boolean value) {
        mExtras.put(name, value);
        return this;
    }

    public String getStringExtra(String name) {
        Object val = mExtras.get(name);
        return val instanceof String ? (String) val : null;
    }

    public int getIntExtra(String name, int defaultValue) {
        Object val = mExtras.get(name);
        return val instanceof Integer ? (Integer) val : defaultValue;
    }

    public boolean getBooleanExtra(String name, boolean defaultValue) {
        Object val = mExtras.get(name);
        return val instanceof Boolean ? (Boolean) val : defaultValue;
    }

    public String getAction() { return mAction; }
    public int getFlags() { return mFlags; }
}

