# f-droid.org submission

`com.askingmeric.tailmate.yml` is the draft recipe for
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) (`metadata/`). Store texts
and the icon come from `fastlane/metadata/android/` in this repository.

## Same signature everywhere

The recipe sets `Binaries` and `AllowedAPKSigningKeys`. F-Droid builds the
unsigned APK from source, copies the signature from the GitHub release APK onto
it, and publishes **the developer-signed APK** only if that verifies. GitHub,
Obtainium and f-droid.org therefore ship the same certificate
(`0924bb04…626ae3`) and users can switch between them without reinstalling.

This only works while the build is byte-for-byte reproducible:

- `android/build.py` refuses release/unsigned builds unless they use Build Tools
  36.0.0, platform `android-36` and JDK 25 (the vendor does not matter: Debian's
  OpenJDK 25 and Temurin 25 produce identical bytes).
- Releases are signed with the `apksigner` from Build Tools **34.0.0**. Newer
  `apksigner` versions produce signatures that F-Droid's `apksigcopier` cannot
  copy (see the "apksigner from build-tools >= 35" note in
  https://f-droid.org/docs/Reproducible_Builds/). The published 3.6.0 APK was
  signed with 36.0.0 and fails this check; 4.0.0 is the first release that
  f-droid.org can publish with the developer signature.
- On tags, CI runs `apksigcopier compare` against the release APK it just built,
  so a toolchain change that breaks this fails the release instead of the
  F-Droid build.

Verified locally (2026-11-25):

- In a Debian trixie container that mirrors the build server, a signed build
  from one checkout and an independent `--unsigned` build from a different path
  pass `apksigcopier compare`.
- With the official `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`
  image and fdroidserver 2.4.2, `fdroid build` of this recipe (with `Repo`
  pointed at a local checkout) succeeds, deletes `files/`, and produces
  `unsigned/com.askingmeric.tailmate_40000000.apk` (SHA-256 `f34fff90…a3b849`),
  byte-identical to the Temurin 25 build. The build server image ships only
  JDK 21, hence the `sudo` install of `openjdk-25-jdk-headless`.

## Submitting

1. Publish the 4.0.0 release (tag `v4.0.0_tailscale1.104.7`) first; the recipe
   pins that tag and downloads the APK from it.
2. Fork fdroiddata, copy the recipe to `metadata/com.askingmeric.tailmate.yml`,
   run `fdroid lint com.askingmeric.tailmate` and
   `fdroid build -v -l com.askingmeric.tailmate`, then open a merge request.
3. Mention in the MR that the app needs root and the separately installed
   TailMate module (`RequiresRoot: true` and the description say so), and that
   `files/` is deleted before the build because it holds prebuilt module
   binaries that the APK does not use.

Later releases need no recipe edits: `AutoUpdateMode`/`UpdateCheckMode` pick up
new `v*_tailscale*` tags and read `versionCode`/`version` from `module.prop`.
