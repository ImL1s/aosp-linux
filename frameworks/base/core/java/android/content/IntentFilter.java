package android.content;

import java.util.ArrayList;
import java.util.List;

public class IntentFilter {
    private final List<String> mActions = new ArrayList<>();

    public IntentFilter() {}

    public IntentFilter(String action) {
        mActions.add(action);
    }

    public void addAction(String action) {
        mActions.add(action);
    }

    public List<String> getActions() { return mActions; }
}
