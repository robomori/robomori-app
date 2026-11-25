package com.robomori.device;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;

/**
 * Makes a small preview of each image or video given, keeping its shape, so only a few kilobytes leave the device whatever the
 * file's size: `ThumbnailHelper <size> <path>...` prints one `ROBOMORI_THUMB` line per path, its number and a base64
 * JPEG, or nothing after the number when the file can't be read as an image or a video, then `ROBOMORI_DONE`. Runs as
 * Android's shell user.
 */
public final class ThumbnailHelper {
    public static void main(String[] args) throws Exception {
        // Android's media code reads the app it runs in, so this starts the system's own context first, as AppsHelper does.
        Looper.prepareMainLooper();
        Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        int size = Integer.parseInt(args[0]);
        for (int index = 1; index < args.length; index++) {
            String encoded = "";
            try {
                Bitmap preview = preview(args[index], size);
                if (preview != null) {
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    preview.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
                    preview.recycle();
                    encoded = Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
                }
            } catch (Throwable failure) {
                // A file that can't be read leaves its preview empty; the others still get theirs. Why goes to the error
                // output, for diagnosing on a device.
                System.err.println(args[index] + ": " + failure);
            }
            System.out.println("ROBOMORI_THUMB\t" + (index - 1) + "\t" + encoded);
        }
        System.out.println("ROBOMORI_DONE");
    }

    private static Bitmap preview(String path, int size) throws Exception {
        Bitmap full = isVideo(path) ? videoFrame(path) : decodeImage(path, size);
        if (full == null) return null;
        // Scaled to fit a square of [size], keeping its shape.
        float scale = (float) size / Math.max(full.getWidth(), full.getHeight());
        if (scale >= 1) return full;
        Bitmap fitted = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * scale)),
            Math.max(1, Math.round(full.getHeight() * scale)), true);
        if (fitted != full) full.recycle();
        return fitted;
    }

    /** A frame from the start of the video, read from a file this process opened, as the shell user can read it. */
    private static Bitmap videoFrame(String path) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (FileInputStream input = new FileInputStream(path)) {
            retriever.setDataSource(input.getFD());
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {
            retriever.release();
        }
    }

    /** Decodes only as many pixels as the preview needs, so a large photo doesn't fill the memory. */
    private static Bitmap decodeImage(String path, int size) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(path, options);
    }

    private static boolean isVideo(String path) {
        String name = path.toLowerCase();
        for (String extension : new String[] {".mp4", ".m4v", ".3gp", ".webm", ".mkv", ".mov"}) if (name.endsWith(extension)) return true;
        return false;
    }
}
