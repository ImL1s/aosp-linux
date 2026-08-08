package android.view;

import android.hardware.HardwareBuffer;

public class SurfaceControl {
    public SurfaceControl() {}
    public void release() {}
    public boolean isValid() { return true; }

    public static class Transaction {
        public Transaction setBuffer(SurfaceControl sc, HardwareBuffer buffer) { return this; }
        public Transaction setVisibility(SurfaceControl sc, boolean visible) { return this; }
        public Transaction reparent(SurfaceControl sc, SurfaceControl newParent) { return this; }
        public void apply() {}
    }
}
