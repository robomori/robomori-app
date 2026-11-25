package android.view.accessibility;
public class AccessibilityNodeInfo {
    public static final class AccessibilityAction { public int getId() { throw new RuntimeException("Stub"); } }
    public int getChildCount() { throw new RuntimeException("Stub"); }
    public AccessibilityNodeInfo getChild(int index) { throw new RuntimeException("Stub"); }
    public CharSequence getText() { throw new RuntimeException("Stub"); }
    public CharSequence getContentDescription() { throw new RuntimeException("Stub"); }
    public CharSequence getHintText() { throw new RuntimeException("Stub"); }
    public CharSequence getClassName() { throw new RuntimeException("Stub"); }
    public CharSequence getPackageName() { throw new RuntimeException("Stub"); }
    public String getViewIdResourceName() { throw new RuntimeException("Stub"); }
    public void getBoundsInScreen(android.graphics.Rect out) { throw new RuntimeException("Stub"); }
    public boolean isVisibleToUser() { throw new RuntimeException("Stub"); }
    public boolean isClickable() { throw new RuntimeException("Stub"); }
    public boolean isLongClickable() { throw new RuntimeException("Stub"); }
    public boolean isCheckable() { throw new RuntimeException("Stub"); }
    public boolean isChecked() { throw new RuntimeException("Stub"); }
    public boolean isEditable() { throw new RuntimeException("Stub"); }
    public boolean isFocused() { throw new RuntimeException("Stub"); }
    public boolean isEnabled() { throw new RuntimeException("Stub"); }
    public boolean isSelected() { throw new RuntimeException("Stub"); }
    public boolean isScrollable() { throw new RuntimeException("Stub"); }
    public boolean isPassword() { throw new RuntimeException("Stub"); }
    public boolean isShowingHintText() { throw new RuntimeException("Stub"); }
    public java.util.List<AccessibilityAction> getActionList() { throw new RuntimeException("Stub"); }
    public boolean performAction(int action, android.os.Bundle arguments) { throw new RuntimeException("Stub"); }
    public AccessibilityNodeInfo findFocus(int focus) { throw new RuntimeException("Stub"); }
    public boolean isHeading() { throw new RuntimeException("Stub"); }
    public int getWindowId() { throw new RuntimeException("Stub"); }
    public void recycle() { throw new RuntimeException("Stub"); }
}
