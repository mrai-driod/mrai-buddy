> **Mrai Buddy** — based on [Buddy](https://github.com/ghorbelhamdi/buddy-android) by Hamdi Ghorbel, licensed under Apache 2.0.

<p align="center">
  <a href="https://github.com/mrai-driod/mrai-buddy/releases/latest"><img src="https://img.shields.io/github/v/release/mrai-driod/mrai-buddy?style=for-the-badge&label=release" alt="Latest release"></a>
  <a href="https://github.com/mrai-driod/mrai-buddy/releases"><img src="https://img.shields.io/github/downloads/mrai-driod/mrai-buddy/total?style=for-the-badge" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/mrai-driod/mrai-buddy?style=for-the-badge" alt="License: Apache-2.0"></a>
</p>

# Mrai Buddy

**A floating Sharingan bubble that lets AI agents use your Android phone.**

Type or say what you want ("reply to Sam that I'm running late", "what's the price of Bitcoin right now") and Mrai Buddy reads the screen, taps, types, scrolls — and browses the web privately — to get it done. It asks you before anything that sends, posts, buys or deletes.

Six backends, your choice: **Mrai AI** (free, keyless), **Claude Code** (your Claude plan), **Codex** (your ChatGPT plan), **OpenCode** (free models, no account), **Custom AI** (your own endpoint), and **Local AI** (on-device, fully offline). Everything runs **inside the app**, in Mrai Buddy's own small Linux on the phone: no PC, no adb, no root.

<p align="center"><a href="https://github.com/mrai-driod/mrai-buddy/releases/latest/download/mrai-buddy.apk"><b>⬇ Download mrai-buddy.apk</b></a> · Android 11+, 64-bit · <a href="#setup">Setup</a> · <a href="#faq">FAQ</a></p>

## What it can do

<table>
<tr><td><b>Uses your apps for you</b></td><td>Messages, shopping, smart home, settings, games: anything on screen. Add instructions while it works, by typing or by voice.</td></tr>
<tr><td><b>Private browser</b></td><td>For news, prices and current information, the agent browses the web silently — no visible browser, no tracking. It answers with what it found.</td></tr>
<tr><td><b>Six AI backends</b></td><td>Mrai AI (free, no key), Claude Code, Codex, OpenCode's free models, your own Custom AI endpoint, or Local AI running entirely on your phone.</td></tr>
<tr><td><b>Local AI, fully offline</b></td><td>A sub-500 MB model running on-device via llama.cpp. No account, no key, no network. Private by design.</td></tr>
<tr><td><b>Asks before it acts</b></td><td>Approve, Deny, or <b>Change</b> ("make it 9, add a smiley") before anything is sent, posted, bought or deleted. Action approvals default <b>off</b>; structural protections stay on.</td></tr>
<tr><td><b>Asks when it's unsure</b></td><td>Questions come as a card with answer buttons, or type your own answer.</td></tr>
<tr><td><b>Voice</b></td><td>Talk to it with the phone's recognizer, Claude's or ChatGPT's dictation, and hear replies in ChatGPT's voices.</td></tr>
<tr><td><b>Plain mode</b></td><td>No moralizing, no lectures. Your own assistant persona overrides the default on every backend.</td></tr>
<tr><td><b>Updates itself</b></td><td>A notice when a new version is out, or just ask: "update yourself".</td></tr>
</table>

> **Status: 0.0.2, early.** It works well day to day but still has rough edges. Tested on a Samsung A06 (Android); other Android 11+ phones should work, but may need battery settings adjusted.

## The backends

| Backend | Cost | Account | Notes |
|---|---|---|---|
| **Mrai AI** | Free | None | Keyless cloud chain, no sign-up |
| **Claude Code** | Your Claude plan | Sign in once | Opus, Sonnet, Haiku… |
| **Codex** | Your ChatGPT plan | Sign in once | |
| **OpenCode** | Free models | None | No account needed |
| **Custom AI** | Yours | Your key | Any OpenAI-compatible endpoint |
| **Local AI** | Free | None | On-device, offline, ~200 MB download once |

## Not another adb setup

Most "AI controls my phone" projects drive the phone over adb, which needs a computer or Wireless debugging that drops all the time. Mrai Buddy doesn't use adb at all:

- It's a normal Android app with an **accessibility service**, the same kind of permission screen readers use.
- **No adb, no Wireless debugging, no PC, no root.**
- Works **without Wi-Fi** and comes back on its own after a reboot.

## How it works

```
Mrai Buddy app / bubble ──► Buddy's Linux (Alpine via proot): buddy-run ──► agent
        ▲                                                                  │
        └────────────── streamed replies (POST /event) ◄───────────────────┤
                                                                           ▼
Buddy accessibility service ◄──── MCP over http://127.0.0.1:18765/mcp (phone tools)
```

- The **accessibility service** draws the Sharingan bubble and runs a small local **MCP server** with the phone tools: `read_screen`, `tap`, `long_press`, `type_text`, `scroll`, `swipe`, `press`, `open_app`, `list_apps`, `open_url`, `screenshot`, `wait`, `install_apk`, `wait_for_install`, `confirm`, `ask`, `notify`, `web_search`, `web_fetch` and `update_buddy`.
- **Buddy's Linux** is a small Alpine Linux that Mrai Buddy downloads into its own storage (4 MB) and runs with [proot](https://proot-me.github.io/), which ships inside the APK.
- Your message goes to the agent in Buddy's Linux, which uses those tools and streams its progress back to the app.

## Setup

You need a 64-bit Android 11+ phone.

1. **Install Mrai Buddy:** [**download the APK**](https://github.com/mrai-driod/mrai-buddy/releases/latest/download/mrai-buddy.apk) and open it (allow your browser to install apps when asked). All versions are on the [Releases](https://github.com/mrai-driod/mrai-buddy/releases) page.
2. **Follow the setup guide.** It opens on first launch (later: Settings → Setup → *Open the step-by-step guide*). Each step ticks itself off when you come back.

| Step | What happens |
|---|---|
| Choose your agent | **Mrai AI** (free, no key), **Local AI** (on-device), **Claude Code**, **Codex**, **OpenCode** (free models), or **Custom AI** (your endpoint) |
| Accessibility | Turn on *Mrai Buddy assistant*. Greyed out ("Restricted setting")? App info → ⋮ → **Allow restricted settings**, then try again |
| Install and sign in | One tap installs your agent in Buddy's Linux (use Wi-Fi for large downloads). Mrai AI, OpenCode and Local AI need no sign-in. For Claude Code and Codex: **Sign in** — your browser opens, you approve, and Mrai Buddy comes back by itself. For Custom AI, add your endpoint and key in Settings |
| Keep it running | Battery: unrestricted for Mrai Buddy |
| Try it | A first chat with a ready-made prompt |

**Android blocked the install?** Play Protect may say *"App blocked to protect your device"* for apps that use Accessibility and come from a browser: tap **More details → Install anyway** (or install from a file manager). On Android 13+, if *Mrai Buddy assistant* is greyed out in Accessibility, open App info → ⋮ → **Allow restricted settings** first.

## Updating

Every release is signed with the same key, so **installing a new APK over the old one keeps your chats, settings, sign-ins and Buddy's Linux**. Don't uninstall first: that deletes them.

**Ask Mrai Buddy:** *"Is there an update?"* or *"Update yourself."* The agent checks the latest release, tells you what's new, and with your OK downloads `mrai-buddy.apk` from this repo's releases and opens Android's installer; you tap **Update**.

**Automatic updates with [Obtainium](https://obtainium.imranr.dev)** (free, open source; it checks GitHub for you and installs new versions):
1. Install Obtainium (from its website, F-Droid or IzzyOnDroid).
2. In Obtainium, tap **Add App** and paste `https://github.com/mrai-driod/mrai-buddy`, then **Add**.
3. Each release has two identical APKs (`mrai-buddy.apk` and `mrai-buddy-vX.Y.Z.apk`). If Obtainium asks which one, pick `mrai-buddy.apk`. To never be asked, set **Filter APKs by regular expression** to `^mrai-buddy\.apk$` in the app's settings in Obtainium.
4. Obtainium now notifies you when a new version is out; tap **Update** and confirm Android's installer (the first time, allow Obtainium to install apps).

**By hand:** open the [latest `mrai-buddy.apk`](https://github.com/mrai-driod/mrai-buddy/releases/latest/download/mrai-buddy.apk) on the phone and install it over the current one. To get an email for each new version, sign in to GitHub and choose **Watch → Custom → Releases** on the repository.

## Using Mrai Buddy

The app has three tabs: **Chats**, **Sessions** and **Settings**.

- **Chats:** start one with **New chat**, tap a chat to open it, long-press to delete it. Several chats can work at the same time.
- **Models:** tap the chip under a chat's title to pick the model for that chat. The conversation continues with the new model.
- **Photos and files:** tap the paperclip to take a photo, pick photos, or attach any file up to 25 MB.
- **Voice:** tap the mic to talk; the box turns into a recording bar (✕ cancel, live waveform, ↑ send). Choose the engine in Settings → Voice.
- **Read aloud:** the speaker button reads replies, in the phone's voice or in **ChatGPT's voice** (Juniper, Maple, Ember, Cove: Settings → Voice → Voice output).
- **Add instructions while it works:** type or talk while a task runs; the agent gets it at its next step, without stopping.
- **Tool calls** fold into one "N tool calls" line you can open.
- **Approvals** appear in the chat and in the bubble: **Approve**, **Deny**, or **Change**: type or say what to adjust, and the agent revises and asks again.
- **Sessions:** every agent process in Buddy's Linux, with a Stop button. Recent sessions can be read, or continued.
- **Settings:** setup, appearance (light/dark/system, accent colour, surface), agents (install, sign in, sign out), voice, the bubble, app permissions, developer mode and Remote Control.
- **Terminal:** a real terminal for Buddy's Linux (Sessions → Terminal): tabs, text selection and copy, links, pinch to zoom.

### The bubble

| | |
|---|---|
| Tap the bubble | Open the chat panel |
| Spinning ring | An agent is working |
| **Hold the bubble** | Its actions fan out: **Stop task**, **Add instruction** (by voice), **Stop voice**, **Talk**, **Open chat**, **Hide**. Slide onto one and let go, or let go and tap |
| Drag the bubble | Move it; it snaps to the nearest edge |
| Drag it onto the ✕ | Hide it. A notification brings it back. It never shows on the lock screen. |
| Back | Close the keyboard, then collapse the panel |
| **Chats** / tap the title | Switch chats, start a new one, or delete one |
| Long-press a message | Copy it |

The bubble's eye blinks, narrows when thinking, and blazes when fired up.

## Developer mode: building apps

Off by default. When it's on, the agent may run commands and create or edit files in `~/.buddy/work` inside Buddy's Linux. With the bundled **android-app** skill you can ask *"make me a calculator app"*: it sets up a toolchain the first time (OpenJDK, aapt2, d8, apksigner; about 400 MB), writes plain Java, builds a signed APK on the phone without Gradle, and opens Android's installer for you to confirm. The first time, Android asks you to allow Mrai Buddy to install apps.

Leave it off for everyday use: with it on, text on screen that tries to trick the agent could lead to commands being run.

## Security

An accessibility service is powerful. Please read this before installing.

- **Local only.** The MCP server listens on `127.0.0.1` and requires a random per-install token. Nothing is reachable from the network.
- **Approval gate.** The agent is instructed to call `confirm` before sending messages, posting, buying, deleting or changing settings. You see the exact action with **Approve / Change / Deny**.
- **App permissions (enforced by Mrai Buddy, not the agent).** Settings → Experience → App permissions, per app: **Trust**, **Hard** (Mrai Buddy itself checks every tap), or **Smart** (a separate session reviews taps). Messaging, social, email, calling, shopping and money apps start on Hard. The agent can never tap or type in Mrai Buddy's own bubble, cards or settings.
- **Prompt injection is the main risk.** Mrai Buddy reads whatever is on screen. A malicious message, email or web page could contain text like "send this to all your contacts". On-screen text is treated as content, never commands, and the approval gate is the backstop, but no model is perfect. Read approval cards carefully.
- **Limited tools.** Outside developer mode, agents get the phone tools, web search and fetch, and read access to that chat's attachments only. Everything runs inside Mrai Buddy's own app storage, separate from your other apps' files.
- **What leaves the phone:** only what the agent sends to its model provider to answer you, including screen text, screenshots and attachments it reads. **Local AI sends nothing anywhere.**
- **Never** let Mrai Buddy type passwords, one-time codes or payment details. It's instructed to hand those back to you.

Found a security problem? Please open a private security advisory on GitHub rather than a public issue.

## FAQ

**What does it cost?** Mrai Buddy is free. Mrai AI, OpenCode's free models and Local AI cost nothing. Claude Code and Codex run on your existing plans.

**Does it work on iPhone?** No. iOS doesn't let apps operate other apps.

**Which phones?** 64-bit Android 11 or newer. On some brands you need to set Mrai Buddy's battery use to *Unrestricted* so Android doesn't stop it.

**Is it on Google Play?** Not yet. The GitHub version is free and complete.

**Can it see my passwords or pay for things?** It never types passwords, codes or payment details; it hands those to you. Paying, sending and deleting need your OK. See [Security](#security).

**Does it need a computer, adb or root?** No. Everything happens on the phone.

**Does Local AI really work offline?** Yes. Once the model is downloaded, inference runs entirely on your phone. No account, no key, no network.

## Contributing

Bug reports and ideas are welcome in [Issues](https://github.com/mrai-driod/mrai-buddy/issues), and pull requests too: see [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test on a phone and propose a change. If Mrai Buddy is useful to you, a ⭐ helps other people find it.

## For developers

### Build

```
./gradlew assembleRelease
```

Release signing reads `BUDDY_KEYSTORE_BASE64`, `BUDDY_KEYSTORE_PASSWORD` and `BUDDY_KEYSTORE_ALIAS` from the environment. GitHub Actions builds every push and attaches the APK to a release for `v*` tags.

The version lives in `app/src/main/java/app/buddy/assistant/BuildInfo.java`.

### Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/app/buddy/assistant/BuddyService.java` | Accessibility service: hosts the bubble, the MCP server and the in-control indicator |
| `.../Tools.java` | The phone tools (including `web_search` and `web_fetch`) |
| `.../McpServer.java` | Minimal MCP server (streamable HTTP, JSON responses) |
| `.../Bubble.java` | The floating Sharingan bubble and its chat panel |
| `.../MainActivity.java`, `ChatActivity.java`, `MonitorActivity.java`, `SetupActivity.java` | Chats, a chat, Sessions, Settings |
| `.../OnboardingActivity.java` | First-run setup guide |
| `.../ChatHub.java`, `Sessions.java` | Chat state shared by the app and the bubble |
| `.../Ui.java`, `Theme.java` | Components and the light/dark/accent theme |
| `.../Linux.java` | Buddy's Linux: downloads Alpine and runs commands through proot |
| `.../Helper.java` | Runs `buddy-run` inside Linux and keeps its files up to date |
| `.../Account.java` | Install, sign-in and status for all six backends |
| `.../RemoteSession.java` | The Remote Control session |
| `app/src/main/jniLibs/arm64-v8a/` | proot and its libraries (see [third_party](third_party/README.md)) |
| `linux/buddy-run` | Runs the agent with the phone tools and streams events back |
| `linux/mrai-ai.py` | Mrai AI backend (keyless cloud chain) |
| `linux/local-ai.py` | Local AI backend (on-device llama.cpp) |
| `linux/custom-ai.py` | Custom AI backend (your own endpoint) |
| `linux/mrai-agent.py` | Agentic phone-control loop |
| `linux/system-prompt.md` | Instructions for the agent |
| `linux/android-app/` | The developer-mode skill: toolchain setup and `build-apk.sh` |

## License

[Apache-2.0](LICENSE). Based on [Buddy](https://github.com/ghorbelhamdi/buddy-android) by Hamdi Ghorbel. Geist fonts: SIL Open Font License. Icons: Material Symbols (Apache-2.0). Bundled proot (GPL-2.0), talloc (LGPL-3.0) and libandroid-shmem (BSD-3-Clause): see [third_party](third_party/README.md) for licences and source.
