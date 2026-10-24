# Building and releasing

GitHub Actions builds every APK; nothing needs to be built on a computer.

## Signing key

Every APK must be signed with the same key, or Android refuses to install it over an earlier
version. The key is a keystore file kept by the maintainer, outside this repository. CI reads it
from two repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
| --- | --- |
| `GLINT_KEYSTORE_BASE64` | The keystore file, encoded with `base64 -w0` |
| `GLINT_KEYSTORE_PASSWORD` | Its password |

The key was created when the app was called Xlight, so its alias inside the keystore is `xlight`.

Keep a backup of the keystore and its password somewhere safe. If they are lost, no new version
can ever install over the existing ones, and every user would have to uninstall first.

## Test builds

Actions tab > **Build preview** > Run workflow, on any branch. It runs the tests and lint, then
publishes the APKs as a pre-release named `preview-<branch>`. Their version ends in `-preview`.

## Releases

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Write the release notes in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
   (under 500 characters).
3. Commit to `main`, then open the Actions tab, choose **Release** and press **Run workflow** on
   `main`. Pushing a tag that matches the version (`git tag v0.9.0 && git push origin v0.9.0`)
   starts it too.

The **Release** workflow checks the version (and that it is not released yet), builds and tests
the APKs, and publishes a release tagged `v<versionName>` with one APK per processor type, the
notes, and the signing certificate's fingerprint. It refuses to publish without the signing key.

## gallery-dl

The gallery-dl a fresh install starts with lives in `app/src/main/assets/gallery-dl`: its wheel,
the pure-Python packages it needs, a launcher and its version. Run `tools/update-gallery-dl.sh` to
refresh them from PyPI. Installed apps also update gallery-dl from PyPI on their own, alongside
yt-dlp.

## Store listing

`fastlane/metadata/android/` holds the title, descriptions, icon, screenshots and changelogs
in the layout F-Droid, IzzyOnDroid and Obtainium read. Screenshots are 720 × 1494 and carry
no EXIF data.
