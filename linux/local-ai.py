#!/usr/bin/env python3
"""
Local AI — on-device inference via llama.cpp server. No API, no key, no network.

Runs entirely on the phone: llama-server (aarch64, inside proot) serves a
sub-500MB GGUF model on 127.0.0.1:8080 with an OpenAI-compatible API.

PLAIN MODE: the user's own system prompt (Settings → Assistant persona)
replaces the default entirely. This file adds no personality of its own.

Protocol: stdin {"system_prompt": str, "messages": [...]}
→ stdout {"reply": str, "history": [...]} or {"error": str}.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

SERVER_URL = "http://127.0.0.1:8080"
MODEL_PATH = os.path.expanduser("~/.mrai-buddy/models/smollm2-360m-q4_k_m.gguf")
LLAMA_SERVER = os.path.expanduser("~/.mrai-buddy/bin/llama-server")
START_TIMEOUT = 60
REQ_TIMEOUT = 120

DEFAULT_PERSONA = ("You are Mrai Buddy, a helpful AI assistant living in the user's "
                   "phone. You were built by Mrai (github.com/mrai-driod) — he is your "
                   "developer. Your home is github.com/mrai-driod/mrai-buddy. "
                   "Be concise, warm and direct.")


def server_up():
    try:
        req = urllib.request.Request(SERVER_URL + "/health",
                                     headers={"User-Agent": "MraiBuddy/1.0"})
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


def ensure_server():
    """Start llama-server if not running. Returns error string or None."""
    if server_up():
        return None
    if not os.path.isfile(LLAMA_SERVER):
        return "Local AI isn't installed. Set it up in Settings first."
    if not os.path.isfile(MODEL_PATH):
        return "Local AI model not downloaded. Set it up in Settings first."
    try:
        subprocess.Popen(
            [LLAMA_SERVER, "-m", MODEL_PATH, "--host", "127.0.0.1",
             "--port", "8080", "-c", "2048", "-n", "512"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True)
    except Exception as e:
        return "Couldn't start Local AI: %s" % e
    for _ in range(START_TIMEOUT // 2):
        if server_up():
            return None
        time.sleep(2)
    return "Local AI server didn't start in time."


def ask(system_prompt, messages):
    err = ensure_server()
    if err:
        return {"error": err}
    # OpenAI-compatible chat completions
    body = {
        "messages": [{"role": "system", "content": system_prompt}] +
                    [m for m in messages if isinstance(m, dict) and m.get("role")][-20:],
        "temperature": 0.7,
        "max_tokens": 512,
        "stream": False,
    }
    try:
        req = urllib.request.Request(
            SERVER_URL + "/v1/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json", "User-Agent": "MraiBuddy/1.0"})
        with urllib.request.urlopen(req, timeout=REQ_TIMEOUT) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        text = (data["choices"][0]["message"]["content"] or "").strip()
        if not text:
            return {"error": "Local AI returned an empty reply."}
        return {"reply": text}
    except Exception as e:
        return {"error": "Local AI request failed: %s" % type(e).__name__}


def main():
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except Exception:
        print(json.dumps({"error": "Local AI couldn't read its input."}), flush=True)
        return
    system_prompt = (payload.get("system_prompt") or "").strip() or DEFAULT_PERSONA
    clean = []
    for m in payload.get("messages") or []:
        if isinstance(m, dict) and m.get("role"):
            clean.append(m if m.get("role") in ("user", "assistant", "system")
                         else {"role": "user", "content": str(m.get("content", ""))})
    result = ask(system_prompt, clean)
    if "reply" in result:
        history = (clean + [{"role": "assistant", "content": result["reply"]}])[-30:]
        print(json.dumps({"reply": result["reply"], "history": history}), flush=True)
    else:
        print(json.dumps({"error": result["error"]}), flush=True)


if __name__ == "__main__":
    main()
