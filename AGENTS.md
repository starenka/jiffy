# AGENTS.md

Guidance for AI coding agents (and humans) working on jiffy: an Android app that shares selected folders over key-authenticated SFTP. See `README.md` for what it does and `docs/RELEASING.md` for the release process.

## Policies

- **Never push unless explicitly asked.** This covers `git push` (branches and tags), force-pushes, and anything else that publishes to the remote (creating or editing GitHub releases, setting secrets). Commit locally and stop. An earlier approval to push does not carry over to later changes.
- **Never modify, delete, move, rename, overwrite or create any shared file on the phone**, over SFTP/scp/sshfs or over adb (no `adb push`, and no `rm`/`mv`/`cp`/`touch`/redirects via `adb shell` on storage; `adb pull` and read-only listing are fine). That includes everything under the shared folders and the rest of `/sdcard`. Test with read-only operations only (list, read, `stat`, download). There are no exceptions: `just smoke` is read-only (it only lists and downloads), so use it and don't hand-write write tests. `just mount` is read-write by default, so agents must mount read-only: `SSHFS_OPTS=ro just mount`. Don't clear app data (`just reset`) or uninstall (`just uninstall`) without being asked, since that deletes the host key and saved keys.
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
| Read-only smoke test of the running share | `just smoke` (needs sharing on, phone on the same LAN) |
| Mount / unmount shares | `just mount`, `just umount` |

CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest assembleDebug` on pushes to `master` and on pull requests. Run `just test` before committing.

When testing on a real phone: add the phone's host key to `~/.ssh/known_hosts` only after comparing its fingerprint with the one shown in the app, and stop sharing when done. Don't capture screenshots with the notification shade open.

## Releasing

Use `just release <X.Y.Z|patch|minor|major> [--dry] [--no-verify]` (`scripts/release`). Full details are in `docs/RELEASING.md`. It checks that you're on a clean, up-to-date `master` with a non-empty `[Unreleased]` changelog section and passing tests, moves that section under the new version, commits "Release vX.Y.Z", asks for confirmation on the terminal, then tags and pushes `master` and the tag. The Release workflow (`.github/workflows/release.yml`) builds and signs the APK and creates the GitHub Release with the matching `CHANGELOG.md` section as notes; the script watches the run and verifies the APK signature.

- **Only run `just release` when the user explicitly asks for a release.** Use `--dry` first. The confirmation prompt needs a real terminal; never work around it (no piping `y`, no pty tricks, no editing the script to skip it). If you can't confirm interactively, give the user the command to run themselves.
- `versionName` comes from the tag and `versionCode` is `major*10000 + minor*100 + patch`, so versions must only go up. Never reuse a tag; if a release fails, follow the redo steps in `docs/RELEASING.md`.
