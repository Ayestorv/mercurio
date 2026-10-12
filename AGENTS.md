# Repository guidelines

The rules live in [garden/DNA.md](garden/DNA.md): purpose, evidence, growth rules, proof surfaces, refusals and what done means, each with an id. This file is the map of the repo and its build; when the two disagree, the DNA wins. [garden/merge-policy.yml](garden/merge-policy.yml) says what merges on its own and what waits for the operator.

## Project structure

Mercurio is an Android SSH client for driving AI coding agents in Herdr, forked from ConnectBot. It is a Gradle project with one product flavor, `oss`. The app lives in `app/`. ConnectBot's code and most of the app sit under `app/src/main/java/org/connectbot`: UI in `ui/`, data and Room in `data/`, dependency injection in `di/`, terminal and service code in `service/`, protocol code in `transport/`, the usage log in `usage/`. Mercurio-only features sit under `app/src/main/java/solutions/ormus/logos`: Herdr in `herd/`, push in `push/`, agent commands in `commands/`. Resources are in `app/src/main/res`, Room schemas in `app/schemas`. Unit tests are in `app/src/test/kotlin`, instrumentation tests in `app/src/androidTest`, shared test helpers in `app/src/sharedTest/kotlin`. `scripts/` holds the usage log readers, `host/` the push watcher for Herdr hosts, `docs/` the design notes.

## Build and checks

Use the checked-in Gradle wrapper in a full clone. Never use a git worktree: the app-versioning plugin fails in worktrees. Run one Gradle build per clone at a time.

- `./gradlew :app:assembleOssDebug` builds the debug APK.
- `./gradlew testOssDebugUnitTest` runs the JVM and Robolectric tests.
- `./gradlew spotlessCheck` checks formatting; `./gradlew spotlessApply` fixes it.
- `./gradlew :app:lintOssDebug` runs Android lint. Run it on its own: it can crash when it shares a Gradle run with other tasks.
- `./gradlew connectedOssDebugAndroidTest` runs instrumentation tests on a device or emulator. It wipes the app's data there.

The gate before a merge is `./gradlew testOssDebugUnitTest spotlessCheck :app:assembleOssDebug`, then `./gradlew :app:lintOssDebug`. There is no hosted CI. To prove a change on an emulator, follow `.grok/skills/verify-mercurio/SKILL.md`.

## Coding style

Target JVM 17. Kotlin uses ktlint with the Compose rules. Spotless enforces it and the license header in `spotless/license-header.txt`; files copied from Termish keep their MIT header (see `NOTICE`). Name types in `PascalCase`, functions and properties in `camelCase`, tests `*Test`. Prefer the existing Compose, Hilt, Room and repository patterns over new abstractions. Declare dependency and plugin versions in `gradle/libs.versions.toml`. In Compose, read UI strings with `stringResource()`, and put an XML comment before each new `strings.xml` entry so translators know where it shows. Never change the applicationId `solutions.ormus.logos` or the namespace `org.connectbot`.

## Tests

Add focused tests for behavior changes. Use Robolectric in `app/src/test/kotlin` for UI and ViewModel tests; use instrumentation tests only when device APIs, the Hilt runner or emulator state are required. Keep Compose business logic in ViewModels so local tests can cover it. For database changes, update the Room schema JSON under `app/schemas` and test the migration.

Every new user action gets an id in `usage/UsageActions.kt` and the same line in `docs/usage-actions.txt`, in the same order (`UsageSchemaTest` checks). Ids only: never typed text, keys or passwords. See `docs/USAGE.md`.

## Commits and pull requests

Commit subjects follow `type(scope): description`, for example `fix(push): post a pushed Needs you before the push service stops`. Types: feat, fix, refactor, perf, docs, test, style, chore, build, ci, revert. One change per pull request, against `main` on Ormus-Solutions/mercurio. Run `git diff --check` and the gate above before opening it. Describe the change, link the issue, name the tests, and add screenshots for visible UI changes.

## Agent-specific instructions

Do not overwrite unrelated local changes. Keep edits scoped to the requested behavior, and leave generated schema and translation files alone unless the change needs them. Never block the main thread; run IPC, network, disk I/O and other long work off it. In injected classes and ViewModels, use the injected `CoroutineDispatchers` instead of hardcoded `Dispatchers.IO` so tests can control execution.
