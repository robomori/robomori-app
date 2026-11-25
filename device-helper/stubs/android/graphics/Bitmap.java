package android.graphics;
public final class Bitmap {
    public enum CompressFormat { JPEG, PNG, WEBP }
    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888 }
    public static Bitmap createBitmap(int width, int height, Config config) { throw new RuntimeException("Stub"); }
    public static Bitmap createScaledBitmap(Bitmap src, int width, int height, boolean filter) { throw new RuntimeException("Stub"); }
    public boolean compress(CompressFormat format, int quality, java.io.OutputStream stream) { throw new RuntimeException("Stub"); }
    public int getWidth() { throw new RuntimeException("Stub"); }
    public int getHeight() { throw new RuntimeException("Stub"); }
    public void recycle() { throw new RuntimeException("Stub"); }
}
