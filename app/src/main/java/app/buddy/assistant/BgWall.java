package app.buddy.assistant;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Per-screen backgrounds + glass, shared by every screen (v0.0.7). A screen's root is
 * wrapped in a frame whose bottom layer is the screen's background — the chat's
 * wallpaper by default, or the screen's own image/video (Settings → Appearance →
 * Backgrounds). glassify() then walks a built view tree and swaps the app's solid
 * card surfaces and text colours for the wallpaper-derived glass palette, so every
 * page, tab and panel floats on the image like the chat does. No background set →
 * both are no-ops and the screen renders exactly as before.
 */
final class BgWall {

    /** Wrap a screen's root with its background layer. Returns the view to set as content. */
    static View host(Activity a, String screen, View root) {
        String p = Prefs.bgPath(a, screen);
        if (p == null) return root;
        FrameLayout frame = new FrameLayout(a);
        frame.addView(backgroundView(a, p), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.TRANSPARENT);
        frame.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return frame;
    }

    static boolean hasBg(Context c, String screen) {
        return Prefs.bgPath(c, screen) != null;
    }

    /** The background layer itself: a centre-cropped image, or a muted looping video. */
    static View backgroundView(Context c, String path) {
        if (Prefs.bgIsVideo(path)) return new VideoLayer(c, path);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bmp = decode(path);
        if (bmp != null) iv.setImageBitmap(bmp);
        return iv;
    }

    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(path, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tint a built screen's solid surfaces and texts to the screen's glass palette. */
    static void glassify(Activity a, String screen) {
        String p = Prefs.bgPath(a, screen);
        if (p == null) return;
        Glass g = Glass.forPath(p);
        if (g == null) return;
        View content = a.findViewById(android.R.id.content);
        if (content != null) tint(content, g);
    }

    private static void tint(View v, Glass g) {
        if (v.getBackground() instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) v.getBackground();
            try {
                android.content.res.ColorStateList cs = gd.getColor();
                int cur = cs == null ? 0 : cs.getDefaultColor();
                if (cur == Ui.CARD) gd.setColor(g.fill);
                else if (cur == Ui.CARD2) gd.setColor(Glass.withAlpha(g.base, 0xB8));
                else if (cur == Ui.USER) gd.setColor(g.fillStrong);
                else if (cur == Ui.BG) gd.setColor(Glass.withAlpha(g.base, 0x70));
            } catch (Exception ignored) {
            }
        }
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int cur = t.getCurrentTextColor();
            if (cur == Ui.TEXT) t.setTextColor(g.ink);
            else if (cur == Ui.MUTED) t.setTextColor(g.inkMuted);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) tint(vg.getChildAt(i), g);
        }
    }

    /** A muted, looping, centre-cropped video background. */
    static final class VideoLayer extends TextureView implements TextureView.SurfaceTextureListener {
        private final String path;
        private MediaPlayer mp;
        private int videoW, videoH;

        VideoLayer(Context c, String path) {
            super(c);
            this.path = path;
            setOpaque(false);
            setSurfaceTextureListener(this);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            try {
                mp = new MediaPlayer();
                mp.setDataSource(path);
                mp.setSurface(new Surface(st));
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.setOnPreparedListener(m -> {
                    videoW = m.getVideoWidth();
                    videoH = m.getVideoHeight();
                    applyCrop();
                    m.start();
                });
                mp.prepareAsync();
            } catch (Exception e) {
                releasePlayer();
            }
        }

        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            applyCrop();
        }

        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            releasePlayer();
            return true;
        }

        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }

        private void applyCrop() {
            if (videoW <= 0 || videoH <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
            float scale = Math.max((float) getWidth() / videoW, (float) getHeight() / videoH);
            Matrix m = new Matrix();
            m.setScale((videoW * scale) / getWidth(), (videoH * scale) / getHeight(),
                    getWidth() / 2f, getHeight() / 2f);
            setTransform(m);
        }

        private void releasePlayer() {
            if (mp != null) {
                try {
                    mp.release();
                } catch (Exception ignored) {
                }
                mp = null;
            }
        }
    }
}
