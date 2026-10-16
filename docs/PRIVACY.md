# Mrai Buddy privacy policy

*Last updated: 13 October 2026*

Mrai Buddy is an open-source Android app (Apache 2.0) by Mrai (github.com/mrai-driod), based on Buddy by Hamdi Ghorbel. It lets an AI agent of your choice (Mrai AI, Claude Code, Codex, OpenCode, Custom AI or Local AI) use your phone's apps for you. This policy explains what data Mrai Buddy handles. The source code is public at https://github.com/mrai-driod/mrai-buddy, so every statement here can be checked.

## Mrai Buddy has no servers

Mrai Buddy doesn't run any servers or analytics. It doesn't collect, store, sell or share your data with us or with advertisers, and it shows no ads.

## Accessibility service

Mrai Buddy uses Android's AccessibilityService API so the agent can carry out what you ask: it reads what's on the screen and taps, types and scrolls in other apps, and it shows the floating bubble. Mrai Buddy explains this and asks for your agreement in the app before you turn the service on.

- The screen content is read only while the agent works on a request you made.
- That content, which can include personal information shown on screen (such as messages, names, addresses or order details), is sent to the AI service of the agent you chose, only to carry out your request (see below).
- Before anything that sends, posts, buys or deletes, Mrai Buddy shows the action and waits for your approval.
- You can turn the service off at any time in Android's accessibility settings.

## Data sent to the AI service you choose

The agent runs inside the app and talks directly to its own provider. What you type or say, the attachments you add, and the screen content the agent reads are sent to:

- **Claude Code:** Anthropic, under your Claude account. See https://www.anthropic.com/legal/privacy
- **Codex:** OpenAI, under your ChatGPT account. See https://openai.com/policies/privacy-policy
- **OpenCode:** the provider of the model you pick (by default OpenCode's free models). See https://opencode.ai
- **Mrai AI:** Mrai's keyless cloud service.
- **Custom AI:** your own endpoint, under your own key.
- **Local AI:** nothing leaves your phone. Inference runs entirely on-device.

You sign in with these services yourself; Mrai Buddy never sees your passwords. Their own privacy policies apply to what they receive.

## Voice

If you use the microphone, your voice is turned into text by the engine you choose in Settings: your phone's speech recognizer, Claude's dictation (Anthropic) or ChatGPT's (OpenAI). Replies read aloud use the phone's voice or ChatGPT's voices (OpenAI).

## Data stored on your phone

Your chats, settings, sign-ins and the agent's files are stored only in Mrai Buddy's private storage on your phone. Uninstalling Mrai Buddy deletes them. You can delete a chat at any time in the app.

## Downloads

To set up an agent, Mrai Buddy downloads a small Linux system from Alpine Linux (dl-cdn.alpinelinux.org) and the agent from its official source (Anthropic, OpenAI or npm). These downloads don't include any of your personal data. Mrai Buddy from GitHub also asks GitHub (api.github.com) about once a day whether a newer version is out; the version from Google Play is updated by Play.

## Permissions

- **Accessibility:** described above.
- **Microphone:** only when you tap the mic.
- **Notifications:** for task status and approvals.
- **Internet:** to reach the AI service you chose and the downloads above.

## Children

Mrai Buddy is not directed at children under 13.

## Changes and contact

Changes to this policy are published in this file, with their history in the repository. Questions: open an issue at https://github.com/mrai-driod/mrai-buddy/issues.
