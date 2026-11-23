# Releasing jiffy

How signed APKs are built and published. Releases are produced by GitHub Actions (`.github/workflows/release.yml`) when a version tag is pushed.

## How it works

1. You push a tag such as `v1.2.3`.
2. The workflow decodes the keystore from repository secrets, runs the unit tests and builds `assembleRelease`, signed with your key.
3. The version is taken from the tag: `versionName` = `1.2.3`, `versionCode` = `major*10000 + minor*100 + patch` (here `10203`). The code must increase with every release, so never reuse or lower a version.
4. The APK is attached as `jiffy-v1.2.3.apk` to a new GitHub Release with generated notes.

Android only installs an update over an existing install if it is signed with the same key and has a higher `versionCode`. That is why the keystore matters.

## One-time setup

### 1. Create the keystore

```sh
keytool -genkeypair -keystore .keystore -storetype PKCS12 \
  -alias jiffy -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=jiffy, O=<your name>"
```

Use a strong password (the key password equals the store password with PKCS12). `.keystore` and `*.jks` are in `.gitignore`; never commit them.

**Back up the keystore and its password** (password manager or encrypted drive). If you lose them, nobody can update an installed release in place; users would have to uninstall first, which wipes their host key and saved keys.

### 2. Local signing (optional)

To build signed release APKs on your machine, export these (for example in a git-ignored `.envrc` for direnv):

```sh
export JIFFY_KEYSTORE=$PWD/.keystore
export JIFFY_KEYSTORE_PASSWORD=...
export JIFFY_KEY_ALIAS=jiffy
export JIFFY_KEY_PASSWORD=...
```

Then `./gradlew assembleRelease` (with a JDK 17/21, see the main README). Without these variables the release build is unsigned. `VERSION_NAME` and `VERSION_CODE` override the version if set.

Check the result with `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` (from the Android SDK build-tools).

### 3. Repository secrets

The workflow reads four secrets. Set them with the GitHub CLI (it never echoes the values):

```sh
gh secret set JIFFY_KEYSTORE_BASE64 < <(base64 -w0 .keystore)
gh secret set JIFFY_KEYSTORE_PASSWORD   # paste the store password
gh secret set JIFFY_KEY_PASSWORD        # paste the key password
gh secret set JIFFY_KEY_ALIAS --body jiffy
gh secret list                          # confirm all four exist
```

Rotate or update a secret by running the same command again.

## Publishing a release

```sh
git tag v1.0.0
git push origin v1.0.0
gh run watch                 # follow the Release workflow
gh release view v1.0.0       # confirm the APK is attached
```

If the workflow fails, fix the problem, delete the tag and release, and tag again:

```sh
gh release delete v1.0.0 --yes --cleanup-tag
git tag -d v1.0.0
```

## Security notes

- The keystore and passwords live in GitHub repository secrets. Anyone who can edit workflows or push to a branch with workflow access could exfiltrate them, so keep the repository's write access tight. Secrets are not exposed to workflows triggered from forks' pull requests.
- The release workflow only runs on `v*` tags, and only the signing step sees the secrets.
- Verify a downloaded APK against the certificate fingerprint you expect: `apksigner verify --print-certs jiffy-v1.0.0.apk`. Publish that fingerprint (SHA-256) somewhere users can compare it.
- F-Droid builds from source and signs with its own key, so it is independent of this process.
