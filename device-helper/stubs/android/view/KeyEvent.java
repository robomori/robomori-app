package android.view;
public class KeyEvent extends InputEvent {
    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat, int metaState, int deviceId, int scancode,
                    int flags, int source) { throw new RuntimeException("Stub"); }
    public static KeyEvent changeTimeRepeat(KeyEvent event, long eventTime, int newRepeat) { throw new RuntimeException("Stub"); }
    public static int keyCodeFromString(String symbolicName) { throw new RuntimeException("Stub"); }
}
