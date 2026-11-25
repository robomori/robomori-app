package android.app;
public final class UiAutomation {
    public interface OnAccessibilityEventListener { void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event); }
    public void setOnAccessibilityEventListener(OnAccessibilityEventListener listener) { throw new RuntimeException("Stub"); }
    public android.view.accessibility.AccessibilityNodeInfo getRootInActiveWindow() { throw new RuntimeException("Stub"); }
    public java.util.List<android.view.accessibility.AccessibilityWindowInfo> getWindows() { throw new RuntimeException("Stub"); }
    public boolean injectInputEvent(android.view.InputEvent event, boolean sync) { throw new RuntimeException("Stub"); }
    public void waitForIdle(long idleTimeoutMillis, long globalTimeoutMillis) throws java.util.concurrent.TimeoutException { throw new RuntimeException("Stub"); }
    public android.graphics.Bitmap takeScreenshot() { throw new RuntimeException("Stub"); }
    public android.accessibilityservice.AccessibilityServiceInfo getServiceInfo() { throw new RuntimeException("Stub"); }
    public void setServiceInfo(android.accessibilityservice.AccessibilityServiceInfo info) { throw new RuntimeException("Stub"); }
    public final boolean performGlobalAction(int action) { throw new RuntimeException("Stub"); }
}
