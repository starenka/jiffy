# AGENTS.md

Guidance for AI coding agents (and humans) working on jiffy: an Android app that shares selected folders over key-authenticated SFTP. See `README.md` for what it does and `docs/RELEASING.md` for the release process.

## Policies

- **Never push unless explicitly asked.** This covers `git push` (branches and tags), force-pushes, and anything else that publishes to the remote (creating or editing GitHub releases, setting secrets). Commit locally and stop. An earlier approval to push does not carry over to later changes.
- **Never commit secrets or machine-specific files**: `.keystore`, `*.jks`, `.envrc`, `local.properties`, `.issues/`. They are git-ignored; keep it that way.
- **Keep `CHANGELOG.md` current.** Every user-visible change (feature, fix, behaviour or security change, notable dependency bump) gets a line under `## [Unreleased]` in the same commit. Use the Added / Changed / Fixed / Removed / Security headings. Pure refactors, docs-only and CI-only changes need no entry.
- Keep the README accurate for newcomers; update it when behaviour, commands or requirements change.

## Operations

All commands run from the repo root. `just` lists everything, grouped. Gradle 8.13 can't run on JDK 25: set `ANDROID_JAVA_HOME` (e.g. in a git-ignored `.envrc`) to a JDK 17/21. `just` maps it to `JAVA_HOME`; when calling `./gradlew` directly, set `JAVA_HOME` yourself.

| Task | Command |
|---|---|
| Build debug APK | `just build` |
| Unit tests | `just test` |
| Lint | `just lint` |
| Pair / connect wireless adb | `just pair IP:PAIRPORT`, `just connect IP:PORT` (or use USB) |
| Build, install, grant permissions, launch | `just deploy` |
| Logs / service errors | `just logs`, `just errors` |
| Smoke-test the running share | `just smoke` (needs sharing on, phone on the same LAN) |
| Mount / unmount shares | `just mount`, `just umount` |

CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest assembleDebug` on pushes to `master` and on pull requests. Run `just test` before committing.

When testing on a real phone: add the phone's host key to `~/.ssh/known_hosts` only after comparing its fingerprint with the one shown in the app, and stop sharing when done. Don't capture screenshots with the notification shade open.

## Releasing

Full details are in `docs/RELEASING.md`. Summary:

1. Move the `## [Unreleased]` entries in `CHANGELOG.md` under a new `## [X.Y.Z] - YYYY-MM-DD` heading (leave an empty `[Unreleased]` above it) and commit.
2. When the user asks for a release: push `master`, then tag and push the tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. The Release workflow (`.github/workflows/release.yml`) runs the tests, builds and signs the APK, and creates the GitHub Release automatically. The release notes are the matching `CHANGELOG.md` section; the run fails if that section is missing.
4. Verify: `gh run watch`, then download the APK and check it with `apksigner verify --print-certs`.

`versionName` comes from the tag and `versionCode` is `major*10000 + minor*100 + patch`, so versions must only go up. Never reuse a tag; if a release fails, follow the redo steps in `docs/RELEASING.md`.
