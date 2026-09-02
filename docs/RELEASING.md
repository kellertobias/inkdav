# Releasing InkDAV

GitHub is authoritative for `main`, tags, CI, and hosted releases. Tags use `vMAJOR.MINOR.PATCH`. `feat:` creates a minor release, `fix:`/`perf:` create a patch, and `!` or a `BREAKING CHANGE:` footer creates a major release. Documentation, tests, refactors, build, CI, and chores do not release by default.

The Android version in `app/build.gradle.kts` is authoritative. `scripts/set-version.mjs` maps SemVer to a monotonically increasing Android `versionCode`. The release transaction updates that file and `CHANGELOG.md`, builds the minified signed APK, commits `chore(release): vVERSION [skip ci]`, tags it, and uploads the exact APK and checksum to a GitHub Release.

## One-time signing setup

Create and back up a dedicated Android upload keystore outside the repository:

```sh
keytool -genkeypair -v -keystore inkdav-release.jks -alias inkdav -keyalg RSA -keysize 4096 -validity 10000
base64 < inkdav-release.jks | tr -d '\n' | gh secret set INKDAV_SIGNING_KEY_BASE64
gh secret set INKDAV_KEYSTORE_PASSWORD
gh secret set INKDAV_KEY_ALIAS
gh secret set INKDAV_KEY_PASSWORD
gh variable set RELEASES_ENABLED --body true
```

Store the keystore and passwords in a separate offline backup. Do not enable releases until all four secrets exist. GitHub's ephemeral repository token needs permission to create release commits, tags, and releases; branch or tag protection may require an explicitly approved narrow release credential instead.

## Verify without publishing

Install Node 24 dependencies and preview the next release from complete Git history:

```sh
npm ci
GH_TOKEN=$(gh auth token) npm run release:dry
```

The production archive script deliberately refuses to run without signing variables. Ordinary CI still runs `assembleRelease` unsigned to validate R8 and resources. A real release is triggered only by a trusted push to `main` while the repository variable `RELEASES_ENABLED` is `true`.
