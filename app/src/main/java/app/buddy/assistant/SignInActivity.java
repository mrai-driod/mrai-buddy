package app.buddy.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/**
 * Install an agent (Claude Code, Codex or OpenCode) in Buddy's built-in Linux and sign in: one button, approve in the
 * browser, done. OpenCode needs no sign-in; its providers are connected in Buddy's terminal.
 */
public class SignInActivity extends Screen implements Account.Listener {
    private LinearLayout body, bottom;
    private Account acc;
    private String openedUrl;
    private boolean pasteOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        acc = Account.of(getIntent().getStringExtra("agent"));
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setPadding(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, "Back");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, full());
        ScrollView scroll = new ScrollView(this);
        body = column(this);
        body.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        bottom = column(this);
        bottom.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 16));
        root.addView(bottom, full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        Account.addListener(this);
        if (acc.step == Account.Step.CHECKING || acc.step == Account.Step.SIGNED_OUT
                || acc.step == Account.Step.SIGNED_IN || acc.step == Account.Step.NEED_INSTALL) {
            acc.refresh(this);
        }
        render();
    }

    @Override
    protected void onPause() {
        Account.removeListener(this);
        super.onPause();
    }

    @Override
    public void onAccountChanged() {
        if (isFinishing() || isDestroyed()) return;
        render();
    }

    /** Coming back from the browser with the code copied: use it. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || !acc.waitingForCode()) return;
        try {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String code = acc.findCode(clip.getItemAt(0).coerceToText(this));
                if (code != null) acc.submit(code);
            }
        } catch (Exception ignored) {
        }
    }

    private void render() {
        body.removeAllViews();
        bottom.removeAllViews();
        switch (acc.step) {
            case CHECKING:
                header(R.drawable.ms_terminal, acc.name(), "Checking…", false);
                spinnerRow();
                break;
            case NEED_INSTALL:
                header(R.drawable.ms_terminal, "Install " + acc.name(), acc.claude()
                        ? "Runs Claude Code inside Mrai Buddy on your Claude subscription."
                        : acc.opencode() ? "Runs OpenCode inside Mrai Buddy. Its free models work right away, no account needed; "
                        + "connect your own providers (Anthropic, OpenAI, GitHub Copilot…) anytime."
                        : "Runs Codex inside Mrai Buddy on your ChatGPT plan.", false);
                note(acc.claude() ? "Downloads about 235 MB once: Mrai Buddy's Linux and Claude Code from Anthropic. Use Wi-Fi if you can."
                        : acc.opencode() ? "Downloads about 230 MB once: Mrai Buddy's Linux, Node.js and OpenCode. Use Wi-Fi if you can."
                        : "Downloads about 150 MB once: Mrai Buddy's Linux, Node.js and Codex from OpenAI. Use Wi-Fi if you can.");
                primary("Install " + acc.name(), v -> acc.install(this));
                break;
            case INSTALLING:
                header(R.drawable.ms_terminal, acc.updating ? "Updating" : "Installing", acc.detail, false);
                spinnerRow();
                note("This takes a few minutes. You can leave this screen; it keeps going.");
                break;
            case SIGNED_OUT:
                if (acc.opencode()) {
                    openCodeReady();
                    break;
                }
                header(R.drawable.ms_shield_person, acc.claude() ? "Sign in to Claude" : "Sign in to ChatGPT",
                        (acc.claude() ? "Use your Claude account (Pro or Max)." : "Use your ChatGPT account (Plus, Pro or Business).")
                                + " Your browser opens; approve there and Mrai Buddy comes back by itself.", false);
                primary(acc.claude() ? "Sign in with Claude" : "Sign in with ChatGPT", v -> acc.startLogin(this));
                secondary(acc.claude() ? "Sign in on another device" : "Sign in with a code", v -> {
                    pasteOpen = true;
                    acc.startLogin(this, true);
                });
                break;
            case WAITING_BROWSER:
                if (acc.url == null) {
                    header(R.drawable.ms_shield_person, "Sign in", "Starting sign-in…", false);
                    spinnerRow();
                    break;
                }
                if (acc.remote) {
                    remoteSignIn();
                    break;
                }
                openBrowserOnce();
                header(R.drawable.ms_shield_person, "Approve in your browser", acc.claude()
                        ? "Tap Authorize on the Claude page. Mrai Buddy picks up the code and comes back by itself."
                        : "Sign in and allow Codex. Then come back to Mrai Buddy; it finishes by itself.", false);
                primary("Open the sign-in page again", v -> {
                    openedUrl = null;
                    openBrowserOnce();
                });
                if (acc.claude()) pasteBox();
                secondary("Cancel", v -> acc.cancelLogin());
                break;
            case FINISHING:
                header(R.drawable.ms_shield_person, "Signing in", "Finishing sign-in…", false);
                spinnerRow();
                break;
            case SIGNED_IN:
                if (acc.opencode()) {
                    openCodeReady();
                    break;
                }
                header(R.drawable.ms_check, "Signed in", (acc.email.isEmpty() ? acc.name() + " is ready."
                        : acc.email) + (acc.plan.isEmpty() ? "" : " · " + acc.plan), true);
                versionNote();
                primary("Done", v -> finish());
                updateButton();
                secondary("Sign out", v -> confirmSheet(this, "Sign out of " + acc.name() + "?",
                        acc.name() + " in Mrai Buddy stops working until you sign in again.", "Sign out", true, () -> acc.logout(this)));
                break;
            case ERROR:
                header(R.drawable.ms_error, "Something went wrong", acc.detail, false);
                primary("Try again", v -> {
                    acc.step = Account.Step.CHECKING;
                    acc.refresh(this);
                    render();
                });
                break;
        }
    }

    /** OpenCode is installed: nothing to sign in to. Providers are connected with `opencode auth login` in the terminal. */
    private void openCodeReady() {
        boolean free = acc.plan.isEmpty() || "free models".equals(acc.plan);
        header(R.drawable.ms_check, "OpenCode is ready", free
                ? "Using OpenCode's free models. Pick one per chat with the model chip, or connect your own provider."
                : "Connected: " + acc.plan + ". OpenCode's free models stay available too.", true);
        note("Connecting a provider opens Mrai Buddy's terminal with opencode auth login: pick a provider and follow its steps. "
                + "To remove one later, run opencode auth logout there.");
        versionNote();
        primary("Done", v -> finish());
        updateButton();
        secondary("Connect a provider", v -> startActivity(new Intent(this, TerminalActivity.class)
                .putExtra("run", acc.opencode2 ? "opencode auth login --standalone" : "opencode auth login")));
    }

    /** The installed version, and what the last update did. */
    private void versionNote() {
        if (!acc.updateNote.isEmpty()) note(acc.updateNote);
        else if (!acc.version.isEmpty()) note(acc.name() + " " + acc.version + (acc.oldOpencode()
                ? ". OpenCode 2 is out: update to keep getting new versions. Your connected providers are kept." : "."));
    }

    private void updateButton() {
        secondary(acc.oldOpencode() ? "Update to OpenCode 2" : "Check for updates", v -> acc.update(this));
    }

    /** Sign in on another device: show the link (and Codex's code) to use anywhere; nothing opens here. */
    private void remoteSignIn() {
        final String link = acc.url;
        if (acc.claude()) {
            header(R.drawable.ms_shield_person, "Sign in on another device",
                    "Open this link on any device and approve. Then copy the code the page shows and paste it below.", false);
        } else {
            header(R.drawable.ms_shield_person, "Enter this code",
                    "On any device, open the link below, sign in to ChatGPT and enter the code. Mrai Buddy finishes by itself.", false);
            TextView code = monoText(this, acc.userCode == null ? "…" : acc.userCode, 34, TEXT);
            code.setTypeface(mono(this, 600));
            code.setLetterSpacing(0.08f);
            code.setTextIsSelectable(true);
            code.setGravity(Gravity.CENTER);
            code.setPadding(dp(this, 12), dp(this, 18), dp(this, 12), dp(this, 18));
            code.setBackground(rounded(this, CARD, 16));
            LinearLayout.LayoutParams cp = full();
            cp.topMargin = dp(this, 20);
            body.addView(code, cp);
            Button copyCode = button(this, "Copy code", false);
            copyCode.setOnClickListener(v -> copy(acc.userCode, "Code copied"));
            LinearLayout.LayoutParams ccp = wrap();
            ccp.topMargin = dp(this, 8);
            body.addView(copyCode, ccp);
            TextView exp = text(this, "The code works for 15 minutes.", 13, MUTED);
            exp.setPadding(0, dp(this, 6), 0, 0);
            body.addView(exp);
        }
        TextView l = monoText(this, link, 13, ACCENT_TEXT);
        l.setTextIsSelectable(true);
        l.setMaxLines(acc.claude() ? 3 : 2);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        l.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(this, 20);
        body.addView(l, lp);
        LinearLayout btns = row(this);
        Button copyLink = button(this, "Copy link", false);
        copyLink.setOnClickListener(v -> copy(link, "Link copied"));
        btns.addView(copyLink, wrap());
        Button share = button(this, "Share", false);
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null)));
        LinearLayout.LayoutParams sp = wrap();
        sp.leftMargin = dp(this, 8);
        btns.addView(share, sp);
        Button here = button(this, "Open here", false);
        here.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
            } catch (Exception e) {
                Toast.makeText(this, "No browser found.", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams hp = wrap();
        hp.leftMargin = dp(this, 8);
        btns.addView(here, hp);
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(this, 10);
        body.addView(btns, bp);
        if (acc.claude()) pasteBox();
        secondary("Cancel", v -> acc.cancelLogin());
    }

    private void copy(String s, String toast) {
        if (s == null) return;
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Sign-in", s));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private void openBrowserOnce() {
        String u = acc.url;
        if (u == null || u.equals(openedUrl)) return;
        openedUrl = u;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.watchForLoginCode();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser found to open the sign-in page.", Toast.LENGTH_LONG).show();
        }
    }

    private void pasteBox() {
        LinearLayout c = column(this);
        LinearLayout head = row(this);
        head.setPadding(0, dp(this, 10), 0, dp(this, 10));
        head.setBackgroundResource(ripple(this, false));
        TextView h = title(this, "Paste the code instead", 14);
        h.setTextColor(ACCENT_TEXT);
        head.addView(h, weight1());
        head.addView(icon(this, pasteOpen ? R.drawable.ms_expand_less : R.drawable.ms_expand_more, ACCENT_TEXT, 20));
        head.setOnClickListener(v -> {
            pasteOpen = !pasteOpen;
            render();
        });
        c.addView(head, full());
        if (pasteOpen) {
            c.addView(text(this, "If Mrai Buddy doesn't come back by itself: tap Copy Code on the Claude page, then paste it here.",
                    14, MUTED));
            LinearLayout r = row(this);
            r.setPadding(0, dp(this, 10), 0, 0);
            EditText et = new EditText(this);
            et.setHint("code");
            et.setHintTextColor(MUTED);
            et.setTextColor(TEXT);
            et.setTypeface(mono(this, 400));
            et.setTextSize(14);
            et.setSingleLine(true);
            et.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
            r.addView(et, weight1());
            Button go = button(this, "Use", true);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
            gp.leftMargin = dp(this, 8);
            r.addView(go, gp);
            go.setOnClickListener(v -> {
                if (!acc.submit(et.getText().toString())) {
                    Toast.makeText(this, "Start the sign-in again first.", Toast.LENGTH_SHORT).show();
                }
            });
            c.addView(r, full());
        }
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 18);
        body.addView(c, p);
    }

    // ------------------------------------------------------------ pieces

    private void header(int iconRes, String title, String sub, boolean ok) {
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(oval(ok ? Theme.withAlpha(OK, 0x2E) : ACCENT_SOFT));
        ic.addView(icon(this, iconRes, ok ? OK : ACCENT_TEXT, 28),
                new FrameLayout.LayoutParams(dp(this, 28), dp(this, 28), Gravity.CENTER));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56));
        ip.topMargin = dp(this, 8);
        body.addView(ic, ip);
        TextView t = title(this, title, 26);
        t.setPadding(0, dp(this, 18), 0, dp(this, 6));
        body.addView(t);
        body.addView(text(this, sub, 16, MUTED));
    }

    private void spinnerRow() {
        LinearLayout r = row(this);
        r.setPadding(0, dp(this, 20), 0, 0);
        r.addView(spinner(this, 22));
        body.addView(r, full());
    }

    private void note(String s) {
        TextView t = text(this, s, 14, MUTED);
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 14);
        body.addView(t, p);
    }

    private void primary(String label, View.OnClickListener l) {
        Button b = button(this, label, true);
        b.setOnClickListener(l);
        bottom.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52)));
    }

    private void secondary(String label, View.OnClickListener l) {
        Button b = button(this, label, false);
        b.setBackgroundResource(ripple(this, false));
        b.setTextColor(TEXT);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        p.topMargin = dp(this, 4);
        bottom.addView(b, p);
    }
}
