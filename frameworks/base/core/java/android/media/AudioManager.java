package android.media;

public class AudioManager {
    public static final int AUDIOFOCUS_GAIN = 1;
    public static final int AUDIOFOCUS_LOSS = -1;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT = -2;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3;
    public static final int AUDIOFOCUS_REQUEST_GRANTED = 1;
    public static final int AUDIOFOCUS_REQUEST_FAILED = 0;

    public interface OnAudioFocusChangeListener {
        void onAudioFocusChange(int focusChange);
    }

    public int requestAudioFocus(AudioFocusRequest focusRequest) {
        return AUDIOFOCUS_REQUEST_GRANTED;
    }

    public void abandonAudioFocusRequest(AudioFocusRequest focusRequest) {}
}
