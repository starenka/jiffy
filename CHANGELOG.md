# Changelog

All notable changes to jiffy. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow `vMAJOR.MINOR.PATCH` tags.

Release notes on GitHub are taken from the matching section below, so every release needs an entry here.

## [Unreleased]

## [1.1.0] - 2026-11-23

### Added
- Add your own shared directories in the app (*Add directory*: a name and an absolute path). They appear as `/Name` next to the built-in shares and can be enabled, edited and removed; removing one never deletes files.

### Changed
- The built-in shares are listed as Photos, Music, Documents, Downloads (Photos and Music swapped).
- `just smoke` is now read-only: it lists the shares and downloads a small file over sftp and scp instead of uploading a temp file to `/Music`.

## [1.0.0] - 2026-11-23

First public release.

### Added
- Key-authenticated SFTP server (Apache MINA SSHD) on the phone's Wi-Fi IPv4 address, port 2222. No shell, no exec, no password login.
- Start/Stop sharing button with live upload/download rate, run as a foreground service with wake and Wi-Fi locks.
- Authorized keys managed in the app: import from a GitHub handle or paste public keys.
- Persistent host key with fingerprint shown in the app, plus a build line (git revision and time).
- Read-only virtual root exposing only enabled shares: `/Music`, `/Photos`, `/Documents`, `/Downloads`, each with a configurable directory.
- mDNS advertisement as `jiffy-<id>` (`_ssh._tcp`) while sharing, so computers can find the phone without its IP.
- `just` helpers for building, deploying, debugging and using the share (`sftp`, `smoke`, `mount`, `umount`).
- Signed release APKs published from GitHub Actions on version tags; CI runs unit tests and a debug build.

### Security
- Shares are confined to their directories: path traversal and symlink escapes are blocked.
