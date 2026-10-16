#!/usr/bin/env python3
"""
Mrai AI agent mode — drives the phone through the MCP phone tools, step by step.

The smart path for the Mrai AI backend: when the user's message looks like a
phone task ("open WhatsApp and message Ada"), this runs an agentic loop on
rung 1 (Pollinations — the only free rung with tool support, verified live
2026-10-16 with tools:true): think → call tools → execute against the local
MCP server → observe → repeat.

Protocol: stdin {"system_prompt": str, "messages": [...], "mcp_token": str}
→ stdout NDJSON: {"progress": str} lines while working, then
{"reply": str, "history": [...]} or {"error": str}.

TRIGGER (documented choice): automatic phone-task detection (is_phone_task),
not a per-chat mode. Why: zero UI friction — it behaves like the demo video
(user commands, phone acts). The heuristic is conservative on purpose so plain
chat stays on the fast path, and the operator prompt tells the model to answer
directly without tools when the message turns out to be a question ABOUT the
phone rather than a command TO it (self-exit backstop).

DEGRADATION: anything fails (no tools, rung 1 down, MCP unreachable) → falls
back to the plain 3-rung chat chain. Never breaks.

PLAIN MODE: no moralizing anywhere in this file. The user's own system prompt
(Settings → Assistant persona) replaces the default persona entirely; agent
mode appends only the functional operator block (what the tools do), never
personality. The safety watcher can't judge intent from surface patterns, so
approvals are the user's own Settings choice (default off) — this loop just
calls the tools; the app's Guard enforces whatever the user configured.
"""
import importlib.util
import json
import os
import re
import sys
import urllib.request

MCP_URL = "http://127.0.0.1:8765/mcp"
MCP_TIMEOUT = 20          # per tool call — tight, keeps it snappy
RUNG_TIMEOUT = 30
MAX_STEPS = 15
HISTORY_KEEP = 30
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android)", "Content-Type": "application/json"}
MRAI_CHAIN = os.environ.get("MRAI_CHAIN", "/root/bin/mrai-ai.py")

DEFAULT_PERSONA = ("You are Mrai Buddy, a helpful AI assistant living in the user's "
                   "phone. Be concise, warm and direct.")

# Functional operator instructions appended in agent mode (not personality).
OPERATOR_BLOCK = (
    "\n\nYou are operating the user's phone right now. See the screen with "
    "read_screen (prefer it over screenshot) and act with tap, type_text, "
    "scroll, open_app and the other phone tools. Work step by step: look, act, "
    "check the result. Be efficient — don't re-read the screen when you already "
    "know what's there. Call confirm before anything outward-facing or "
    "irreversible; use ask when the user must choose between options. "
    "If this message is a question ABOUT the phone rather than a command to it, "
    "just answer it directly without using tools. When the task is done, say so "
    "briefly. If you're stuck, explain what's blocking you and stop."
)


def emit_progress(text):
    print(json.dumps({"progress": text}), flush=True)


def strip_ads(text):
    return re.sub(r"\[ADVERTISEMENT\].*?\[/ADVERTISEMENT\]", "", text or "",
                  flags=re.S | re.I).strip()


# ---------------------------------------------------------- trigger heuristic
_PHONE_VERBS = ("open", "launch", "start", "message", "text", "call", "dial",
                "tap", "click", "press", "type", "scroll", "swipe", "search",
                "play", "pause", "take a screenshot", "screenshot", "turn on",
                "turn off", "enable", "disable", "install", "uninstall", "send")
_QUESTION_STARTS = ("how", "what", "why", "when", "where", "which", "who",
                    "is", "are", "do", "does", "can", "could", "would", "should")
_NO_TASK_STARTS = ("remind me", "remember to", "don't ", "dont ", "tell me about")


def is_phone_task(text):
    """Conservative: is this a command to drive the phone? Plain chat stays fast."""
    t = (text or "").strip().lower()
    if not t or t.endswith("?"):
        return False
    words = t.split()
    if words and words[0] in _QUESTION_STARTS:
        return False
    if t.startswith(_NO_TASK_STARTS):
        return False
    padded = " " + t + " "
    return any(padded.startswith(" " + v + " ") or (" " + v + " ") in padded
               for v in _PHONE_VERBS)


# ------------------------------------------------------------------ MCP calls
def mcp_rpc(token, method, params=None):
    body = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}
    req = urllib.request.Request(
        MCP_URL, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + token,
                 "User-Agent": UA["User-Agent"]})
    with urllib.request.urlopen(req, timeout=MCP_TIMEOUT) as r:
        data = json.loads(r.read().decode("utf-8", "replace"))
    if "error" in data:
        raise RuntimeError(str(data["error"].get("message", "mcp error")))
    return data.get("result", {})


def fetch_openai_tools(token):
    """MCP tools/list → OpenAI tools format (inputSchema is already JSON Schema)."""
    result = mcp_rpc(token, "tools/list")
    out = []
    for t in result.get("tools", []):
        schema = t.get("inputSchema") or {"type": "object", "properties": {}}
        out.append({"type": "function",
                    "function": {"name": t.get("name", ""),
                                 "description": t.get("description", ""),
                                 "parameters": schema}})
    return out


