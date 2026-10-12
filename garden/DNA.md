# Mercurio DNA

The genome: what Mercurio is, what counts as evidence for a change, how it grows, how a change is proven, and what it refuses. Every automated reader (pantry fill, Menu ordering, the builder's brief, the verifier, the gardener) reads this file first; [README.md](README.md) says how each one uses it. The rules are taken from `docs/INTENT.md`, `docs/DESIGN.md`, `AGENTS.md`, `CONTRIBUTING.md`, `pantry/README.md`, `docs/USAGE.md`, `docs/PUSH.md` and the merged pull requests; each rule names its source. When this file and another doc disagree, fix the other doc: this one is the rulebook.

Each rule has an id (`P1`, `E2`, ...) so a pantry atom, a Menu order, a PR body or a verdict can cite the line that admits or refuses it.

## Purpose

- P1. One operator. Mercurio is a phone console for that operator's own fleet of AI coding agents (Claude Code, Codex, Grok) running in Herdr on machines the operator owns, reached over SSH on Tailscale. The phone is a control surface; the work runs elsewhere. (`docs/INTENT.md`)
- P2. Herdr is the substrate. Sessions, workspaces, tabs, panes, focus, agent state and the prefix come from Herdr (`herdr api snapshot`, `herdr pane ...`, the host's Herdr config). Mercurio follows Herdr's model and vocabulary and never invents a parallel one: no session registry of its own, no agent state machine of its own, no relay. (`herd/HerdrModels.kt`; pantry queue 2026-10-03, "Explicitly not stocked": relays and daemons)
- P3. Dictation first, one thumb, minimal chrome. Screen space goes to terminal output. Everything is reachable with a thumb: large targets, left-edge gestures, the session drawer instead of menus. (`docs/INTENT.md`, Principles)
- P4. The app says what the agents are doing without opening each session, and reaches out (Needs you) when one is blocked. (`docs/INTENT.md`)
- P5. Non-goals: a faithful upstream ConnectBot; general terminal power features that do not serve agent work; session content leaving the device, except to a machine the operator owns. (`docs/INTENT.md`, Non-goals)

## Evidence rule

A pantry atom, a Menu row or a PR cites at least one of these. Nothing else admits work.

- E1. The usage log: "used N times" or "never used" for the controls the change touches, from the repo's usage report script (`scripts/usage-report.py`, run on the usage host). The tray was slimmed on 820 logged actions this way (PR #78). (`docs/USAGE.md`)
- E2. An inbox wish or error: the wish text and its date, from `scripts/inbox.py list`. (`docs/USAGE.md`, `pantry/README.md`)
- E3. A Linear item in the project's Linear project, by its id. The Menu is the engine and Linear mirrors it.
- E4. A competitor's shipped feature, with the URL that shows it shipped, from the pantry's competitor map. (`pantry/README.md`)
- E5. The operator's own ask, quoted in the PR's Why. (shipped practice: PRs #65, #68, #78, #120 quote the ask)
- E6. Never invented: engagement counts, star counts, sentiment percentages, quotes. Cite the URL or leave the cell blank. (`pantry/README.md`, rule 5)

## Growth rules

- G1. One change per PR, about 200 logic lines (tests and generated files not counted), with proof in the body: a test that fails on `main` and passes on the PR, the gate command with its exit code, and what the branch was lived with. Over budget means split, or say why in the body. (`AGENTS.md`, `CONTRIBUTING.md`)
- G2. New behavior ships on unless the operator asked for a setting. A new control acts where it makes sense (Herdr sessions) and sits dimmed elsewhere, so every host keeps the same layout. (PRs #92, #118, #120, `behavior: new-on`; PR #92: "a setting was not asked for")
- G3. Nothing personal in the tree: no IP addresses, usernames, machine names, home paths, client names, chat ids, pane titles that describe client work, keys or tokens. Tests and fixtures use synthetic values. Every release is scanned before it reaches the public mirror. (PRs #100, #101, #104, #107)
- G4. Brand: Ormus tokens only (`app/brand/ormus-tokens.json`, generated into `ui/theme/OrmusTokens.kt`), no hand-pasted brand color. Gold is the one filled action per surface; every other key is a hairline ghost. No red, no pills. One radius, 3dp, everywhere (`KEY_SHAPE`, `OrmusCornerShape`). Mono "/ LABEL" micro-labels. Keys come from `ui/components/OrmusKey.kt`. Dark only. (`docs/DESIGN.md`, PR #71)
- G5. Usage ids: every new user action gets an id in `usage/UsageActions.kt` and the same line in `docs/usage-actions.txt`, in the same order (`UsageSchemaTest`). Ids only; never typed text, keys or passwords. (`AGENTS.md`, `docs/USAGE.md`)
- G6. The applicationId stays `solutions.ormus.logos` and the namespace stays `org.connectbot`. A new applicationId is a different app and the phone loses its hosts and keys. (`AGENTS.md`)
- G7. Code: match the surrounding style and comment density; the existing Compose, Hilt, Room and repository patterns over new abstractions; versions in `gradle/libs.versions.toml`; UI strings through `stringResource()` with a comment for translators; the injected `CoroutineDispatchers`, never a hardcoded dispatcher; never block the main thread. (`AGENTS.md`)
- G8. Ideas from other clients are copied in under their own license with their notice kept in `NOTICE`, when the license allows it. Their repos stay theirs. (`NOTICE`; pantry queue 2026-10-03)
- G9. Commit subjects are `type(scope): description`. No AI footers, no "generated with" lines, no em dashes, no durations in a PR body. Every claim carries its label: measured, inferred or guess. (`AGENTS.md`; the merged PRs since #100)

## Surfaces and proof

- S1. The gate, in a full clone (never a git worktree: the app-versioning plugin fails there), one Gradle build per clone: `./gradlew testOssDebugUnitTest spotlessCheck :app:assembleOssDebug`, then alone `./gradlew :app:lintOssDebug`. `./gradlew spotlessApply` fixes formatting. There is no hosted CI; the nightly dream cycle reruns the gate on `main`. (`AGENTS.md`, `CONTRIBUTING.md`)
- S2. Unit tests for logic. Robolectric in `app/src/test/kotlin` for UI and ViewModels. Instrumentation tests only when device APIs, the Hilt runner or emulator state are required; they wipe the app's data on the device. (`AGENTS.md`)
- S3. Emulator: the debug build `solutions.ormus.logos.debug` on `emulator-5554`, running the AVD the kitchen names (`vibe_pixel` in the ops repo's verify skill), with the shared emulator lock taken in the same shell as the adb commands. The APK's versionName ends in the head's short SHA (`control-mercurio run --pr N --expect <sha>`). Screenshots and `uiautomator` dumps travel with the PR. (`pantry/README.md`; the verify skill)
- S4. Live Herdr and SSH checks run against throwaways: a named Herdr session the run creates and deletes (own config, state and data dirs), a throwaway sshd with a test key removed afterwards, a loopback server bound to 127.0.0.1. Never the operator's own sessions, saved machines or phone. (PRs #92, #108, #115, #120)
- S5. Push: `python3 -m unittest discover -s host/tests` (fake Herdr socket, no network). (`docs/PUSH.md`)
- S6. "Not proven" is written in the PR, never left out. (PRs #97, #115, #120)

## Refusals

- R1. No GPL or AGPL code in the tree. Apache-2.0 and MIT sources only, notices kept. A GPL client is design reference only. (pantry queue 2026-10-03; `NOTICE`)
- R2. No relay, cloud service or daemon that the host does not already run. Herdr over SSH is the transport; a check on the host is one exec, nothing installed. The one host-side piece is the project's own push watcher in `host/`, which the operator installs and which carries ids and labels only. (pantry queue 2026-10-03; `docs/PUSH.md`; PR #115)
- R3. No secrets or typed text in logs or usage events: nothing typed or dictated, no terminal output, no hostnames, usernames, IPs, keys or passwords. `UsageSchemaTest`, `ComposeBarUsageTest` and `UsageTrackerTest` prove it. (`docs/USAGE.md`)
- R4. No hosted CI: no `.github/workflows`, no Dependabot for actions. The local gate is the gate. (PRs #64, #109)
- R5. No session content off the device except to the usage host the operator set, over an SSH session the operator opened, with a pinned host key. No analytics SDK, no third party. (`docs/USAGE.md`, `docs/INTENT.md`)
- R6. No light mode and no theme setting. (`docs/DESIGN.md`)
- R7. Never weaken, skip or delete a test, a check or a lint rule to make the gate pass. (the kitchen law: "never weakening or skipping one")

## Merge policy

[merge-policy.yml](merge-policy.yml) is the machine-readable rule for what merges on its own and what waits for the operator. In short: a PR merges on its own only on a PASS verdict for its head, the gate green, the leak scan clean and the swarm clean. A guarded path, a FAIL or INCONCLUSIVE verdict, a diff over 400 logic lines, a new dependency or a new manifest permission goes to the operator.

## What done means

- D1. Merged on `main`.
- D2. Released: tagged, built with proof that the APK is that commit, and published to the public mirror after the release scan passes.
- D3. Verified on the emulator on the merged commit. Never on the phone.
- D4. The APK reaches the operator as a bare link, nothing else in the message.
