package android.graphics;
public final class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize;
        public int outWidth;
        public int outHeight;
    }
    public static Bitmap decodeFile(String pathName, Options opts) { throw new RuntimeException("Stub"); }
}
