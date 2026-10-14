# Pantry: Mercurio

Goal atoms for Mercurio, an Android control surface for one operator's agent fleet: speak into, read and move between many Claude Code, Codex and Grok sessions running in Herdr on the operator's machines, over SSH on Tailscale. This pantry belongs to the Mercurio Stack Kitchen and stocks only atoms provable on `Ormus-Solutions/mercurio-dev` (base branch `main`) or on its running debug build (`solutions.ormus.logos.debug` on the emulator `emulator-5554`, a throwaway AVD, against a Herdr host).

## How this fills

1. Run the `pantry-fill` skill (ormus-stack) naming Mercurio and this repo.
2. Copy each `TEMPLATE-*.md` to a dated file (`YYYY-MM-DD-*.md`). Leave the templates intact.
3. Fill the competitor map, then the X mine, then the pantry queue, in that order.
4. Stock only atoms whose Done-when can be shown true on this repo or on the running app.
5. Never invent engagement counts, star counts, sentiment percentages or quotes. Cite real URLs or leave the cell blank.
6. Cloud Agents, merges and spend still need the maintainer's Yes.
7. Regenerate the Menu with `menu.py fill --pantry pantry --repo Ormus-Solutions/mercurio-dev` from a full clone of this repo; never edit `MENU.md` by hand.

A restock reads the operator's own usage on the usage host as a source for atoms (see [docs/USAGE.md](../docs/USAGE.md)). Open wishes and errors from `python3 scripts/inbox.py list` are a first-class source: an atom built from one cites the wish text and its date. The latest `python3 scripts/usage-report.py` output is usage evidence: an atom cites "used N times" or "never used" for the controls it touches.

Builds and checks run in a full clone, never a git worktree (the app-versioning Gradle plugin fails in worktrees), one Gradle build per clone at a time. The checks are `./gradlew testOssDebugUnitTest spotlessCheck :app:lintOssDebug :app:assembleOssDebug`.

There is no people mine: the repo is private and has no outside users, so `TEMPLATE-people-mine.md` stays unused until that changes. Atoms are mirrored to Linear (Ormus Solutions workspace, project Mercurio); the Menu is the engine and Linear follows it, nothing flows the other way.

## Templates

| File | Role |
|------|------|
| `TEMPLATE-competitor-map.md` | Blank map; copy to a dated run |
| `TEMPLATE-x-mine.md` | Blank X praise and complaint mine |
| `TEMPLATE-pantry-queue.md` | Blank Goal-atom queue |
| `TEMPLATE-people-mine.md` | Blank people mine (unused while the repo is private) |

## Latest run

- Competitor map: [2026-10-12](2026-10-12-competitor-map.md)
- X mine: [2026-10-12](2026-10-12-x-mine.md)
- Pantry queue: [2026-10-12](2026-10-12-pantry-queue.md)
- Menu: [MENU.md](MENU.md)
