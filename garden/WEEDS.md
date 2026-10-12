# Weeds

One row per weed pulled, by the gardener or by hand. Kind is a short slug; reuse a kind already in the table when a new weed matches it. Check names what now catches the kind (a gate step, a test, a script in the ops repo), or starts with `none`.

The second row of a kind must name a check. The stack's `garden.py --repeats-only` fails when a kind has two rows and no check. Keep pipes out of cells. A DNA rule broken in the tree is a weed too: the row names the rule id (`garden/DNA.md`) in the Weed cell.

## Ledger

| Date | Kind | Weed | Check |
|------|------|------|-------|
| 2026-10-03 | spotless | formatting that failed `spotlessCheck`: two `style(console): satisfy spotless` commits and trailing spaces in the pantry templates (#34) | `./gradlew spotlessCheck` in the gate, over Kotlin, Gradle, XML, TOML and Markdown |
| 2026-10-04 | usage-id-drift | ids in `UsageActions.ALL` and `docs/usage-actions.txt` unequal or out of order; two PRs appending at once need a one-line rebase (#118, #120) | `UsageSchemaTest` in the gate (#56) |
| 2026-10-05 | personal-values | the maker's addresses, usernames, machine names, home paths and pane titles in defaults, docs, tests and fixtures (#100, #101, #104, #107) | the release scan in the ops repo (deny list plus gitleaks) and the dream cycle leak scan |
