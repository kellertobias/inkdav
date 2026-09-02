# Releasing InkDAV

GitHub is authoritative for `main`, tags, CI, and hosted releases. Tags use `vMAJOR.MINOR.PATCH`. `feat:` creates a minor release, `fix:`/`perf:` create a patch, and `!` or a `BREAKING CHANGE:` footer creates a major release. Documentation, tests, refactors, build, CI, and chores do not release by default.

The Android version in `app/build.gradle.kts` is authoritative. `scripts/set-version.mjs` maps SemVer to a monotonically increasing Android `versionCode`. A successful push-triggered Android CI run is the release gate: it must pass formatting, JVM tests, Android lint, debug and minified release builds, Android 15 emulator tests, CodeQL, and BOOX APK compatibility validation. Only then does the release transaction update the version and `CHANGELOG.md`, build the minified signed APK, commit `chore(release): vVERSION [skip ci]`, tag it, and upload the exact APK and checksum to a GitHub Release.

The published `InkDAV-vVERSION-boox-note-air5c.apk` is an installable universal APK containing ARM64 native libraries. The Note Air5 C runs Android 15; InkDAV supports Android 8 through Android 16 (`minSdk 26`, `targetSdk 36`). CI verifies the package ID, SDK levels, ARM64 libraries, zip alignment, and release signature before publication.

## One-time signing setup

Create and back up a dedicated PKCS#12 Android upload keystore outside the repository:

```sh
keytool -genkeypair -v -storetype PKCS12 -keystore inkdav-release.p12 -alias inkdav -keyalg RSA -keysize 4096 -validity 10000
base64 < inkdav-release.p12 | tr -d '\n' | gh secret set INKDAV_SIGNING_KEY_BASE64
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

The production archive script deliberately refuses to run without signing variables. Ordinary CI still runs `assembleRelease` unsigned and validates that it is compatible with the BOOX tablet. The trusted release workflow is triggered only after the complete Android CI workflow succeeds for a push to `main` while the repository variable `RELEASES_ENABLED` is `true`.
