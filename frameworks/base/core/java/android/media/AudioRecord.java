package android.media;

public class AudioRecord {
    public static final int STATE_INITIALIZED = 1;
    public static final int RECORDSTATE_RECORDING = 3;
    public static final int RECORDSTATE_STOPPED = 1;

    public AudioRecord(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {}

    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        return 1024;
    }

    public int getState() {
        return STATE_INITIALIZED;
    }

    public int getRecordingState() {
        return RECORDSTATE_RECORDING;
    }

    public void startRecording() {}
    public void stop() {}
    public void release() {}

    public int read(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return sizeInBytes;
    }
}
