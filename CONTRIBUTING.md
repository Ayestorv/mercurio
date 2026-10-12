# Contributing

Mercurio is a fork of [ConnectBot](https://github.com/connectbot/connectbot). Report Mercurio bugs and send Mercurio changes here, not upstream.

## Issues

Open an issue on [Ormus-Solutions/mercurio](https://github.com/Ormus-Solutions/mercurio/issues) before a large change, so the approach can be agreed first. A small fix can go straight to a pull request.

## Changes

1. Fork the repo and make a full clone. Do not use a git worktree: the app-versioning Gradle plugin fails in worktrees.
2. Branch from `main`.
3. Keep one change per pull request, with tests for any behavior change.
4. Write commit subjects as `type(scope): description`, for example `fix(push): post a pushed Needs you before the push service stops`.
5. Run the gate. There is no hosted CI, so this is the check:

   ```bash
   git diff --check
   ./gradlew testOssDebugUnitTest spotlessCheck :app:assembleOssDebug
   ./gradlew :app:lintOssDebug    # on its own: lint can crash when it shares a Gradle run
   ```

   `./gradlew spotlessApply` fixes formatting.
6. Open the pull request against `main` on Ormus-Solutions/mercurio. Say what changed and why, name the tests, and add screenshots for visible UI changes.

[garden/DNA.md](garden/DNA.md) holds the rules a change must keep (what the project is for, what counts as evidence, how it grows, what it refuses); [AGENTS.md](AGENTS.md) has the code layout and the build.
