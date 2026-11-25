package android.view;
public final class MotionEvent extends InputEvent {
    public static final class PointerProperties {
        public int id, toolType;
        public PointerProperties() { }
    }
    public static final class PointerCoords {
        public float x, y, pressure, size;
        public PointerCoords() { }
    }
    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) { throw new RuntimeException("Stub"); }
    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount, PointerProperties[] pointerProperties,
                                     PointerCoords[] pointerCoords, int metaState, int buttonState, float xPrecision, float yPrecision,
                                     int deviceId, int edgeFlags, int source, int flags) { throw new RuntimeException("Stub"); }
    public final void setSource(int source) { throw new RuntimeException("Stub"); }
    public final void recycle() { throw new RuntimeException("Stub"); }
}
