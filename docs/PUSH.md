# Push alerts: Herdr host to phone

Android freezes Mercurio when the phone sleeps, so its polling stops and a blocked agent waits
unseen. Push fixes that without Google: the host pushes, a self-hosted ntfy server relays, and the
ntfy app on the phone (the UnifiedPush distributor) wakes Mercurio.

```text
Herdr host                         ntfy server (Tailscale only)        phone
mercurio-push-watch  --POST JSON-->  /up<topic>  --held connection-->  ntfy app --UnifiedPush--> Mercurio
  follows the Herdr event socket      anonymous write on up* only        (one app kept awake)     reads the pane
  when a pane's agent turns blocked                                                               over SSH itself
```

The message carries ids and labels only. It never carries pane text, prompts or secrets; Mercurio
reads the pane over SSH after it wakes.

## Message

One JSON object per push, schema `v: 1`:

```json
{"v":1,"event":"pane.agent_status_changed","state":"blocked","host":"<hostname>","session":"default",
 "pane_id":"w1:p3","agent":"claude","workspace_id":"w1","workspace":"mercurio","tab_id":"w1:t2","tab":"build",
 "ts":1791214536}
```

`state` is Herdr's agent status (`blocked` by default, `done` too with `--states blocked,done`).
`workspace`, `tab` and `agent` can be null when Herdr has no label for them.

## Endpoints file

`${XDG_CONFIG_HOME:-~/.config}/mercurio/push-endpoints` on each Herdr host. One push endpoint URL
per line; blank lines and `#` comments are ignored; only `http` and `https` URLs count. Mercurio
writes its UnifiedPush endpoint here over SSH when it connects. The watcher reads the file again
on every push, so a new or replaced URL takes effect without a restart. No file means no pushes.

An endpoint URL is a write capability for one phone. Keep the file mode 600.

## ntfy server (one per tailnet)

Use the official static binary as a systemd user service. No root.

```sh
# binary (check the release checksum first)
curl -fsSLO https://github.com/binwiederhier/ntfy/releases/download/v2.28.0/ntfy_2.28.0_linux_amd64.tar.gz
curl -fsSLO https://github.com/binwiederhier/ntfy/releases/download/v2.28.0/checksums.txt
grep linux_amd64.tar.gz checksums.txt | sha256sum -c -
tar xzf ntfy_2.28.0_linux_amd64.tar.gz
install -m 755 ntfy_2.28.0_linux_amd64/ntfy ~/.local/bin/ntfy

# phone user password, and its bcrypt hash for the config
mkdir -p ~/.config/ntfy ~/.local/state/ntfy && chmod 700 ~/.config/ntfy ~/.local/state/ntfy
(umask 077; python3 -c "import secrets; print(secrets.token_urlsafe(18))" > ~/.config/ntfy/phone-password)
ntfy user hash    # paste the password twice; copy the $2a$... line
```

`~/.config/ntfy/server.yml` (mode 600):

```yaml
base-url: "http://<tailscale-ip>:2586"
listen-http: "<tailscale-ip>:2586"     # Tailscale address only, never 0.0.0.0
cache-duration: "1h"                   # in memory only; lets the phone catch up after a drop
auth-file: "<home>/.local/state/ntfy/user.db"
auth-default-access: "deny-all"
auth-users:
  - '<phone-user>:<bcrypt-hash>:user'
auth-access:
  - "*:up*:write-only"                 # hosts publish anonymously, UnifiedPush topics only
  - "<phone-user>:up*:read-only"       # the phone reads them
web-root: "disable"
```

No `attachment-cache-dir` (attachments off) and no `upstream-base-url` (nothing goes to ntfy.sh or
Firebase).

`~/.config/systemd/user/ntfy.service`:

```ini
[Unit]
Description=ntfy push server (UnifiedPush relay for Mercurio, Tailscale only)

[Service]
ExecStart=%h/.local/bin/ntfy serve --config %h/.config/ntfy/server.yml
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
```

```sh
systemctl --user daemon-reload && systemctl --user enable --now ntfy.service
loginctl show-user "$USER" -p Linger    # Linger=yes keeps user services up with nobody logged in
```

Check the access rules (expected codes in comments):

```sh
B=http://<tailscale-ip>:2586
curl -s -o /dev/null -w '%{http_code}\n' -d hi $B/upCheck1              # 200 anonymous write, up*
curl -s -o /dev/null -w '%{http_code}\n' -d hi $B/other                 # 403 anything else
curl -s -o /dev/null -w '%{http_code}\n' "$B/upCheck1/json?poll=1"      # 403 anonymous read
curl -s -u <phone-user>:"$(cat ~/.config/ntfy/phone-password)" "$B/upCheck1/json?poll=1&since=all"  # the message
```

## Phone

1. Install the ntfy app. The F-Droid build has no Google libraries.
2. Settings, Manage users, Add user: server `http://<tailscale-ip>:2586`, the phone user, and the
   password from `~/.config/ntfy/phone-password` on the server host.
3. Settings, Default server: `http://<tailscale-ip>:2586`. UnifiedPush registrations go to the
   default server, so set it before Mercurio registers.
4. Let ntfy ignore battery optimization when it asks. It is the one app that stays awake.
5. Tailscale must be on for the phone to reach the server.

## Watcher on a Herdr host

Needs `python3` and a route to the ntfy server (Tailscale). Nothing else.

```sh
install -m 755 host/mercurio-push-watch ~/.local/bin/mercurio-push-watch
install -m 644 host/mercurio-push-watch.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now mercurio-push-watch.service
journalctl --user -u mercurio-push-watch -f    # "watching N panes on .../herdr.sock"
```

It follows every running Herdr session (the default one and each under `~/.config/herdr/sessions/`)
and looks for new ones every 15 s; each alert names its session, and Mercurio answers Approve and
Deny in that session. To follow one session only, add `--session <name>` to `ExecStart`.
It resubscribes when panes open or close, reconnects with backoff (up to 60 s) when the Herdr
server restarts, and pushes one alert per pane per 20 s at most (`--debounce`). It stays quiet
about panes that were already blocked when it started. With no Herdr config directory on the host
it exits 0 and systemd leaves it stopped.

Manual test without the app, using a throwaway topic:

```sh
T=upTest$RANDOM
curl -sN -u <phone-user>:<password> http://<tailscale-ip>:2586/$T/json &      # subscriber
mkdir -p ~/.config/mercurio && echo "http://<tailscale-ip>:2586/$T?up=1" >> ~/.config/mercurio/push-endpoints
# make an agent blocked in a pane of your own; the subscriber prints the message
```

Remove the test line from the endpoints file afterwards.

## Stop or remove

```sh
systemctl --user disable --now mercurio-push-watch.service   # on each Herdr host
systemctl --user disable --now ntfy.service                  # on the server host
```

Removing the unit files and binaries after that leaves nothing running.

## Troubleshooting

- No push when an agent asks for permission: check `herdr pane get <pane>` shows `agent` and
  `agent_status: blocked`. Herdr names the agent from the process. A wrapper that `exec`s Claude
  Code's versioned binary (`~/.local/share/claude/versions/2.x.y`) runs as `2.x.y`, so Herdr never
  identifies it and never reports blocked. Exec it through a path whose last part is `claude`.
- `no endpoints in ...` in the journal: the app has not written its endpoint to this host yet.
- `push to <host> failed`: the ntfy server is down or not reachable over Tailscale.

Tests: `python3 -m unittest discover -s host/tests` (fake Herdr socket, no network).
