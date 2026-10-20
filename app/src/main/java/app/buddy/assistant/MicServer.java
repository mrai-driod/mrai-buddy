package app.buddy.assistant;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The phone's microphone for programs in Buddy's Linux (Claude Code's /voice, Codex's dictation): raw 16-bit
 * mono PCM over a local socket. A stand-in `arecord` (linux/arecord) connects with a token. It only runs while
 * the Terminal is on screen, so nothing in Linux can listen in the background.
 */
final class MicServer {
    // Port 18766 (not the original's 8766) so both apps can stay installed side by side
    // without hijacking each other's mic socket.
    static final int PORT = 18766;

    interface Host {
        /** The microphone permission is missing: ask for it on screen. */
        void needMicPermission();

        void onListening(boolean on);
    }

    /** Loudness of what's being recorded (0..1), for a waveform. Called on a background thread. */
    interface Levels {
        void onLevel(float level);
    }

    static volatile Levels levels;

    // Dictation capture: recording starts the moment the user taps the mic, before the program's recorder
    // connects, so the first words aren't lost. A recorder that connects meanwhile gets everything from the tap.
    private static final java.util.ArrayList<byte[]> chunks = new java.util.ArrayList<>();
    private static volatile boolean captureOn, recording;
    /** A recorder has received everything captured, after the capture stopped. */
    static volatile boolean drained;
    private static ServerSocket server;
    private static Host host;
    private static String token;
    private static volatile int active;

    private MicServer() {
    }

    /** Start accepting (Terminal visible). Writes a fresh token where the stand-in recorder reads it. */
    static synchronized void start(Context c, Host h) {
        host = h;
        if (server != null) return;
        try {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            token = sb.toString();
            File f = new File(Linux.root(c), "root/.buddy/mic-token");
            f.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write((token + "\n").getBytes(StandardCharsets.UTF_8));
            }
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            server = null;
            return;
        }
        final ServerSocket s = server;
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket sock = s.accept();
                    new Thread(() -> serve(app, sock), "mic-client").start();
                } catch (IOException e) {
                    break;
                }
            }
        }, "mic-server");
        t.setDaemon(true);
        t.start();
    }

    /** Stop accepting and end any recording (Terminal left the screen). */
    static synchronized void stop() {
        host = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    /** Start recording now (the user tapped the mic). False if the microphone isn't available. */
    static boolean startCapture(Context c) {
        endCapture();
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        int rate = 16000, ch = AudioFormat.CHANNEL_IN_MONO;
        int min = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord r;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, ch, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            r.startRecording();
        } catch (Exception e) {
            return false;
        }
        synchronized (chunks) {
            chunks.clear();
            pullChunk = pullOff = 0;
        }
        drained = false;
        captureOn = true;
        recording = true;
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1280]; // 40 ms
            while (recording) {
                int n = r.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n == 0) continue;
                byte[] chunk = java.util.Arrays.copyOf(buf, n);
                synchronized (chunks) {
                    chunks.add(chunk);
                    chunks.notifyAll();
                }
                Levels lv = levels;
                if (lv != null) lv.onLevel(loudness(chunk, n));
            }
            recording = false;
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            r.release();
            synchronized (chunks) {
                chunks.notifyAll();
            }
        }, "mic-capture");
        t.start();
        return true;
    }

    private static int pullChunk, pullOff;

    /** For a reader in Buddy itself (ChatGPT voice): the next captured audio, at most max bytes, from the start. */
    static byte[] pull(int max) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(max);
        synchronized (chunks) {
            while (out.size() < max && pullChunk < chunks.size()) {
                byte[] c = chunks.get(pullChunk);
                int n = Math.min(c.length - pullOff, max - out.size());
                out.write(c, pullOff, n);
                pullOff += n;
                if (pullOff == c.length) {
                    pullChunk++;
                    pullOff = 0;
                }
            }
        }
        return out.toByteArray();
    }

    static boolean recording() {
        return recording;
    }

    /** The user finished talking: stop the microphone; recorders still get what was captured. */
    static void stopCapture() {
        recording = false;
    }

    /** Dictation over: forget the captured audio. */
    static void endCapture() {
        recording = false;
        captureOn = false;
        synchronized (chunks) {
            chunks.clear();
            pullChunk = pullOff = 0;
            chunks.notifyAll();
        }
    }

    /** Captured audio from the start, then live; silence once it's all sent (the recorder decides when to stop). */
    private static void streamCapture(OutputStream out) throws IOException, InterruptedException {
        int i = 0;
        byte[] silence = new byte[1280];
        while (server != null && captureOn) {
            byte[] next = null;
            boolean live;
            synchronized (chunks) {
                if (i < chunks.size()) next = chunks.get(i++);
                live = recording;
                if (next == null && live) {
                    chunks.wait(200);
                    continue;
                }
            }
            if (next != null) {
                out.write(next);
            } else {
                drained = true;
                out.write(silence);
                Thread.sleep(40);
            }
        }
    }

    private static void serve(Context c, Socket sock) {
        AudioRecord rec = null;
        try (Socket s = sock) {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine(); // "<token> <rate> <channels>"
            String[] p = first == null ? new String[0] : first.trim().split("\\s+");
            if (p.length < 1 || !p[0].equals(token)) return;
            int rate = p.length > 1 ? Integer.parseInt(p[1]) : 16000;
            int channels = p.length > 2 && "2".equals(p[2]) ? 2 : 1;
            if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Host h = host;
                if (h != null) h.needMicPermission();
                return;
            }
            if (captureOn && rate == 16000 && channels == 1) {
                streamCapture(s.getOutputStream());
                return;
            }
            int chMask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            int min = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, chMask,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate)); // ~0.5 s of buffer
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            rec.startRecording();
            listening(+1);
            OutputStream out = s.getOutputStream();
            byte[] buf = new byte[Math.max(min, 3200)];
            while (server != null) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n > 0) {
                    out.write(buf, 0, n); // throws once the program stops reading (recording done)
                    Levels lv = levels;
                    if (lv != null) lv.onLevel(loudness(buf, n));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
                listening(-1);
            }
        }
    }

    /** RMS of 16-bit little-endian samples, on a -50..-10 dBFS scale mapped to 0..1. */
    static float loudness(byte[] b, int n) {
        double sum = 0;
        int count = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (double) v * v;
        }
        if (count == 0 || sum == 0) return 0;
        double db = 20 * Math.log10(Math.sqrt(sum / count) / 32768.0);
        return (float) Math.max(0, Math.min(1, (db + 50) / 40));
    }

    private static void listening(int d) {
        boolean was = active > 0;
        active += d;
        boolean now = active > 0;
        Host h = host;
        if (h != null && was != now) h.onListening(now);
    }
}
