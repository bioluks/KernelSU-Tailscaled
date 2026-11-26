# f-droid.org submission

`com.askingmeric.tailmate.yml` is the recipe for
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) (`metadata/`);
`MERGE_REQUEST.md` is the merge request text. Store texts and the icon come from
`fastlane/metadata/android/` in this repository.

## Same signature everywhere

The recipe sets `Binaries` and `AllowedAPKSigningKeys`. F-Droid builds the
unsigned APK from source, copies the signature from the GitHub release APK onto
it, and publishes **the developer-signed APK** only if that verifies. GitHub,
Obtainium, the bioluks/execd F-Droid channels and f-droid.org therefore ship the
same certificate (`0924bb04…626ae3`) and users can switch without reinstalling.

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

Verified (2026-11-25), all with the official
`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` image:

- A signed build from one checkout and an independent `--unsigned` build from a
  different path pass `apksigcopier compare`.
- `fdroid lint` and `fdroid rewritemeta` are clean in a current fdroiddata checkout.
- `fdroid build` of this recipe against the published release built the APK,
  deleted `files/`, compared it with the `Binaries` APK ("compared built binary
  to supplied reference binary successfully") and accepted the allowed signer.
  The build server image ships only JDK 21, hence the `sudo` install of
  `openjdk-25-jdk-headless`.

## Submitting

1. Sign in at https://gitlab.com and fork https://gitlab.com/fdroid/fdroiddata
   (keep the fork public).
2. In the fork, create a branch (e.g. `tailmate`), add
   `metadata/com.askingmeric.tailmate.yml` with exactly this directory's recipe,
   commit, and push.
3. Open a merge request into `fdroid/fdroiddata:master` titled
   `New app: TailMate`, with the text from `MERGE_REQUEST.md`. Attach 2–4
   screenshots (main screen, share sheet, settings).
4. Wait for the pipeline. If GitLab asks for a phone number or card to run CI,
   don't provide one; leave a note in the MR instead (F-Droid triggers it).

Later releases need no recipe edits: `AutoUpdateMode`/`UpdateCheckMode` pick up
new `v*_tailscale*` tags and read `versionCode`/`version` from `module.prop`.
