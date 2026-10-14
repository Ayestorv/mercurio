# Usage log and wish list

Mercurio records how its operator uses it, so design changes follow real use
instead of guesses. The first change it fed was decluttering the compose bar.
The data belongs to the operator: no analytics SDK, no third party, nothing
leaves the phone except to one machine the operator owns, the usage host, over
an SSH session the operator already opened. Settings names the usage host
"Sun".

Alongside it sits a wish list: wishes the operator types or dictates, and
connection error reports sent from a failed session. Both are read on the usage
host, by the operator or an agent working there.

## What is recorded

One usage event per use of a control or feature:

| Field | What it holds |
|---|---|
| `action` | A stable action id such as `key:up`, `herdr:goto`, `reader:open`, `send`. Every id is listed in [`usage-actions.txt`](usage-actions.txt); the app drops any id not on that list. |
| `screen` | The screen it happened on, from the navigation route name only (`console`, `hostlist`, `settings`). |
| `herdr` | Whether the active host's session runs Herdr (true, false, or empty off the console). |
| `ts` | When, in epoch milliseconds. |
| `seq` | A per-device counter, used to upload each event exactly once. |
| `duration_ms` | Session length, on `session:end` only. |
| `reason` | A reason class (`REMOTE_EOF`, `NETWORK_LOST`, `AUTH_FAIL`, ...), on `session:end` and `session:fail` only. |

Sessions add `session:start`, `session:end`, `session:fail`,
`session:reconnect` and `session:auto-reconnect` (a dropped link reconnected
once with no tap). A refused upload adds `key:upload-refused-hostkey`.

Raw events older than 30 days, or beyond the newest 5000, roll up into one row
per day, action, screen and host type (`type: daily`, with a `count`), so the
store stays bounded.

## What is never recorded

Usage events have no field for text, so none of these can land in them:

