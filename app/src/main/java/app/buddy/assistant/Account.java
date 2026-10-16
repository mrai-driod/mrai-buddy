package app.buddy.assistant;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One agent (Claude Code, Codex or OpenCode) inside Buddy's built-in Linux: install it, sign in, check the account.
 * Claude Code's sign-in shows a one-time code in the browser; Buddy reads it off the screen (accessibility),
 * the clipboard, or a paste box. Codex's sign-in returns to a local address inside Linux by itself.
 * OpenCode needs no sign-in (its free models work right away); providers are connected in the terminal.
 */
final class Account {
    enum Step { CHECKING, NEED_INSTALL, INSTALLING, SIGNED_OUT, WAITING_BROWSER, FINISHING, SIGNED_IN, ERROR }

    interface Listener {
        void onAccountChanged();
    }

    static final Account CLAUDE = new Account("claude");
    static final Account CODEX = new Account("codex");
    static final Account OPENCODE = new Account("opencode");
    static final Account[] ALL = {CLAUDE, CODEX, OPENCODE};

    static Account of(String agent) {
        return "codex".equals(agent) ? CODEX : "opencode".equals(agent) ? OPENCODE : CLAUDE;
    }

    /** The agent's display name: "Claude Code", "Codex" or "OpenCode". */
    static String nameOf(String agent) {
        return of(agent).name();
    }

    /** Its command-line name, shown in mono labels: "claude-code", "codex" or "opencode". */
    static String cliOf(String agent) {
        return "codex".equals(agent) ? "codex" : "opencode".equals(agent) ? "opencode" : "claude-code";
    }

    final String agent;
    volatile Step step = Step.CHECKING;
    volatile String detail = "", email = "", plan = "";
    /** The sign-in page to open, and (Claude) the OAuth state that the confirmation code ends with. */
    volatile String url, state;
    /** Signing in from another device: show the link (and Codex's one-time code) instead of opening the browser here. */
    volatile boolean remote;
    /** Codex's device code ("ABCD-12345") in remote mode. */
    volatile String userCode;
    /** OpenCode 2 (npm @opencode/cli) rather than 1.x: its commands need --standalone, or it leaves a background service. */
    volatile boolean opencode2;
    /** The installed agent's version ("2.1.40"), or "" if unknown. */
    volatile String version = "";
    /** Updating rather than installing (same INSTALLING step), and what the last update did, for the screen. */
    volatile boolean updating;
    volatile String updateNote = "";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Context app;
    private static final List<Listener> listeners = new ArrayList<>();
    private Process login;
    private OutputStream loginIn;

    private Account(String agent) {
        this.agent = agent;
    }

    boolean claude() {
        return "claude".equals(agent);
    }

    boolean opencode() {
        return "opencode".equals(agent);
    }

    String name() {
        return claude() ? "Claude Code" : opencode() ? "OpenCode" : "Codex";
    }