def tool_result_text(result):
    parts = []
    for c in result.get("content", []):
        if isinstance(c, dict) and c.get("text") is not None:
            parts.append(str(c["text"]))
    return "\n".join(parts)[:4000]  # keep observations tight


# ------------------------------------------------------------- rung 1+tools
def post_json(url, body, timeout):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def rung1_tools(messages, tools):
    body = {"model": "openai", "stream": False, "messages": messages,
            "tools": tools, "tool_choice": "auto"}
    data = json.loads(post_json("https://text.pollinations.ai/openai", body, RUNG_TIMEOUT))
    msg = data["choices"][0]["message"]
    return {"content": msg.get("content"), "tool_calls": msg.get("tool_calls") or []}


# ------------------------------------------------------- plain-chain fallback
_chain_mod = None


def plain_ask(system_prompt, messages):
    """Degrade gracefully: the exact 3-rung keyless chain (other rungs can't do tools)."""
    global _chain_mod
    if _chain_mod is None:
        spec = importlib.util.spec_from_file_location("mrai_ai", MRAI_CHAIN)
        _chain_mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(_chain_mod)
    return _chain_mod.ask(system_prompt, messages)


# ---------------------------------------------------------------- agent loop
def summarize_args(args):
    if not isinstance(args, dict) or not args:
        return ""
    bits = []
    for k, v in list(args.items())[:3]:
        s = str(v)
        bits.append("%s=%s" % (k, s[:24] + ("…" if len(s) > 24 else "")))
    return "(" + ", ".join(bits) + ")" if bits else ""


def agent_loop(system_prompt, messages, mcp_token):
    tools = fetch_openai_tools(mcp_token)
    if not tools:
        raise RuntimeError("no tools")
    convo = [{"role": "system", "content": system_prompt + OPERATOR_BLOCK}]
    convo += [m for m in messages if isinstance(m, dict) and m.get("role")][-12:]
    last_obs = ""
    for step in range(MAX_STEPS):
        msg = rung1_tools(convo, tools)
        if not msg["tool_calls"]:
            text = strip_ads(msg.get("content") or "")
            convo.append({"role": "assistant", "content": text})
            return (text or "Done.", convo)
        convo.append({"role": "assistant", "content": msg.get("content"),
                      "tool_calls": msg["tool_calls"]})
        for tc in msg["tool_calls"]:
            fn = tc.get("function", {}) if isinstance(tc, dict) else {}
            name = fn.get("name", "")
            args = fn.get("arguments", {})
            if isinstance(args, str):
                try:
                    args = json.loads(args or "{}")
                except Exception:
                    args = {}
            if not isinstance(args, dict):
                args = {}
            emit_progress("→ %s%s" % (name, summarize_args(args)))
            try:
                result = mcp_rpc(mcp_token, "tools/call",
                                 {"name": name, "arguments": args})
                obs = tool_result_text(result)
                if result.get("isError"):
                    obs = "Error: " + obs
                    emit_progress("⚠ %s failed" % name)
            except Exception as e:
                obs = "Error calling %s: %s" % (name, type(e).__name__)
                emit_progress("⚠ %s failed" % name)
            last_obs = obs
            convo.append({"role": "tool", "tool_call_id": tc.get("id") if isinstance(tc, dict) else "",
                          "content": obs})
    return ("I took %d steps and didn't finish. Last thing I saw: %s"
            % (MAX_STEPS, last_obs[:300]), convo)


# ---------------------------------------------------------------------- main
def main():
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except Exception:
        print(json.dumps({"error": "Mrai AI couldn't read its input."}), flush=True)
        return
    # User's persona REPLACES the default entirely (never appended).
    system_prompt = (payload.get("system_prompt") or "").strip() or DEFAULT_PERSONA
    mcp_token = payload.get("mcp_token") or ""
    clean = []
    for m in payload.get("messages") or []:
        if isinstance(m, dict) and m.get("role"):
            clean.append(m if m.get("role") in ("user", "assistant", "system", "tool")
                         else {"role": "user", "content": str(m.get("content", ""))})
    last_user = next((m.get("content", "") for m in reversed(clean)
                      if m.get("role") == "user"), "")

    history = None
    try:
        if mcp_token and is_phone_task(last_user):
            emit_progress("Driving your phone…")
            reply, convo = agent_loop(system_prompt, clean, mcp_token)
            history = convo[-HISTORY_KEEP:]
        else:
            reply = plain_ask(system_prompt, clean)
            history = (clean + [{"role": "assistant", "content": reply}])[-HISTORY_KEEP:]
    except Exception:
        # Anything at all went wrong in agent mode → plain chain, never break.
        try:
            reply = plain_ask(system_prompt, clean)
            history = (clean + [{"role": "assistant", "content": reply}])[-HISTORY_KEEP:]
        except Exception as e:
            print(json.dumps({"error": "Mrai AI is unreachable right now — all free "
                                       "providers failed. Try again in a moment. (%s)" % e}),
                  flush=True)
            return
    if history is None:
        history = (clean + [{"role": "assistant", "content": reply}])[-HISTORY_KEEP:]
    print(json.dumps({"reply": strip_ads(reply), "history": history}), flush=True)


if __name__ == "__main__":
    main()