- anything typed or dictated into the compose bar or any other field
- terminal output
- hostnames, usernames, IP addresses (the host's nickname is not recorded either)
- keys, passwords, passphrases

Tests prove it: `UsageSchemaTest` checks that every string field is an
identifier, `ComposeBarUsageTest` types a password into the real compose bar and
checks the store, and `UsageTrackerTest` checks that free text passed as an id
or a reason is dropped.

The wish list is the one deliberate exception, kept in a separate store:

- A **wish** holds the text written down as a wish, plus when, the screen and
  whether the host runs Herdr.
- An **error** holds the connection report that "Copy fix prompt" wraps (app and
  device, network, host, failure, connection log and the recent transcript),
  built by `DiagnosticsReporter`, which scrubs private keys, `password=` style
  values and the host's saved passwords before anything is stored.

## Settings

Settings, "Usage and wish list":

- **Record my usage** (on by default): off records nothing at all.
- **Copy usage summary**: top actions, least-used and never-used controls, and
  splits per screen and per host type; shown on screen and copied.
- **Wish list**: read, edit, mark done, reopen and delete wishes and errors.
- **Upload to Sun now**: uploads over the open session to the usage host, if
  there is one.
- **Sun hosts**: which hosts count as the usage host, by hostname or nickname,
  comma separated (for example `100.x.y.z, your-host`).
- **Sun host key**: the usage host's pinned host key, as a `SHA256:...`
  fingerprint. On the host, `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`
  prints it.
- **Clear usage**: deletes the usage on the phone. The wish list stays.

A wish is two taps away: the console overflow menu or the host list menu, then
**Wish**. It opens one field with the keyboard up, so the IME's voice input is
one more tap. A failed connection shows **Send to wish list** next to **Copy
details** on the overlay, in the console overflow menu and in a disconnected
host's menu on the host list.

## Upload to the usage host

When an SSH session to the usage host opens, the phone uploads if a day has passed since
the last upload or if wish-list entries are waiting. "Upload to Sun now" does it
on demand. Uploads run on the IO dispatcher, one at a time, and never block the
UI.

Files on the usage host, in `~/.local/share/mercurio-usage/`:

| File | Content |
|---|---|
| `<device-id>.jsonl` | Usage events and daily roll-ups, appended. |
| `<device-id>.cursor` | The last event `seq` the usage host holds. |
| `<device-id>-wishes.jsonl` | The wish list as the phone holds it, replaced on each change. |
| `closed.json` | Items closed on the usage host with `inbox.py close`. |

The device id is a random UUID made on first use; it is not the Android ID.

Nothing is sent twice and nothing is lost:

- The phone reads the host's cursor first and sends only events after it. The host appends
  only if the cursor still matches, then moves it. If the phone dies after the host
  wrote but before it heard back, the next upload reads the moved cursor and
  sends nothing old.
- The wish list goes as a full snapshot that replaces the file, so edits and
  deletions land as they are and a deleted wish's text leaves the host.
- Errors happen when there is no connection, so wishes and errors queue in the
  phone's storage and survive restarts. A failed upload marks nothing, so the
  next connection retries.

## Who can read and write it

- **Transport**: only the SSH session to the usage host that the operator
  already authenticated with their key, over SFTP and an exec channel. There is no HTTP endpoint, server,
  token or third party.
- **Host key pin**: the phone uploads only when the host key that session
  verified matches the pinned fingerprint in Settings. On a mismatch it sends
  nothing, keeps the queue, and records `key:upload-refused-hostkey`.
- **On the usage host**: the directory is created with mode 700 and every file in
  it is set to 600 on every upload, so only the operator's account can read or
  write it.
  `inbox.py` and `usage-report.py` refuse to run if the directory or any file is
  group- or world-readable, and print the fix:
  `chmod 700 ~/.local/share/mercurio-usage && chmod 600 ~/.local/share/mercurio-usage/*`.
- **On the phone**: the store lives in the app's private files directory
  (`files/usage/`), owner-only, never on external storage. The app's backup
  agent copies only preferences and the filtered host database, so the store is
  not in device backups. Only the three settings above live in preferences.

## Reading it on the usage host

The wish list, from a checkout of this repo on the usage host:

```sh
python3 scripts/inbox.py list          # open items, errors first, newest first
python3 scripts/inbox.py list --json   # the same, machine-readable
python3 scripts/inbox.py show <id>     # one item in full (an id prefix is enough)
python3 scripts/inbox.py close <id> fixed in PR #61
```

Each item shows its kind, date, text and the usage counts it relates to (a wish
that mentions scrolling shows how often `key:up` was used). `close` writes
`closed.json`; the phone reads it on its next upload and marks the item done,
with the note. An item reopened on the phone after that stays open.

The usage report:

```sh
python3 scripts/usage-report.py
```

It prints markdown: open errors and wishes first, then actions ranked by use,
controls never used (against `docs/usage-actions.txt`), per-screen and
per-host-type splits, trends (the last 7 days against the 7 before; `--days N`
changes the window) and sessions.

## How it feeds decisions

- A Mercurio pantry restock reads the latest `usage-report.py` output and the
  open wish list as sources for Goal atoms (see `pantry/README.md`). An atom
  cites its evidence: "used 41 times", "never used", or the wish text and date.
- Controls that are never used are the first candidates to fold away when the
  compose bar is decluttered; controls used most stay one tap away.
- An open error is a bug report with its diagnostics attached.

## Adding a control

1. Add its id to `UsageActions` (and to `UsageActions.ALL`).
2. Add the same id to `docs/usage-actions.txt` (`UsageSchemaTest` keeps the two
   equal).
3. Call `LocalUsageLog.current.log(UsageActions.YOUR_ID)` in its click handler.

Compose bar keys log through one map in `TerminalComposeBar` (`usageIdFor`); a
key whose sequence has no id there logs `key:other`, which the report flags.
