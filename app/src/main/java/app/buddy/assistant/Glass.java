package app.buddy.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;

/**
 * Glass theme derived from the user's chat wallpaper. NO hardcoded tint: the image itself
 * decides. The bitmap is downscaled and averaged; every glass surface is that colour at a
 * fixed alpha, and the ink colour flips light/dark by the colour's luminance so text stays
 * readable on any photo. Mirrors the reference look: wallpaper full-bleed, UI floating on it
 * as tinted glass.
 */
final class Glass {
    final String path;     // the wallpaper file path
    private final long stamp; // file mtime when sampled (cache invalidation)
    final int base;        // the wallpaper's average colour (opaque)
    final int fill;        // glass surface: base at ~62% alpha
    final int fillStrong;  // own-message glass: base at ~80% alpha
    final int stroke;      // hairline edge: ink at ~30% alpha
    final int ink;         // text on glass
    final int inkMuted;    // secondary text on glass
    final boolean dark;    // base colour is dark → light ink

    private static Glass cached;

    private Glass(String path, long stamp, int base) {
        this.path = path;
        this.stamp = stamp;
        this.base = base;
        this.dark = luminance(base) < 0.5;
        this.fill = withAlpha(base, 0x9E);
        this.fillStrong = withAlpha(base, 0xCC);
        this.ink = dark ? 0xFFFFFFFF : 0xFF16130F;
        this.inkMuted = withAlpha(ink, 0xB4);
        this.stroke = withAlpha(ink, 0x4D);
    }

    /** The glass palette for the current wallpaper, or null when none is set. Cached per file. */
    static synchronized Glass current(Context c) {
        String p = Prefs.wallpaper(c);
        if (p == null) {
            cached = null;
            return null;
        }
        File f = new File(p);
        long stamp = f.lastModified();
        if (cached != null && p.equals(cached.path) && stamp == cached.stamp) return cached;
        int base = sampleAverage(f);
        cached = new Glass(p, stamp, base);
        return cached;
    }

    /**
     * Average colour of the image. Downscale to a 24×24 grid and average in linear-ish space
     * (per-channel mean of squares, then sqrt) so bright areas don't wash the result out.
     */
    private static int sampleAverage(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return 0xFF3A3632;
            int s = 1;
            while (o.outWidth / s > 480 || o.outHeight / s > 480) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            Bitmap big = BitmapFactory.decodeFile(f.getPath(), o);
            if (big == null) return 0xFF3A3632;
            Bitmap tiny = Bitmap.createScaledBitmap(big, 24, 24, true);
            if (tiny != big) big.recycle();
            double r = 0, g = 0, b = 0;
            int n = 0;
            for (int y = 0; y < tiny.getHeight(); y++) {
                for (int x = 0; x < tiny.getWidth(); x++) {
                    int px = tiny.getPixel(x, y);
                    r += Math.pow(Color.red(px), 2);
                    g += Math.pow(Color.green(px), 2);
                    b += Math.pow(Color.blue(px), 2);
                    n++;
                }
            }
            tiny.recycle();
            if (n == 0) return 0xFF3A3632;
            return Color.rgb((int) Math.sqrt(r / n), (int) Math.sqrt(g / n), (int) Math.sqrt(b / n));
        } catch (Exception e) {
            return 0xFF3A3632;
        }
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static double luminance(int color) {
        return (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
    }
}
