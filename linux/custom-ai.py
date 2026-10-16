#!/usr/bin/env python3
"""
Custom AI — Mrai Buddy's "bring your own AI" backend. POSTs to any
OpenAI-compatible {base_url}/chat/completions with the user's own key.

Protocol: config lives in /root/.buddy/custom-ai.json (mode 600, written by the
app, never logged): {"name": str, "base_url": str, "api_key": str, "model": str}.
Read JSON {"system_prompt": str, "messages": [{"role":..., "content":...}]}
from stdin, print JSON {"reply": str} or {"error": str} to stdout.

SECURITY: the API key is never printed, logged, or included in error text.
"""
import json
import os
import sys
import urllib.request
import urllib.error

TIMEOUT = 60
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android)", "Content-Type": "application/json"}
CONFIG = os.environ.get("CUSTOM_AI_CONFIG", "/root/.buddy/custom-ai.json")

DEFAULT_PERSONA = ("You are Mrai Buddy, a helpful AI assistant living in the user's "
                   "phone. Be concise, warm and direct.")


def scrub(text, key):
    """Never let the key appear in anything user-visible."""
    if key and text:
        text = text.replace(key, "[redacted]")
    return text


def load_config():
    try:
        with open(CONFIG, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        return None
    base_url = (cfg.get("base_url") or "").strip().rstrip("/")
    api_key = (cfg.get("api_key") or "").strip()
    model = (cfg.get("model") or "").strip()
    if not base_url or not api_key or not model:
        return None
    return {"name": (cfg.get("name") or "").strip(),
            "base_url": base_url, "api_key": api_key, "model": model}


def chat(cfg, system_prompt, messages):
    body = {
        "model": cfg["model"],
        "messages": [{"role": "system", "content": system_prompt}] + messages[-24:],
    }
    headers = dict(UA)
    headers["Authorization"] = "Bearer " + cfg["api_key"]
    req = urllib.request.Request(cfg["base_url"] + "/chat/completions",
                                 data=json.dumps(body).encode("utf-8"),
                                 headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        # 401/403/429/...: surface the provider's message, never the key
        try:
            detail = e.read().decode("utf-8", "replace")
            msg = ""
            try:
                msg = json.loads(detail).get("error", {}).get("message", "")
            except Exception:
                msg = detail[:200]
        except Exception:
            msg = ""
        msg = scrub(msg.strip(), cfg["api_key"])
        label = {401: "rejected the API key (401)",
                 403: "refused the request (403)",
                 429: "rate-limited the request (429)"}.get(e.code,
                     "returned HTTP %d" % e.code)
        err = "Custom AI %s%s." % (label, (": " + msg[:200]) if msg else "")
        if e.code in (401, 403):
            err += " Check the key in Settings."
        raise RuntimeError(err)
    except Exception as e:
        raise RuntimeError("Custom AI couldn't reach %s (%s)."
                           % (cfg["base_url"], type(e).__name__))
    try:
        text = json.loads(raw)["choices"][0]["message"]["content"]
    except Exception:
        raise RuntimeError("Custom AI got an unreadable reply from %s."
                           % cfg["base_url"])
    text = (text or "").strip()
    if not text:
        raise RuntimeError("Custom AI answered blank.")
    return text


def main():
    cfg = load_config()
    if cfg is None:
        print(json.dumps({"error": "Custom AI isn't configured yet — "
                                   "add your endpoint and key in Settings."}))
        return
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except Exception:
        print(json.dumps({"error": "Custom AI couldn't read its input."}))
        return
    system_prompt = (payload.get("system_prompt") or "").strip() or DEFAULT_PERSONA
    clean = []
    for m in payload.get("messages") or []:
        if isinstance(m, dict) and m.get("content"):
            clean.append({"role": m.get("role", "user"), "content": str(m["content"])})
    try:
        reply = chat(cfg, system_prompt, clean)
    except Exception as e:
        print(json.dumps({"error": scrub(str(e), cfg["api_key"])}))
        return
    print(json.dumps({"reply": reply}))


if __name__ == "__main__":
    main()
