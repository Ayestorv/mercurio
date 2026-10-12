# Garden

The garden is Mercurio's genome and its health: what the project is meant to be, how it is meant to grow, and what has gone wrong before. The kitchen (pantry, Menu, Desk, Kitchen seat) builds; the garden says what may be built and keeps the tree healthy. The pattern comes from the ormus-stack `garden/` and its `gardener` and `garden-report` skills.

## Files

| File | Role |
|------|------|
| [DNA.md](DNA.md) | The genome: purpose, evidence rule, growth rules, surfaces and proof, refusals, what done means. Every automated reader reads it first. |
| [merge-policy.yml](merge-policy.yml) | Machine-readable: what merges on its own, what waits for the operator, what runs after a merge, how to roll back. |
| [WEEDS.md](WEEDS.md) | Ledger of pulled weeds by kind, and the check that now catches each kind. |
| [GARDEN.md](GARDEN.md) | Dated health snapshot rendered by the stack's `garden.py`: beds, waiting for review, weeds, soil, water, pests. |

## How the garden is read

Every reader cites the DNA line (`P2`, `E1`, `G4`, ...) it acts on, so a decision can be traced back to a rule.

- **Pantry fill** reads `DNA.md` before stocking. It stocks only atoms that fit the Purpose and that carry evidence the Evidence rule admits, and each atom's Evidence cell names the DNA line that admits it. Sources are the usage report, the inbox wishes, the project's Linear items and the competitor map (`pantry/README.md`).
- **Menu ordering** respects the Growth rules: an atom whose Done-when would break a growth rule or a refusal is not ordered; it goes to the queue's "Explicitly not stocked" list with the rule id as the reason.
- **The builder's brief** (the Desk's restatement of a plated Goal, and the dream cycle's healer prompt) quotes the DNA lines the atom cites and the Growth rules and Refusals as a whole. The builder cites them in the PR's Why and Not changed.
- **The verifier** (the Kitchen seat's verdict on a PR's latest commit) treats `DNA.md` as the rulebook: a PR that breaks a rule is a FAIL with `rung: structure`, naming the rule id. A PASS with every auto-merge requirement met and no escalation rule fired (`merge-policy.yml`) may merge on its own.
- **The gardener** pulls one provable weed per run as a small PR, never a refactor, never a behavior change. A DNA rule broken in the tree is a weed, proven with `grep`, and its row in `WEEDS.md` names the rule id. The second sighting of a kind ships the check that catches it.

## Rendering the snapshot

From a full clone of this repo, with the stack checked out beside it:

```bash
python3 <ormus-stack>/skills/garden-report/scripts/garden.py --root . --repo Ormus-Solutions/mercurio-dev --host "kitchen machine" --write
```

`--host "kitchen machine"` matches the Routines table below, so the report checks this machine's crontab for them. The script walks up from `--root` to the directory holding `pantry/README.md`. Its Plot line counts stack skills, commands and guide pages, which this repo does not hold, so it reads zero here; the rest of the report is about this repo. Before committing, check the Copy line for a home path and write it as `.`: nothing personal lands in the tree (`DNA.md` G3). Then run `./gradlew spotlessApply`: the script ends the file with a blank line that `spotlessCheck` refuses.

## Routines

The garden report checks each routine listed for the host it runs on against that host's crontab; a missing one is a pest. Cron holds the schedule.

| Routine | Host | Crontab match |
|---------|------|---------------|
| courier | kitchen machine | `loops/mercurio-courier/run.sh` |
| dream cycle | kitchen machine | `loops/mercurio-dream/run.sh` |
