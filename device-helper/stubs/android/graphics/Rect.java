package android.graphics;
/** Works like Android's, so unit tests of the helper's screen logic can run on the computer. */
public final class Rect {
    public int left, top, right, bottom;
    public Rect() { }
    public Rect(int left, int top, int right, int bottom) { this.left = left; this.top = top; this.right = right; this.bottom = bottom; }
    public final int centerX() { return (left + right) >> 1; }
    public final int centerY() { return (top + bottom) >> 1; }
    public final int width() { return right - left; }
    public final int height() { return bottom - top; }
    public boolean contains(int x, int y) { return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom; }
    public boolean intersect(Rect r) {
        if (left < r.right && r.left < right && top < r.bottom && r.top < bottom) {
            if (left < r.left) left = r.left;
            if (top < r.top) top = r.top;
            if (right > r.right) right = r.right;
            if (bottom > r.bottom) bottom = r.bottom;
            return true;
        }
        return false;
    }
}
