# Mercurio

Mercurio is an Android console for driving AI coding agents that run on your
own machines. It connects over SSH, works with [Herdr][herdr] on the host, and
puts the keys, notifications and approvals for agents such as Claude Code and
Codex on a phone.

Mercurio is a fork of [ConnectBot][connectbot], the Android SSH client by Kenny
Root and contributors. The SSH stack, the terminal emulator and host and key
management come from ConnectBot. Mercurio is maintained by
[Ormus Solutions][ormus].

## What it does

### Terminal and input

- **Compose bar.** Two rows of keys under the terminal: paging ↑ and ↓, Herdr,
  Needs you, Esc, Tab, a text field for the keyboard or the phone's voice
  input, and one ⏎ key that sends the field's text and then Enter, or Enter
  alone when the field is empty. Needs you shows how many agents wait for you
  in every Herdr session, in gold while any does; a tap jumps to the next one,
  cycling, and a long press (or a tap with none waiting) opens the Agents board.
- **Tray.** ⋯ or a swipe up on the bar opens more keys: arrows, jump to the
  latest output, Mode, clear the typed line, the read view, copy the latest
  URL (hold it to open the URL instead, whole even when it wrapped at the screen
  edge), copy the agent's last reply, paste a screenshot, Herdr's sidebar and
  the slash menu.
- **Read view.** Recent output in a full-screen view you can scroll and select.
  On a Herdr host it reads the focused pane with `herdr pane read`.
- **Draft view.** The text input button opens the whole draft in a large view,
  so a long dictated message can be read before it goes out.
- **Slash menu.** The agent's built-in slash commands (Claude Code, Codex,
  Grok) and your own commands and skills, read from the host.
- **Command palette.** One search over every action, from the console menu.
- **Paste screenshot.** Uploads the phone's newest screenshot to the host over
  SFTP and puts its path in the text field.
- **Session drawer.** Every open session on every host, with its state and the
  last line of output. Tap the host name or swipe in from the left edge.
- **Machines.** A list of your Tailscale machines, read from `tailscale status`
  on a connected host. One tap connects.

### Herdr

- **Found on any SSH host.** Herdr features turn on once Herdr answers on the
  host. There is no setting for it.
- **Workspace picker.** Connecting to a Herdr host offers its workspaces before
  attaching.
- **Herdr panel.** Every Herdr action, one group at a time, with numbered keys
  for the focused workspace's tabs. Long press a key to hide or change it, or
  add your own keystroke keys.
- **Keys from the host's config.** The prefix and every client key come from
  the host's Herdr config, with Herdr's defaults for anything it leaves out.
- **Gestures.** On the terminal of a Herdr session, one finger across switches
  tabs, two fingers across move pane focus, and two fingers up or down, or a
  fast one-finger flick up or down, step through workspaces. When the host's
  Herdr client has saved machines, that step goes through Herdr's own switcher
  (prefix+w, an arrow, Enter), so it reaches workspaces on the other machines
  too. A one-finger drag up or down that is not a flick still scrolls; a pinch
  still zooms.
- **Herd screen.** Every agent in the session by state (blocked, done, working,
  idle). Tap one to focus its pane.
- **Reattach.** When the Herdr client drops with `lost connection to server`,
  Mercurio runs `herdr` again and the session comes back.

### Notifications and approvals

- **Needs you.** A notification when an agent is blocked on you, and one when
  it finishes, from Herdr's agent state.
- **Approve or deny.** Answer a blocked Claude Code or Codex agent from its
  notification. This works with no session open: Mercurio logs in with the
  host's saved key, sends the answer and logs out. Other agents get the
  notification without the buttons.
- **Push.** While Android has the app frozen, a watcher on the host can wake
  it through [UnifiedPush][unifiedpush] and an [ntfy][ntfy] server you run.
  See [docs/PUSH.md](docs/PUSH.md).

### When something fails

- **Fix prompt.** A failed connection, a Herdr host that does not answer, or a
  failed Herdr action offers Copy fix prompt: the error and its
  diagnostics, with secrets removed, framed as a prompt for a coding agent.

## Requirements