    /** One line on what the agent runs on, for pickers. */
    String tagline() {
        return claude() ? "Uses your Claude plan" : opencode() ? "Free models, or your own providers" : "Uses your ChatGPT plan";
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void set(Step s, String d) {
        step = s;
        detail = d == null ? "" : d;
        Context a = app;
        // remembered, so screens can show progress right away before the next check finishes
        if (a != null && (s == Step.NEED_INSTALL || s == Step.SIGNED_OUT || s == Step.SIGNED_IN)) {
            Prefs.setAgentState(a, agent, s.name());
        }
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onAccountChanged();
        });
    }

    boolean busy() {
        return step == Step.INSTALLING || step == Step.WAITING_BROWSER || step == Step.FINISHING;
    }

    boolean ready() {
        return step == Step.SIGNED_IN;
    }

    /** Works out where things stand: installed? signed in? */
    void refresh(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (!Linux.installed(app)) {
                    set(Step.NEED_INSTALL, "");
                    return;
                }
                if (claude()) {
                    String out = Linux.exec(app, "[ -x /root/.local/bin/claude ] || { echo NOTINSTALLED; exit 0; }; "
                            + "claude auth status 2>/dev/null; echo; echo VER=$(claude --version 2>/dev/null)", 60);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                        return;
                    }
                    version = ver(out);
                    JSONObject j = new JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1));
                    if (j.optBoolean("loggedIn")) {
                        email = j.optString("email", j.optString("emailAddress", ""));
                        plan = j.optString("subscriptionType", "");
                        set(Step.SIGNED_IN, "");
                    } else {
                        set(Step.SIGNED_OUT, "");
                    }
                } else if (opencode()) {
                    // no sign-in needed: installed means ready. Connected providers (if any) are shown as the plan.
                    // 1.x keeps connected providers in auth.json; 2 in its database (it imports auth.json on upgrade)
                    String out = Linux.exec(app, "command -v opencode >/dev/null || { echo NOTINSTALLED; exit 0; }; "
                            + "if readlink -f \"$(command -v opencode)\" | grep -q /@opencode/cli/; then echo OC_V2; "
                            + "opencode auth list --standalone --format json 2>/dev/null | jq -r '[.[].name] | join(\", \")'; "
                            + "else jq -r 'keys | join(\", \")' /root/.local/share/opencode/auth.json 2>/dev/null; fi; echo OC_OK; "
                            + "echo VER=$(opencode --version 2>/dev/null)", 60);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                    } else {
                        opencode2 = out.contains("OC_V2");
                        version = ver(out);
                        String providers = out.substring(0, out.indexOf("OC_OK")).replace("OC_V2", "").trim();
                        email = "";
                        plan = providers.isEmpty() ? "free models" : providers;
                        set(Step.SIGNED_IN, "");
                        // its model list (free ones plus connected providers), for the model picker
                        String list = Linux.exec(app, "timeout 60 opencode models" + (opencode2 ? " --standalone" : "")
                                + " 2>/dev/null", 70);
                        org.json.JSONArray a = new org.json.JSONArray();
                        for (String line : list.split("\n")) {
                            String m = line.trim();
                            if (m.matches("[A-Za-z0-9._-]+/\\S+")) a.put(new org.json.JSONArray().put(m).put(m.substring(m.indexOf('/') + 1)));
                        }
                        if (a.length() > 0) {
                            Prefs.setOpencodeModels(app, a.toString());
                            Models.setOpencode(a.toString());
                        }
                    }
                } else {
                    String out = Linux.exec(app, "command -v codex >/dev/null || { echo NOTINSTALLED; exit 0; }; "
                            + "codex login status 2>&1; echo VER=$(codex --version 2>/dev/null)", 60);
                    version = ver(out);
                    String low = out.toLowerCase(Locale.ROOT);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                    } else if (low.contains("logged in") && !low.contains("not logged in")) {
                        email = "";
                        plan = out.contains("ChatGPT") ? "ChatGPT" : "";
                        set(Step.SIGNED_IN, "");
                    } else {
                        set(Step.SIGNED_OUT, "");
                    }
                }
            } catch (Exception e) {
                set(Step.ERROR, "Couldn't check " + name() + ": " + e.getMessage());
            }
        }).start();
    }

    /** Installs Buddy's Linux (4 MB) if needed, then the agent: Claude Code (~230 MB) or Codex. */
    void install(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        set(Step.INSTALLING, "Setting up Mrai Buddy's Linux…");
        new Thread(() -> {
            try {
                synchronized (Account.class) { // both agents use apk; don't run it twice at once
                    Linux.install(app);
                    Helper.sync(app);
                    set(Step.INSTALLING, "Installing tools…");
                    String o = Linux.exec(app, "apk update -q && apk add -q bash curl jq libgcc libstdc++ ripgrep git "
                            + "ca-certificates procps coreutils findutils" + (claude() ? "" : " nodejs npm")
                            + " && echo TOOLS_OK", 900);
                    if (!o.contains("TOOLS_OK")) throw new IOException(tail(o));
                }
                if (claude()) {
                    set(Step.INSTALLING, "Downloading Claude Code (about 230 MB)…");
                    String o = Linux.exec(app, "curl -fsSL https://claude.ai/install.sh -o /tmp/install.sh && bash /tmp/install.sh"
                            + " && /root/.local/bin/claude --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                } else if (opencode()) {
                    set(Step.INSTALLING, "Downloading OpenCode…");
                    // OpenCode 2 (npm @opencode/cli), always a fresh install (OC_REMOVE)
                    String o = Linux.exec(app, OC_REMOVE + " npm install -g @opencode/cli >/tmp/npm.log 2>&1;"
                            + " tail -5 /tmp/npm.log;" + OC_GLIBC + " opencode --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                } else {
                    set(Step.INSTALLING, "Downloading Codex…");
                    String o = Linux.exec(app, "npm install -g @openai/codex >/tmp/npm.log 2>&1; tail -5 /tmp/npm.log;"
                            + " codex --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                }
                set(Step.SIGNED_OUT, "");
                refresh(app);
            } catch (Exception e) {
                set(Step.ERROR, "Install failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Removes OpenCode (2 or 1.x) before npm installs it again. Its install hard-links the binary, and Buddy's
     * Linux fakes hard links with symlinks (proot --link2symlink): when npm replaces an installed copy and
     * deletes the old links, the new binary goes with them ("opencode: not found"). A fresh install is fine.
     * Twice: the first rm can leave the binary behind as a hidden .l2s file (~200 MB).
     */
    private static final String OC_REMOVE = " for i in 1 2; do rm -rf /usr/local/lib/node_modules/@opencode/cli"
            + " /usr/local/lib/node_modules/@opencode/.cli-* /usr/local/lib/node_modules/opencode-ai"
            + " /usr/local/bin/opencode /usr/local/bin/opencode2 2>/dev/null; done;";

    /** npm also fetches OpenCode's glibc build; Buddy's Alpine only runs the musl one, so drop the other (~200 MB). */
    private static final String OC_GLIBC = " rm -rf /usr/local/lib/node_modules/@opencode/cli/node_modules/@opencode/cli-linux-arm64;";

    private static String ver(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("VER=\\D*(\\d+\\.\\d+\\.\\d+)").matcher(out);
        return m.find() ? m.group(1) : "";
    }

    /** OpenCode 1.x is installed (npm opencode-ai): its update moves it to OpenCode 2. */
    boolean oldOpencode() {
        return opencode() && !opencode2 && !version.isEmpty();
    }

    /** Updates the agent to its latest version; {@link #updateNote} says what happened. */
    void update(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        final String before = version;
        final boolean toV2 = oldOpencode();
        updating = true;
        updateNote = "";
        set(Step.INSTALLING, toV2 ? "Downloading OpenCode 2…" : "Checking for a new " + name() + "…");
        new Thread(() -> {
            String o;
            try {
                if (claude()) {
                    o = Linux.exec(app, "/root/.local/bin/claude update 2>&1 | tail -3; echo VER=$(/root/.local/bin/claude --version)", 900);
                } else if (opencode()) {
                    // Reinstalls only when npm has a newer version (1.x counts: OpenCode 2 is a new npm package that
                    // keeps 1.x's connected providers). The install removes the old copy first (OC_REMOVE), so it
                    // downloads into npm's cache before that: a failed download leaves the old one working.
                    o = Linux.exec(app, ": >/tmp/npm.log; v=$(npm view @opencode/cli version 2>>/tmp/npm.log);"
                            + " [ -n \"$v\" ] || { tail -3 /tmp/npm.log; exit 0; };"
                            + " if [ \"$(opencode --version 2>/dev/null | grep -oE '[0-9]+[.][0-9]+[.][0-9]+' | head -1)\" != \"$v\" ]; then"
                            + " npm cache add @opencode/cli@$v @opencode/cli-linux-arm64-musl@$v >>/tmp/npm.log 2>&1"
                            + " || { tail -3 /tmp/npm.log; exit 0; };" + OC_REMOVE
                            + " npm install -g @opencode/cli@$v >>/tmp/npm.log 2>&1; fi;"
                            + OC_GLIBC + " tail -3 /tmp/npm.log; echo VER=$(opencode --version)", 1800);
                } else {
                    o = Linux.exec(app, "npm install -g @openai/codex@latest >/tmp/npm.log 2>&1; tail -3 /tmp/npm.log;"
                            + " echo VER=$(codex --version)", 1800);
                }
                String after = ver(o);
                if (after.isEmpty()) throw new IOException(tail(o));
                updateNote = after.equals(before) ? "You have the latest version (" + after + ")."
                        : "Updated to " + after + (before.isEmpty() ? "." : " (was " + before + ").");
                if (toV2 && !after.startsWith("2") && !after.matches("[3-9].*")) updateNote = "Couldn't update to OpenCode 2; "
                        + "you still have " + after + ". Try again on Wi-Fi.";
            } catch (Exception e) {
                updateNote = "Update failed: " + e.getMessage();
            }
            updating = false;
            set(Step.CHECKING, "");
            refresh(app);
        }).start();
    }

    /** Starts the agent's login; the sign-in URL appears in {@link #url} when it's ready. */
    void startLogin(Context c) {
        startLogin(c, false);
    }

    /** remote = sign in on another device: Codex uses its device code; Claude shows its link to open elsewhere. */
    void startLogin(Context c, boolean onOtherDevice) {
        app = c.getApplicationContext();
        if (busy() || opencode()) return; // OpenCode: providers are connected in the terminal (opencode auth login)
        final Context app = c.getApplicationContext();
        url = null;
        state = null;
        userCode = null;
        remote = onOtherDevice;
        set(Step.WAITING_BROWSER, "Starting sign-in…");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                Process p = (claude()
                        ? Linux.builder(app, "/root/.local/bin/claude", "auth", "login", "--claudeai")
                        : onOtherDevice ? Linux.builder(app, "codex", "login", "--device-auth")
                        : Linux.builder(app, "codex", "login")).start();
                synchronized (this) {
                    login = p;
                    loginIn = p.getOutputStream();
                }
                Pattern u = Pattern.compile(onOtherDevice && !claude()
                        ? "https://\\S*/codex/device\\S*" : "https://\\S*/oauth/authorize\\?\\S+");
                Pattern code = Pattern.compile("one-time code[^\\n]*\\n\\s*([A-Z0-9][A-Z0-9-]{4,15})");
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        String plain = stripAnsi(out.toString());
                        if (url == null) {
                            Matcher m = u.matcher(plain);
                            if (m.find()) {
                                url = m.group();
                                state = Uri.parse(url).getQueryParameter("state");
                                // Codex's device flow also needs its code before the screen is useful
                                if (!(onOtherDevice && !claude())) set(Step.WAITING_BROWSER, "");
                            }
                        }
                        if (onOtherDevice && !claude() && userCode == null) {
                            Matcher m = code.matcher(plain);
                            if (!m.find()) {
                                m = Pattern.compile("\\b([A-Z0-9]{4,5}-[A-Z0-9]{4,6})\\b").matcher(plain);
                                if (!m.find()) m = null;
                            }
                            if (m != null) {
                                userCode = m.group(1);
                                if (url != null) set(Step.WAITING_BROWSER, "");
                            }
                        }
                    }
                }
                if (p.waitFor() == 0) {
                    // signed in: bring Buddy back from the browser
                    main.post(() -> {
                        try {
                            app.startActivity(new android.content.Intent(app, SignInActivity.class).putExtra("agent", agent)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        } catch (Exception ignored) {
                        }
                    });
                }
            } catch (Exception e) {
                out.append("\n").append(e.getMessage());
            } finally {
                synchronized (this) {
                    login = null;
                    loginIn = null;
                }
            }
            url = null;
            step = Step.CHECKING;
            refresh(app);
            final String why = tail(stripAnsi(out.toString()));
            main.postDelayed(() -> {
                if (step == Step.SIGNED_OUT) set(Step.ERROR, "Sign-in didn't finish. " + why);
            }, 4000);
        }).start();
    }

    /** Hands Claude's one-time code to Claude Code. Returns false if no sign-in is waiting. */
    synchronized boolean submit(String code) {
        if (loginIn == null || code == null || code.trim().isEmpty()) return false;
        try {
            loginIn.write((code.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            loginIn.flush();
            set(Step.FINISHING, "Finishing sign-in…");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    synchronized void cancelLogin() {
        if (login != null) login.destroy();
    }

    void logout(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (!opencode()) Linux.exec(app, claude() ? "claude auth logout" : "codex logout", 60);
            } catch (Exception ignored) {
            }
            refresh(app);
        }).start();
    }

    /** Claude's confirmation code if this text contains it ("&lt;code&gt;#&lt;state&gt;"), else null. */
    String findCode(CharSequence text) {
        String s = state;
        if (!claude() || text == null || s == null || s.isEmpty()) return null;
        Matcher m = Pattern.compile("[A-Za-z0-9_\\-]{16,}#" + Pattern.quote(s)).matcher(text);
        return m.find() ? m.group() : null;
    }

    boolean waitingForCode() {
        return claude() && step == Step.WAITING_BROWSER && url != null;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "");
    }

    private static String tail(String s) {
        s = s.trim();
        return s.length() > 300 ? "…" + s.substring(s.length() - 300) : s;
    }
}
