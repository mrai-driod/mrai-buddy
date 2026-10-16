package app.buddy.assistant;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The hard limit behind "Buddy asks before anything risky", for the apps the user puts on it (Settings →
 * App permissions; trusted apps leave asking to the agent). Enforced here, in Buddy: before a tap lands on a
 * send / pay / delete / … button (by its text, icon description or id), or text is typed with "submit", the
 * user must have approved it: either through the agent's own confirm call just
 * before (one approval covers one action), or on a card Buddy shows right then. The agent can't tap Buddy's
 * own bubble or approval card, so it can't approve for the user.
 */
final class Guard {
    /** Plain mode: the user turned Action approvals off, so no approval gates apply — taps and the
     * agent's confirm calls go through without asking. The Guard's structural protections (Mrai Buddy's
     * own UI stays off-limits) still hold; only the approval behavior is gated. */
    static boolean approvalsOn(android.content.Context c) {
        return Prefs.actionApprovals(c);
    }

    /** Words on buttons that send, pay, delete or otherwise can't be taken back, and add-to-cart buttons, so every
     * agent asks the same way before shopping (EN, FR, DE, ES, IT, AR). */
    private static final Pattern RISKY = Pattern.compile("(?iu)^[^\\p{L}]*(?:(?:proceed|continue|go) to |passer (?:à la|au) |weiter zur |zur )?("
            + "send|post|publish|share now|tweet|reply all|submit|confirm|pay|pay now|buy|buy now|purchase|"
            + "order|place order|checkout|check out|payment|add to cart|add to basket|add to bag|add to trolley|subscribe|transfer|donate|delete|remove|erase|uninstall|"
            + "unfriend|block|report|unsubscribe|deactivate|factory reset|reset|sign out|log out|logout|call|"
            + "envoyer|envoi|publier|partager maintenant|répondre à tous|valider|confirmer|payer|acheter|commander|"
            + "ajouter au panier|passer la commande|passer commande|caisse|s'abonner|virement|transférer|supprimer|effacer|désinstaller|bloquer|signaler|se désabonner|"
            + "désactiver|réinitialiser|se déconnecter|déconnexion|appeler|"
            + "senden|absenden|bezahlen|kaufen|bestellen|löschen|entfernen|in den einkaufswagen|in den warenkorb|kasse|"
            + "enviar|pagar|comprar|eliminar|borrar|añadir al carrito|agregar al carrito|inviare|pagare|acquista|elimina|"
            + "aggiungi al carrello|"
            + "إرسال|ارسال|أرسل|دفع|ادفع|شراء|اشتر|حذف|احذف|تأكيد|اتصال|أضف إلى السلة|إضافة إلى السلة"
            + ")(?![\\p{L}])");
    /** Resource ids of send buttons that only have an icon (e.g. WhatsApp's "send"). */
    private static final Pattern RISKY_ID = Pattern.compile("(?i)[:/_](send|send_button|sendbutton|btn_send|delete|pay|buy|post|submit|add-to-cart|add_to_cart|addtocart)\\b");
    // long enough for a slow multi-step task between the plan's approval and its risky tap
    private static final long APPROVAL_VALID_MS = 600_000;

    private static long approvedAt, planAt;
    /** The last approved confirm's text: the plan the user OK'd, for the smart reviewer. */
    private static String plan;

