# Releasing VBoard

A release is produced by pushing a tag. `.github/workflows/release.yml` builds a
**signed** APK and attaches it to a GitHub Release; `v*-alpha*`, `v*-beta*` and
`v*-rc*` tags are marked pre-release automatically.

An unsigned APK cannot be installed on Android, so the workflow fails loudly
rather than publishing one. That is deliberate: a release asset nobody can
install is worse than no release.

## One-time setup

### 1. Generate the signing key

Do this once, on a machine you trust, and **do not commit the result**:

```
keytool -genkey -v -keystore vboard-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias vboard
```

**Back up `vboard-release.jks` and its passwords somewhere durable and offline.**
Android identifies an installed app by its signing key. If this key is lost,
every existing install becomes un-upgradable: users must uninstall and lose their
data, and the app can never be published under the same identity again. This is
the one artifact in the project with no recovery path.

### 2. Store it as repository secrets

```
base64 -i vboard-release.jks | gh secret set VBOARD_KEYSTORE_BASE64
gh secret set VBOARD_KEYSTORE_PASSWORD
gh secret set VBOARD_KEY_ALIAS
gh secret set VBOARD_KEY_PASSWORD
```

The alias is `vboard` if you used the `keytool` command above. `gh secret set`
prompts for the value rather than taking it as an argument, which keeps the
passwords out of your shell history.

## Cutting a release

```
git switch main
git pull
git tag v1.0.0-alpha.1
```

Then publish the tag, which is what triggers the workflow:

```
git push origin v1.0.0-alpha.1
```

Watch it with `gh run watch`. The release appears at
`https://github.com/nvkudva/VBoard/releases`.

To rebuild an existing tag without moving it, run the workflow manually:
`gh workflow run release.yml -f tag=v1.0.0-alpha.1`.

## How versioning works

- `versionName` comes from the tag with the leading `v` stripped —
  `v1.0.0-alpha.1` becomes `1.0.0-alpha.1`.
- `versionCode` is the workflow's `github.run_number`. It only has to increase,
  not to encode the version, and Android refuses to install an upgrade whose code
  is not greater than the installed one.

Both are read from the environment in `app/build.gradle.kts` and fall back to
`1` / `1.0.0` for local builds, so a plain `./gradlew :app:assembleRelease` on a
developer machine still works and simply produces an unsigned APK.

## What users have to do to install it

The APK is signed by us, not by Google Play, so Android treats it as an unknown
source. Installing requires enabling "install unknown apps" for the browser or
file manager doing the install. After installing, VBoard still has to be
**enabled** in Settings → System → Languages & input → On-screen keyboard, and
then **selected** as the active keyboard — installing alone does not make it
usable. The in-app onboarding flow walks through both steps.

## Known limitation

The APK is `arm64-v8a` only (see the `abiFilters` comment in
`app/build.gradle.kts`). It will not install on a 32-bit device or on an x86_64
emulator.
