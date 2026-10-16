package app.buddy.assistant;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * Buddy's replies read aloud in ChatGPT's voice (the user's ChatGPT plan), through a Codex voice call like
 * {@link CodexDictation}'s. The call only receives: Buddy sends silence, so the microphone isn't used. Each text
 * is given to the voice to read word for word. If anything goes wrong, the phone's own voice reads it instead.
 */
final class CodexSpeech {
    static final String[][] VOICES = {{"juniper", "Juniper"}, {"maple", "Maple"}, {"ember", "Ember"}, {"cove", "Cove"}};
    private static final String PROMPT = "You are the voice of Mrai Buddy, a phone assistant. You only read text aloud. "
            + "Each time you're given text, read it exactly as written, word for word, in the language it is in, "
            + "naturally and warmly. Never add, answer, comment, ask, or hand anything off. Say nothing else.";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<String> queue = new ArrayDeque<>();
    private static WebView web;
    private static Context app;
    private static String threadId;
    private static Process threadServer;
    private static volatile String sessionThread; // the thread with a live call
    private static boolean connected, reading, playing;
    private static String current;
    private static long lastSound, sentAt;
    /** Fallback for what couldn't be spoken (the phone's voice). */
    interface Fallback {
        void say(String text);
    }

    private static Fallback fallback;

    private CodexSpeech() {
    }

    static boolean active() {
        return web != null;
    }

    static boolean owns(String thread) {
        return thread != null && thread.equals(sessionThread);
    }

    static void speak(Context c, String text, Fallback f) {
        app = c.getApplicationContext();
        fallback = f;
        queue.add(text);
        if (web == null) open();
        else if (connected) next();
    }

    static void stop() {
        queue.clear();
        close();
    }

    // ------------------------------------------------------------------ the call

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void open() {
        connected = reading = playing = false;
        current = null;
        opened = System.currentTimeMillis();
        web = new WebView(app);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        final WebView mine = web;
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void offer(String sdp) {
                new Thread(() -> startCall(mine, sdp), "codex-speech-call").start();
            }

            @JavascriptInterface
            public void connected() {
                main.post(() -> {
                    if (web != mine) return;
                    connected = true;
                    next();
                });
            }

            /** Whether the voice is audible right now (the page measures what it plays). */
            @JavascriptInterface
            public void sound(boolean on) {
                main.post(() -> {
                    if (web != mine) return;
                    playing = on;
                    lastSound = System.currentTimeMillis();
                });
            }

            @JavascriptInterface
            public void failed(String why) {
                main.post(() -> {
                    if (web == mine) fail();
                });
            }
        }, "Buddy");
        web.loadDataWithBaseURL("https://buddy.local/", PAGE, "text/html", "utf-8", null);
        main.postDelayed(watchdog, 1000);
    }

    private static void startCall(WebView page, String sdp) {
        try {
            CodexDictation.ensureServer(app);
            if (threadId == null || threadServer != CodexDictation.server()) {
                threadId = CodexDictation.newThread();
                threadServer = CodexDictation.server();
            }
            sessionThread = threadId;
            JSONObject r = CodexDictation.request("thread/realtime/start", new JSONObject()
                    .put("threadId", threadId)
                    .put("transport", new JSONObject().put("type", "webrtc").put("sdp", sdp))
                    .put("version", "v3")
                    .put("outputModality", "audio")
                    .put("voice", Prefs.speechVoice(app))
                    .put("delegationAckFiller", false)
                    .put("prompt", PROMPT)
                    .put("includeStartupContext", false), 30_000);
            if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
        } catch (Exception e) {
            main.post(() -> {
                if (web == page) fail();
            });
        }
    }

    /** Give the voice the next text (one at a time: it reads, then the next). */
    private static void next() {
        if (reading || queue.isEmpty() || !connected) return;
        current = queue.poll();
        reading = true;
        sentAt = System.currentTimeMillis();
        final String t = sessionThread, text = current;
        new Thread(() -> {
            try {
                JSONObject r = CodexDictation.request("thread/realtime/appendText", new JSONObject()
                        .put("threadId", t).put("role", "user")
                        .put("text", "Read this aloud exactly, word for word, nothing else:\n\n" + text), 15_000);
                if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
            } catch (Exception e) {
                main.post(CodexSpeech::fail);
            }
        }, "codex-speech-text").start();
    }

    static void notification(String method, JSONObject p) {
        switch (method) {
            case "thread/realtime/sdp": {
                final String sdp = p.optString("sdp");
                main.post(() -> {
                    if (web != null) web.evaluateJavascript("answer(" + JSONObject.quote(sdp) + ")", null);
                });
                break;
            }
            case "thread/realtime/transcript/done":
                if ("assistant".equals(p.optString("role"))) main.post(() -> {
                    current = null; // read; the audio may still be playing (the watchdog waits for quiet)
                });
                break;
            case "thread/realtime/error":
            case "thread/realtime/closed":
                main.post(CodexSpeech::fail);
                break;
        }
    }

    /** Moves on once a text has been read and the voice went quiet; hangs up when there's nothing left. */
    private static final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (web == null) return;
            long now = System.currentTimeMillis();
            if (reading && current == null && !playing && now - lastSound > 700) {
                reading = false;
                if (queue.isEmpty()) {
                    close();
                    return;
                }
                next();
            } else if (reading && current != null && now - sentAt > 60_000) {
                fail(); // never read
                return;
            } else if (!connected && now - opened > 20_000) {
                fail(); // never connected
                return;
            }
            main.postDelayed(this, 200);
        }
    };

    private static long opened;

    /** Hand whatever wasn't spoken to the phone's voice. */
    private static void fail() {
        if (web == null) return;
        StringBuilder rest = new StringBuilder();
        if (current != null) rest.append(current);
        for (String t : queue) rest.append(rest.length() > 0 ? "\n" : "").append(t);
        queue.clear();
        close();
        if (rest.length() > 0 && fallback != null) fallback.say(rest.toString());
    }

    private static void close() {
        main.removeCallbacks(watchdog);
        opened = 0;
        sentAt = 0;
        reading = false;
        current = null;
        if (web != null) {
            web.destroy();
            web = null;
        }
        final String t = sessionThread;
        sessionThread = null;
        connected = false;
        if (t != null) new Thread(() -> {
            try {
                CodexDictation.request("thread/realtime/stop", new JSONObject().put("threadId", t), 5000);
            } catch (Exception ignored) {
            }
        }, "codex-speech-stop").start();
    }

    /** Sends silence (the call needs audio flowing for the voice to speak) and plays what comes back. */
    private static final String PAGE = "<!doctype html><html><body><audio id=out autoplay></audio><script>\n"
            + "let pc;\n"
            + "async function start() {\n"
            + "  try {\n"
            + "    const ctx = new AudioContext(); ctx.resume();\n"
            + "    const osc = ctx.createConstantSource(); osc.offset.value = 0;\n"
            + "    const dest = ctx.createMediaStreamDestination(); osc.connect(dest); osc.start();\n"
            + "    pc = new RTCPeerConnection();\n"
            + "    pc.addTrack(dest.stream.getAudioTracks()[0], dest.stream);\n"
            + "    pc.createDataChannel('oai-events');\n"
            + "    pc.ontrack = e => {\n"
            + "      const s = e.streams[0] || new MediaStream([e.track]);\n"
            + "      const out = document.getElementById('out'); out.srcObject = s; out.play().catch(() => {});\n"
            + "      const an = ctx.createAnalyser(); an.fftSize = 1024; ctx.createMediaStreamSource(s).connect(an);\n"
            + "      const buf = new Float32Array(an.fftSize); let was = false;\n"
            + "      setInterval(() => { an.getFloatTimeDomainData(buf); let m = 0; for (const v of buf) m = Math.max(m, Math.abs(v));\n"
            + "        const on = m > 0.01; if (on || was) Buddy.sound(on); was = on; }, 100);\n"
            + "    };\n"
            + "    pc.onconnectionstatechange = () => {\n"
            + "      if (pc.connectionState === 'connected') Buddy.connected();\n"
            + "      else if (pc.connectionState === 'failed') Buddy.failed('the connection failed');\n"
            + "    };\n"
            + "    await pc.setLocalDescription(await pc.createOffer());\n"
            + "    await new Promise(r => { if (pc.iceGatheringState === 'complete') r(); else { pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') r(); }; setTimeout(r, 1500); } });\n"
            + "    Buddy.offer(pc.localDescription.sdp);\n"
            + "  } catch (e) { Buddy.failed(String(e && e.message || e)); }\n"
            + "}\n"
            + "function answer(sdp) { pc.setRemoteDescription({type: 'answer', sdp}).catch(e => Buddy.failed(String(e.message || e))); }\n"
            + "start();\n"
            + "</script></body></html>";
}
