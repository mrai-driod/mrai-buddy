# Contributing to Mrai Buddy

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/mrai-driod/mrai-buddy/issues/new/choose) with the bug form. The most useful details are your phone and Android version, the agent and model (Mrai AI, Claude Code, Codex, OpenCode, Custom AI or Local AI), what you asked, and what happened instead. A screenshot of the chat (open its "N tool calls" line) usually shows where it went wrong.

Found a way for an agent to act without your approval, or to approve for you? Please don't post it publicly: use [private vulnerability reporting](https://github.com/mrai-driod/mrai-buddy/security/advisories/new).

## Before you write code

- **Small fixes** (a crash, a typo, a wrong label): just open a pull request.
- **Bigger changes** (a new tool, a new screen, a new agent): open an issue first, so we agree on the approach before you spend time on it.
- **Anything that touches safety** (`Guard.java`, `Reviewer.java`, the approval cards, the rules in `linux/system-prompt.md`): always start with an issue. These decide what an agent can do without asking, so they get extra care.

## Building

See [For developers](README.md#for-developers) in the README: `./gradlew assembleRelease`, or `./build.sh` right on the phone in Termux. The project layout is there too.

There are two flavors: `github` (the full app) and `play` (without self-updates and developer mode, as Google Play requires). Build the `github` one unless your change is about Play.

## Testing

Mrai Buddy drives real apps, so please test on a real phone before opening a pull request:

1. Install your build over the current one (same signing key) or on a clean phone.
2. Run at least one task with an agent, for example "Open the calculator and compute 12 × 7".
3. If you changed approvals or tools, also try something that must ask ("Send me a WhatsApp saying test") and check the card appears, and that Deny really stops it.

Say in the pull request what you tested and on which phone.

## Code style

- Plain Java, no new libraries unless there's no other way. The APK is small on purpose.
- Match the surrounding code: naming, comment density, the `Ui` helpers for views.
- Text the user sees is short and plain: one idea per sentence, no jargon.
- `linux/buddy-run` is bash with jq programs inside single quotes: no apostrophes in comments there, or the quoting breaks.
- One topic per pull request. Don't bump the version; releases do that.

## License

Mrai Buddy is [Apache-2.0](LICENSE). By opening a pull request you agree that your contribution is released under the same license.