    /** Apps that start on "hard limit": messaging, email, social, calls, shopping, payments, banking. */
    private static final java.util.Set<String> HARD_BY_DEFAULT = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.android.mms", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca",
            "com.facebook.katana", "com.instagram.android", "com.snapchat.android", "com.twitter.android",
            "com.zhiliaoapp.musically", "com.linkedin.android", "com.discord", "com.Slack", "com.viber.voip",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.oneplus.dialer",
            "com.android.vending", "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.paypal.android.p2pmobile",
            "com.revolut.revolut", "de.number26.android", "com.google.android.apps.walletnfcrel", "com.wise.android",
            "com.transferwise.android", "com.klarna.android", "com.coinbase.android", "com.binance.dev"));
    private static final Pattern HARD_NAME = Pattern.compile(
            "(?i)(bank|banking|pay|wallet|finance|credit|sparkasse|volksbank|boursorama|revolut|n26|crypto|trading|broker)");

    // ------------------------------------------------------------------ the user's button lists

    /** A rule: a button label, in one app (pkg) or in all apps (pkg null). */
    static final class Rule {
        final String label, pkg, app;

        Rule(String label, String pkg, String app) {
            this.label = label;
            this.pkg = pkg;
            this.app = app;
        }

        org.json.JSONObject json() throws org.json.JSONException {
            return new org.json.JSONObject().put("label", label).put("pkg", pkg == null ? "" : pkg).put("app", app == null ? "" : app);
        }

        static Rule of(org.json.JSONObject o) {
            String pkg = o.optString("pkg");
            return new Rule(o.optString("label"), pkg.isEmpty() ? null : pkg, o.optString("app"));
        }

        String describe() {
            return pkg == null ? label + " · all apps" : label + " · " + (app == null || app.isEmpty() ? pkg : app);
        }
    }

    /** "allow" (never ask) or "ask" (always ask), or "seen" (buttons the agent tapped, newest first). */
    static java.util.List<Rule> rules(android.content.Context c, String list) {
        java.util.List<Rule> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(Prefs.guardList(c, list));
            for (int i = 0; i < a.length(); i++) out.add(Rule.of(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveRules(android.content.Context c, String list, java.util.List<Rule> rules) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Rule r : rules) a.put(r.json());
        } catch (Exception ignored) {
        }
        Prefs.setGuardList(c, list, a.toString());
    }

    static void addRule(android.content.Context c, String list, Rule r) {
        java.util.List<Rule> rules = rules(c, list);
        for (java.util.Iterator<Rule> it = rules.iterator(); it.hasNext(); ) {
            Rule x = it.next();
            if (norm(x.label).equals(norm(r.label)) && java.util.Objects.equals(x.pkg, r.pkg)) it.remove();
        }
        rules.add(0, r);
        saveRules(c, list, rules);
        // a button is on one list at most
        String other = "allow".equals(list) ? "ask" : "allow";
        java.util.List<Rule> o = rules(c, other);
        boolean changed = false;
        for (java.util.Iterator<Rule> it = o.iterator(); it.hasNext(); ) {
            Rule x = it.next();
            if (norm(x.label).equals(norm(r.label)) && java.util.Objects.equals(x.pkg, r.pkg)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) saveRules(c, other, o);
    }

    /** Remember a button the agent tapped, for the lists' suggestions. */
    static void remember(android.content.Context c, String label, String pkg, String app) {
        if (label == null || pkg == null) return;
        java.util.List<Rule> seen = rules(c, "seen");
        for (java.util.Iterator<Rule> it = seen.iterator(); it.hasNext(); ) {
            Rule x = it.next();
            if (norm(x.label).equals(norm(label)) && pkg.equals(x.pkg)) it.remove();
        }
        seen.add(0, new Rule(label, pkg, app));
        while (seen.size() > 150) seen.remove(seen.size() - 1);
        saveRules(c, "seen", seen);
    }

    /** Is this button on the list, for this app or all apps? */
    static boolean listed(android.content.Context c, String list, String label, String pkg) {
        if (label == null) return false;
        String l = norm(label);
        for (Rule r : rules(c, list)) {
            if (r.pkg != null && !r.pkg.equals(pkg)) continue;
            String x = norm(r.label);
            if (!x.isEmpty() && (l.equals(x) || l.startsWith(x + " "))) return true;
        }
        return false;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Labels that say nothing about what a button does (web form buttons are often just "Submit"). */
    private static final Pattern GENERIC = Pattern.compile("(?i)submit|button|btn|input|ok|go|enter|envoyer|absenden|enviar|invia");

    /** A short name for what a tap would press. A generic one ("Submit") is replaced by the button's id
     * ("add-to-cart-button" → "add to cart") or the visible text next to it, so the card says what it does. */
    static String buttonLabel(AccessibilityNodeInfo n) {
        String l = rawButtonLabel(n);
        if (l != null && !GENERIC.matcher(l.trim()).matches()) return l;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            String id = cur.getViewIdResourceName();
            if (id != null) {
                String w = id.substring(id.lastIndexOf('/') + 1).replaceAll("[-_.]+", " ")
                        .replaceAll("(?i)\\b(submit|button|btn|input)\\b", " ").trim().replaceAll("\\s+", " ");
                if (w.matches("[\\p{L} ]{3,}") && !w.contains("autoid")) return w; // not generated ids ("a-autoid-3")
            }
            if (cur.isClickable()) break;
            cur = cur.getParent();
        }
        AccessibilityNodeInfo p = n.getParent(); // the visible text beside it (a form button and its label)
        for (int up = 0; p != null && up < 2; up++, p = p.getParent()) {
            for (int c = 0; c < Math.min(p.getChildCount(), 8); c++) {
                AccessibilityNodeInfo ch = p.getChild(c);
                String h = ch == null ? null : shortLabel(ch);
                if (h != null && !GENERIC.matcher(h).matches()) return h;
            }
        }
        return l;
    }

    private static String rawButtonLabel(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            String l = shortLabel(cur);
            if (l != null) return l;
            if (cur.isClickable()) {
                for (int c = 0; c < Math.min(cur.getChildCount(), 6); c++) {
                    AccessibilityNodeInfo ch = cur.getChild(c);
                    String h = ch == null ? null : shortLabel(ch);
                    if (h != null) return h;
                }
                String id = cur.getViewIdResourceName();
                if (id != null) return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
                break;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static String shortLabel(AccessibilityNodeInfo n) {
        for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription()}) {
            if (cs == null) continue;
            String s = cs.toString().trim().replaceAll("\\s+", " ");
            if (s.isEmpty()) continue;
            return s.length() > 40 ? s.substring(0, 40) + "…" : s;
        }
        return null;
    }

    /** Trust: the agent works on its own in this app, without asking the user. */
    static boolean autonomous(android.content.Context c, String pkg) {
        return pkg != null && "trust".equals(mode(c, pkg, null));
    }

    /** "hard": Buddy enforces approval in this app; "smart": the lists, then a reviewer AI for every tap;
     *  "trust": the agent works on its own, without asking. */
    static String mode(android.content.Context c, String pkg, CharSequence label) {
        return modeWhy(c, pkg, label)[0];
    }

    /** {mode, why}: the user's choice, or the suggestion and its reason. */
    static String[] modeWhy(android.content.Context c, String pkg, CharSequence label) {
        if (pkg == null) return new String[]{"trust", ""};
        String set = Prefs.appSafety(c, pkg);
        if (set != null) return new String[]{set, "Your choice"};
        if (HARD_BY_DEFAULT.contains(pkg)) return new String[]{"hard", "Suggested: messages, social, calls or money"};
        java.util.regex.Matcher m = HARD_NAME.matcher(pkg);
        if (m.find()) return new String[]{"hard", "Suggested: name has “" + m.group(1) + "”"};
        if (label != null && (m = HARD_NAME.matcher(label)).find()) return new String[]{"hard", "Suggested: name has “" + m.group(1) + "”"};
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL) return new String[]{"hard", "Suggested: a social app"};
            if (label == null) {
                CharSequence l = c.getPackageManager().getApplicationLabel(ai);
                if (l != null && (m = HARD_NAME.matcher(l)).find()) return new String[]{"hard", "Suggested: name has “" + m.group(1) + "”"};
            }
        } catch (Exception ignored) {
        }
        // the rest follow the first-setup choice: work on its own, or ask first
        return Prefs.autonomousDefault(c) ? new String[]{"trust", "Suggested"} : new String[]{"hard", "Your setup choice: ask first"};
    }

    private Guard() {
    }

    /** The agent's confirm was approved: that covers the next risky action. */
    static synchronized void approved(String summary) {
        approvedAt = planAt = System.currentTimeMillis();
        plan = summary;
    }

    /** What the user approved in the last 10 minutes (it stays known after the approval is used), or null. */
    static synchronized String approvedPlan() {
        return plan != null && System.currentTimeMillis() - planAt < APPROVAL_VALID_MS ? plan : null;
    }

    /** Use the pending approval, if any (one approval, one action). */
    static synchronized boolean takeApproval() {
        boolean ok = approvedAt > 0 && System.currentTimeMillis() - approvedAt < APPROVAL_VALID_MS;
        approvedAt = 0;
        return ok;
    }

    /** What a tap at (x, y) would hit: the topmost app window's deepest node there. */
    static AccessibilityNodeInfo nodeAt(List<AccessibilityWindowInfo> windows, int x, int y) {
        AccessibilityWindowInfo top = null;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            // Buddy's floating bubble and panel let taps through while a gesture runs (see
            // BuddyService.setBubblePassThrough), so what counts is the app underneath
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            w.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            if (top == null || w.getLayer() > top.getLayer()) top = w;
        }
        if (top == null) return null;
        AccessibilityNodeInfo n = top.getRoot();
        if (n == null) return null;
        for (int depth = 0; depth < 60; depth++) {
            AccessibilityNodeInfo next = null;
            for (int i = n.getChildCount() - 1; i >= 0; i--) { // later children draw on top
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch == null || !ch.isVisibleToUser()) continue;
                ch.getBoundsInScreen(r);
                if (r.contains(x, y)) {
                    next = ch;
                    break;
                }
            }
            if (next == null) break;
            n = next;
        }
        return n;
    }

    /** Is this Buddy's own UI (its screens, the bubble, its approval card)? */
    static boolean isBuddy(AccessibilityNodeInfo n) {
        return n != null && n.getPackageName() != null
                && BuildInfo.class.getPackage().getName().contentEquals(n.getPackageName());
    }

    /**
     * Must the agent keep out of this node? All of Buddy's UI, unless the user turned on "Let agents tap Buddy's
     * screens"; even then the protected parts (Ui.protect: approval cards, App permissions, the developer
     * switches) stay off-limits. The bubble and its card are overlays that taps never land on (see nodeAt).
     */
    static boolean offLimits(android.content.Context c, AccessibilityNodeInfo n) {
        if (!isBuddy(n)) return false;
        if (!Prefs.agentsTapBuddy(c)) return true;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 60; i++) {
            if (cur.getExtras().getBoolean(Ui.PROTECTED, false)) return true;
            if (cur.getWindow() != null && cur.getWindow().getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return true;
            cur = cur.getParent();
        }
        return false;
    }

    /**
     * The risky label of what would be tapped (the node, the button it belongs to, and that button's own
     * labels), or null when it looks harmless.
     */
    static String riskyLabel(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            String hit = risky(cur);
            if (hit != null) return hit;
            if (cur.isClickable()) {
                for (int c = 0; c < Math.min(cur.getChildCount(), 6); c++) { // an icon button's label child
                    AccessibilityNodeInfo ch = cur.getChild(c);
                    String h = ch == null ? null : risky(ch);
                    if (h != null) return h;
                }
                break; // the button itself: don't look at its surroundings
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static String risky(AccessibilityNodeInfo n) {
        for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription(), n.getTooltipText()}) {
            if (cs == null) continue;
            String s = cs.toString().trim();
            // a button label ("Send", "Pay now", "Delete chat"): starts with the word and is short, so a chat
            // preview like "Sarah, the audio call ended" isn't mistaken for a Call button
            if (s.isEmpty() || s.length() > 30 || s.split("\\s+").length > 4) continue;
            if (RISKY.matcher(s).find()) return s;
        }
        String id = n.getViewIdResourceName();
        if (id != null && RISKY_ID.matcher(id.toLowerCase(Locale.ROOT)).find()) {
            return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
        }
        return null;
    }
}