- Android 7.0 (API 24) or newer.
- An SSH server on each host. Telnet hosts also work, without the Herdr
  features.
- [Herdr][herdr] on the host for the agent features. Without it, Mercurio is a
  plain SSH client and the Herdr keys stay dimmed. The key defaults follow
  Herdr 0.9.0.
- For push (optional): an ntfy server you run, the ntfy app on the phone, and
  `python3` on each Herdr host.

## Build from source

Mercurio is not on Google Play. Build it from source:

- A JDK, version 17 or newer.
- The Android SDK. Gradle finds it through `ANDROID_HOME` or `sdk.dir` in
  `local.properties`.
- A full clone with tags. The version name comes from the latest `v*` tag, and
  the versioning plugin fails inside a git worktree.

```sh
git clone https://github.com/Ormus-Solutions/mercurio.git
cd mercurio
./gradlew :app:assembleOssDebug
adb install -r app/build/outputs/apk/oss/debug/app-oss-debug.apk
```

`oss` is the only build flavor. The debug build's application id ends in
`.debug`, so it installs beside a release build.

### Checks

This repo runs no hosted CI. Before a merge, run the gate in a full clone:

```sh
./gradlew testOssDebugUnitTest spotlessCheck :app:assembleOssDebug
./gradlew :app:lintOssDebug
```

Run lint on its own: the lint analyzer can crash when it shares a Gradle run
with other tasks. To prove a change on an emulator, follow
[`.grok/skills/verify-mercurio/SKILL.md`](.grok/skills/verify-mercurio/SKILL.md).

## Host setup

1. Install [Herdr][herdr] on the host and check that `herdr` runs in an SSH
   session.
2. In Mercurio, add the host and set its **Post-login automation** to `herdr`.
   Each connection then attaches to the host's Herdr session, which keeps its
   panes and agents between connections.
3. For push alerts while the phone sleeps, set up the ntfy server, the ntfy app
   and the host watcher in [docs/PUSH.md](docs/PUSH.md).

## Privacy

- **No Google services.** The build has no Google Play services, Firebase or
  analytics library. Push goes through UnifiedPush to a server you run.
- **Push messages carry ids only.** A push holds pane, tab and workspace ids
  and labels, never pane text, prompts or secrets. Mercurio reads the pane over
  SSH after it wakes.
- **Usage log.** Mercurio keeps a log of which controls you use, to guide
  design. It records action ids, never typed text, terminal output, hostnames,
  usernames, keys or passwords. It is stored in the app's private files.
  Recording is on by default. Turn off Record my usage in Settings, Usage and
  wish list, and nothing is recorded.
- **Upload only over your own SSH.** The log and the wish list leave the phone
  only over an SSH session to a host named in that same settings section, and
  only when the session's host key matches the key pinned there. There is no
  HTTP endpoint and no third party.
- **Diagnostics on request.** A fix prompt is built on the phone with private
  keys and saved passwords removed. It goes to the clipboard only when you tap
  Copy fix prompt, and into the wish list only when you tap Send to wish list.

## License and credit

Mercurio is licensed under the [Apache License 2.0](LICENSE). See
[NOTICE](NOTICE) for what was changed and for third-party code.

Mercurio is built on the work of the ConnectBot project:

- [ConnectBot][connectbot] by Kenny Root and contributors, the app this is a
  fork of.
- [ConnectBot Terminal][termlib], the terminal emulator, by Kenny Root.
- [ConnectBot's fork of Trilead SSH-2][sshlib], the SSH library, originally by
  Christian Plattner.

The Herdr models and client in `solutions/ormus/logos/herd/` are adapted from
[Termish][termish] (MIT).

Mercurio is not affiliated with or endorsed by the ConnectBot project. Report
Mercurio problems in this repository, not upstream.

[connectbot]: https://github.com/connectbot/connectbot
[termlib]: https://github.com/connectbot/termlib
[sshlib]: https://github.com/connectbot/sshlib
[herdr]: https://github.com/herdrdev/herdr
[termish]: https://github.com/farlume/termish
[unifiedpush]: https://unifiedpush.org
[ntfy]: https://ntfy.sh
[ormus]: https://ormus.solutions
