package app.buddy.assistant;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Sharingan's motion: frame sequences rendered from the user's own face art
 * (assets/face/&lt;state&gt;/fNN.webp + durations.txt), played on the bubble's face
 * ImageView. Replaces the old programmatic scaleY blink — the loop itself carries
 * the slow, expressive blink (close 0.7s, shut linger, open 0.9s, 4s loop).
 * Only the current state's frames are held in memory. Any failure falls back to
 * the matching static face drawable: the face must never be blank.
 */
final class FaceAnim {
    private final ImageView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String state;
    private List<Bitmap> frames;
    private int[] durations;
    private int idx;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || frames == null || frames.isEmpty()) return;
            view.setImageBitmap(frames.get(idx));
            int d = durations != null && idx < durations.length ? durations[idx] : 42;
            idx = (idx + 1) % frames.size();
            handler.postDelayed(this, Math.max(16, d));
        }
    };

    FaceAnim(ImageView view) {
        this.view = view;
    }

    /** Loop a state's sequence ("idle", "thinking", "fired"). No-op if already playing it. */
    void play(String newState) {
        if (newState.equals(state) && running) return;
        stop();
        state = newState;
        if (!load(newState)) {
            view.setImageResource(fallbackFor(newState));
            return;
        }
        idx = 0;
        running = true;
        handler.post(tick);
    }

    /** Show a still drawable instead (e.g. the brace face during a drag). */
    void showStatic(int resId) {
        stop();
        state = null;
        view.setImageResource(resId);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
        if (frames != null) {
            for (Bitmap b : frames) if (b != null && !b.isRecycled()) b.recycle();
            frames = null;
        }
        durations = null;
    }

    private boolean load(String s) {
        try {
            Context c = view.getContext();
            AssetManager am = c.getAssets();
            String dir = "face/" + s;
            String[] names = am.list(dir);
            if (names == null) return false;
            Arrays.sort(names);
            List<Bitmap> out = new ArrayList<>();
            List<Integer> durs = new ArrayList<>();
            String durText = null;
            for (String n : names) {
                if (n.equals("durations.txt")) {
                    durText = readAll(am.open(dir + "/" + n));
                } else if (n.endsWith(".webp")) {
                    try (InputStream in = am.open(dir + "/" + n)) {
                        Bitmap b = BitmapFactory.decodeStream(in);
                        if (b != null) out.add(b);
                    }
                }
            }
            if (out.isEmpty()) return false;
            if (durText != null) {
                for (String part : durText.trim().split(",")) {
                    try {
                        durs.add(Integer.parseInt(part.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            while (durs.size() < out.size()) durs.add(42);
            frames = out;
            durations = new int[out.size()];
            for (int i = 0; i < out.size(); i++) durations[i] = durs.get(i);
            return true;
        } catch (Exception e) {
            frames = null;
            durations = null;
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static int fallbackFor(String s) {
        switch (s) {
            case "thinking": return R.drawable.bubble_face_thinking;
            case "fired": return R.drawable.bubble_face_fired;
            default: return R.drawable.bubble_face_idle;
        }
    }
}
