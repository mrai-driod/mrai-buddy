package app.buddy.assistant;

/** Models offered per agent. "" means the agent's own default. */
final class Models {
    static final String[][] CLAUDE = {
            {"", "Default"},
            {"claude-opus-5-5", "Opus 5.5"},
            {"claude-sonnet-5-5", "Sonnet 5.5"},
            {"claude-haiku-5-5", "Haiku 5.5"},
            {"claude-haiku-4-5-20251001", "Haiku 4.5"},
            {"claude-fable-5-1", "Fable 5.1"},
    };
    /** Used until the helper reports Codex's own model list. */
    static final String[][] CODEX = {
            {"", "Default"},
            {"gpt-6.1-sol", "GPT-6.1-Sol"},
            {"gpt-6-astra", "GPT-6-Astra"},
            {"gpt-6-luna", "GPT-6-Luna"},
    };
    /** Used until OpenCode reports its own list (`opencode models`, which includes the free ones). */
    static final String[][] OPENCODE = {
            {"", "Default"},
            {"opencode/big-pickle", "opencode/big-pickle"},
    };
    /** Mrai AI has no model picker: the chain picks a free rung by itself. */
    static final String[][] MRAI = {
            {"", "Default"},
    };
    private static String[][] codexLive, opencodeLive;

    private Models() {
    }

    static String[][] forBackend(String backend) {
        if ("mrai".equals(backend)) return MRAI;
        if ("opencode".equals(backend)) return opencodeLive != null ? opencodeLive : OPENCODE;
        if (!"codex".equals(backend)) return CLAUDE;
        return codexLive != null ? codexLive : CODEX;
    }

    /** OpenCode's models as JSON [[id, name], …] ("provider/model" for both). */
    static void setOpencode(String json) {
        String[][] m = parse(json);
        if (m != null) opencodeLive = m;
    }

    /** Codex's model list as reported by the helper: JSON [[slug, name], …]. */
    static void setCodex(String json) {
        String[][] m = parse(json);
        if (m != null) codexLive = m;
    }

    private static String[][] parse(String json) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            if (a.length() == 0) return null;
            String[][] m = new String[a.length() + 1][];
            m[0] = new String[]{"", "Default"};
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.getJSONArray(i);
                m[i + 1] = new String[]{e.getString(0), e.getString(1)};
            }
            return m;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Effort levels an agent accepts ([value, label]; "" is the agent's default), or null if Buddy can't set it. */
    static String[][] efforts(String backend) {
        if ("claude".equals(backend)) return new String[][]{{"", "Auto"}, {"low", "Low"}, {"medium", "Med"},
                {"high", "High"}, {"xhigh", "XHigh"}, {"max", "Max"}};
        if ("codex".equals(backend)) return new String[][]{{"", "Auto"}, {"low", "Low"}, {"medium", "Med"}, {"high", "High"}};
        return null; // OpenCode: effort ("variants") depends on the model
    }

    /** "agent · model · effort" for a chat's chip. */
    static String summary(Sessions.Session s) {
        return s.brainName() + " · " + shownModel(s) + (s.effort.isEmpty() ? "" : " · " + s.effort + " effort");
    }

    /** The chat's model for display: the one picked, or what "Default" really is when the agent told us. */
    static String shownModel(Sessions.Session s) {
        if (!s.model.isEmpty()) return label(s.backend, s.model);
        if (!s.actualModel.isEmpty()) return nameOf(s.backend, s.actualModel) + " (default)";
        return "default model";
    }

    /** What "Default" means for this chat, for the pickers. */
    static String defaultHint(Sessions.Session s) {
        if (!s.actualModel.isEmpty()) return "now " + nameOf(s.backend, s.actualModel);
        if ("claude".equals(s.backend)) return "Claude Code picks it from your plan; shown here after the first message";
        if ("codex".equals(s.backend)) return "Codex picks it; shown here after the next reply";
        if ("mrai".equals(s.backend)) return "Mrai AI tries free providers in order until one answers";
        return "OpenCode's default model";
    }

    /** A model id the agent reported, by its name in the model list if it's there. */
    static String nameOf(String backend, String id) {
        String l = label(backend, id);
        return l.equals(id) ? pretty(id) : l;
    }

    /** "claude-opus-5-5-20261001" → "Opus 5.5"; other ids as they are. */
    static String pretty(String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("claude-(opus|sonnet|haiku|fable)-(\\d+)-(\\d+)").matcher(id);
        if (m.find()) return Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1) + " " + m.group(2) + "." + m.group(3);
        return id;
    }

    static String label(String backend, String model) {
        if (model == null || model.isEmpty()) return "Default";
        for (String[] m : forBackend(backend)) if (m[0].equals(model)) return m[1];
        return model;
    }
}
