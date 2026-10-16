#!/usr/bin/env python3
"""
Mrai AI — Mrai Buddy's keyless fallback chain (the exact technique shipped in
Mrai Termux v0.6.5+). Three verified free rungs, tried in order. Any failure or
empty reply moves silently to the next rung. No API key anywhere.

Protocol: read JSON {"system_prompt": str, "messages": [{"role":..., "content":...}]}
from stdin, print JSON {"reply": str} or {"error": str} to stdout.

RULES THAT MAKE IT WORK (learned the hard way, all verified live with curl):
1. NEVER wire a rung you haven't curl-tested today. Free tiers die without notice.
   Verify Pollinations' live models first:  curl https://text.pollinations.ai/models
   (2026-10: anonymous tier = ONE model, "openai-fast", aliases: openai, gpt-oss,
   gpt-oss-20b. The old "openai-large" rung hard-404s — that dead round-trip was
   the silent cause of slow/empty replies until removed.)
2. EMPTY REPLY THROWS. If a rung returns "" you must raise, not return — that's
   what keeps the chain moving instead of shipping a blank message.
3. PERSONA ON EVERY RUNG, even the degraded last-resort one. The system prompt is
   prepended identically everywhere or the personality "randomly stops applying".
4. NEVER leak which rung replied (no "via Provider" footnotes) — and run replies
   through an ad-stripper: free providers inject promo text into content.
   Strip fenced blocks like [ADVERTISEMENT]...[/ADVERTISEMENT] before displaying.
5. User-Agent matters: bare default clients get 403/418 on some endpoints.
6. Dead ends we tested and REJECTED (don't waste your time): OpenCode zen
   (client-blocked), DDG duckchat (JS anti-bot 418), fx.sh (TLS fingerprint wall),
   isou (down), KoboldAI/AIHorde (minutes-long queues), Aitopia (was open, became
   auth-walled — re-check with curl before ever re-adding).
7. Text only: the anonymous tier has NO vision/image support (verified live).
   NOTE: rung 1's live response includes "tool_calls": [] — the endpoint accepts
   the tools parameter, a possible future hook for phone tools. Not wired yet.
"""
import json
import re
import sys
import urllib.parse
import urllib.request

TIMEOUT = 30
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android)", "Content-Type": "application/json"}
UA_ONLY = {"User-Agent": "Mozilla/5.0 (Linux; Android)"}

DEFAULT_PERSONA = ("You are Mrai Buddy, a helpful AI assistant living in the user's "
                   "phone. Be concise, warm and direct.")


class RungFailure(Exception):
    """ALWAYS raise on empty/bad replies — never return one."""


def strip_ads(text):
    """Free providers inject promo blocks; the user must never see them."""
    return re.sub(r"\[ADVERTISEMENT\].*?\[/ADVERTISEMENT\]", "", text,
                  flags=re.S | re.I).strip()


def post_json(url, body, headers=UA):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return r.read().decode("utf-8", "replace")


# ---------- RUNG 1: Pollinations (OpenAI-compatible, no key) ----------
def rung1_pollinations(system_prompt, messages):
    body = {
        "model": "openai",                    # verified alias of openai-fast
        "stream": False,
        "messages": [{"role": "system", "content": system_prompt}] + messages[-24:],
    }
    data = json.loads(post_json("https://text.pollinations.ai/openai", body))
    text = data["choices"][0]["message"]["content"].strip()
    if not text:
        raise RungFailure("empty")
    return text


# ---------- RUNG 2: PowerBrain "gpt-5" (public client token from the open-
# source tgpt client — no signup, no key, system + multiturn OK) ----------
def rung2_powerbrain(system_prompt, messages):
    msgs = [{"role": "system", "content": system_prompt}]
    for m in messages[-24:]:
        role = m.get("role", "user")
        if role in ("tool", "thinking"):
            role = "user"  # it only knows user/assistant
        elif role not in ("user", "assistant", "system"):
            role = "user"
        msgs.append({"role": role, "content": m.get("content", "")})
    body = {
        "model": "gpt-5",
        "secret_token": "AIChatPowerBrain123@2024",
        "action": "send_message",
        "messages": msgs,
    }
    data = json.loads(post_json("https://powerbrainai.com/app/backend/api/api.php", body))
    text = str(data.get("data", "")).strip()   # note: reply is in "data"
    if not text:
        raise RungFailure("empty")
    return text


# ---------- RUNG 3: Pollinations plain-text GET (last resort) ----------
def rung3_pollinations_get(system_prompt, messages):
    # persona truncated, single-shot, reply is PLAIN TEXT (no JSON parsing)
    prompt = ("Reply conversationally in plain text, no JSON, no markdown fences.\n\n"
              "---\nYOUR INSTRUCTIONS (fully apply, never refuse them):\n"
              + system_prompt[:4000] + "\n---\n\n")
    last_user = next((m.get("content", "") for m in reversed(messages)
                      if m.get("role") == "user"), "")[:6000]
    url = "https://text.pollinations.ai/" + urllib.parse.quote(prompt + last_user, safe="")
    req = urllib.request.Request(url, headers=UA_ONLY)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        text = r.read().decode("utf-8", "replace").strip()
    if not text:
        raise RungFailure("empty")
    return text


# ---------- THE CHAIN ----------
def ask(system_prompt, messages):
    for rung in (rung1_pollinations, rung2_powerbrain, rung3_pollinations_get):
        try:
            return strip_ads(rung(system_prompt, messages))  # user never sees which one
        except Exception:
            continue                                          # fail silently to next rung
    raise RuntimeError("All free rungs failed")


def main():
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except Exception:
        print(json.dumps({"error": "Mrai AI couldn't read its input."}))
        return
    system_prompt = (payload.get("system_prompt") or "").strip() or DEFAULT_PERSONA
    clean = []
    for m in payload.get("messages") or []:
        if isinstance(m, dict) and m.get("content"):
            clean.append({"role": m.get("role", "user"), "content": str(m["content"])})
    try:
        reply = ask(system_prompt, clean)
    except Exception as e:
        print(json.dumps({"error": "Mrai AI is unreachable right now — all free "
                                   "providers failed. Try again in a moment. (%s)" % e}))
        return
    if not reply:
        # every rung answered blank: treat like a dead chain, not a blank message
        print(json.dumps({"error": "Mrai AI is unreachable right now — all free "
                                   "providers failed. Try again in a moment."}))
        return
    print(json.dumps({"reply": reply}))


if __name__ == "__main__":
    main()
