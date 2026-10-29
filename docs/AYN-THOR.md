# Ayn Thor (dual screen + built-in controller)

Mercurio runs on the Ayn Thor, an Android handheld with a second (lower) screen and a
built-in game controller ("Odin Controller"). Paired Xbox pads get the same map.

## Two screens

- The top screen keeps the terminal. While a controller is attached and controller control
  is on, the touch key bar is hidden and the soft keyboard no longer opens by itself (tap
  the terminal to type).
- The lower screen (any display Android lists as a presentation display) shows Herdr's
  sidebar for every saved host that runs Herdr: workspaces and agents with their state,
  one foldable section per host. A host with an open session is read through it; any
  other is read over a background login with its saved key (no terminal, nothing typed).
- Tap a workspace or agent to focus it. On a host that is not on top, Mercurio opens that
  host's console and focuses the agent, the same way a Herdr notification does.
- While the lower screen shows the sidebar, Mercurio collapses Herdr's own sidebar in the
  terminal once each time the client attaches (prefix+b).

## Controller

| Control | Action |
|---|---|
| D-pad | Focus pane left / down / up / right (prefix + h/j/k/l) |
| LB / RB | Previous / next tab |
| LT / RT | Previous / next workspace (prefix + alt+p / alt+m) |
| Right stick ↑↓ | Workspace picker: the first flick opens it, more flicks move |
| A | Enter (picks in the picker) |
| B | Esc on release; hold ≥ 0.35 s: Backspace, repeating |
| X | Agent that needs you (prefix + o); hold 0.6 s: close pane |
| Y | Shift+Tab; hold ≥ 0.3 s: Space held (Claude Code hold-to-talk) |
| L3 / R3 | Zoom pane / next agent |
| Left stick | Arrow keys, repeating |
| View | Split right; hold 0.6 s: new tab (Share on an Xbox pad) |
| Menu | Split down |
| View + Menu | Controller control on/off (Guide on an Xbox pad) |

Bound keys use the host's own Herdr config. Herdr keys are only sent while Herdr's client is
on screen, never into a shell.

## The Thor's microphone (hold Y to talk)

Claude Code records the default source of the host it runs on. While Y is held, Mercurio
streams the phone's microphone (48 kHz s16 mono) over the session's SSH connection into
`~/.local/bin/thor-mic` on the host, which makes a virtual source `thor_mic` the default
until the stream ends, then restores the previous one.

On a host whose Herdr client has SSH machines, `thor-mic` also forwards the audio to every
enabled machine's `thor-mic`, so agents on those machines hear the Thor too.
`thor-mic-warm` (a user service) keeps those SSH connections open so a press starts at once.

Host setup:

```sh
install -m755 host/thor-mic host/thor-mic-warm ~/.local/bin/
# on the host the Thor attaches to, when it has Herdr machines:
install -m644 host/thor-mic-warm.service ~/.config/systemd/user/
systemctl --user enable --now thor-mic-warm
```

Needs PipeWire (or PulseAudio) with `pactl` and `pacat` on each host.
