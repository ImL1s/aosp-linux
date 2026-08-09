package android.system.linux;

/** {@hide} */
oneway interface ILinuxStatusCallback {
    void onStatusChanged(int oldState, int newState);
    void onError(int errorCode, String errorMessage);
}
