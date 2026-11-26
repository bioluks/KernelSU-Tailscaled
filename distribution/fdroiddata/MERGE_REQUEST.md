Title: `New app: TailMate`

<!-- Paste everything below this line into the merge request description. -->

## Abstract for new app inclusion

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

TailMate is the companion app for the TailMate root module (KernelSU,
KernelSU-Next, Magisk, APatch). The module runs a Tailscale-compatible daemon
natively on rooted Android over a real TUN interface, **without occupying
Android's VPN slot**, so it works alongside an always-on VPN. It supports
Tailscale and self-hosted Headscale.

The app shows module status, starts/stops/restarts the daemon, edits module
settings, shows logs, opens the local panel, sends files with Taildrop from the
Android share sheet (progress, speed, cancel, optional confirmation), notifies on
received files, and keeps an optional, clearable transfer history. English and
Simplified Chinese.

Screenshots: <!-- attach 2–4 phone screenshots (main screen, share sheet, settings) -->

### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

The official Tailscale app and TailSocks use Android's VPN slot (or a proxy),
which conflicts with an always-on VPN. TailMate's daemon runs as a root module
with native TUN routing outside the VPN slot; the app is its control surface and
Taildrop integration. Unlike earlier forks of this module
(anasfanani/magisk-tailscaled → ryukora → WayneShao), it adds this companion,
Taildrop, Headscale sign-in, an English UI and much lower idle CPU use.

## Checklist

### Policy

* [x] The app complies with the inclusion policy. It needs root plus the
  separately installed module (`RequiresRoot: true`, stated in the description);
  the module is open source in the same repository.
* [x] The original app author has been notified: I am the author.
* [x] Fastlane metadata in `fastlane/metadata/android/` (en-US and zh-CN:
  title, summary, description, icon, changelog).

### Docs

* [x] Read the contribution guide, templates, Build Metadata Reference and Quick Start Guide.

### Merge Request Setup

* [x] Title "New app: TailMate".
* [x] Fork public, branch not protected.
* [x] No related issues.
* [x] One app.

### Metadata

* [x] `metadata/com.askingmeric.tailmate.yml`, valid YAML, LF, passes `fdroid lint` and `fdroid rewritemeta`.
* [x] No store texts/images in the recipe.
* [x] Releases are tagged; auto update enabled (`UpdateCheckData` reads `module.prop`).
* [x] Issue tracker and AuthorName present.
* [x] No srclibs or submodules: plain Java, built by `android/build.py` with the SDK and a JDK only (no Gradle, no dependencies).
* [x] **Reproducible build with developer signature** (`Binaries`, `AllowedAPKSigningKeys`).
  Verified with `fdroid build` in the buildserver-trixie image: the source build
  matches the published 4.0.0 APK and the signer is allowed. Release builds pin
  Build Tools 36.0.0 + JDK 25 and are signed with apksigner 34 (avoids the
  apksigner ≥ 35 signature-copy issue).
* [x] No ABI split needed (one ~150 KB APK, no native code).
* [x] Only the latest version, not disabled, full commit hash.
* `scandelete: files` removes the module's prebuilt binaries (Tailscale engine
  is built separately; hev-socks5-tunnel upstream assets). The APK does not use
  them. The build server image ships only JDK 21, hence `sudo` installs
  `openjdk-25-jdk-headless`.

### Pipeline

* [ ] All pipelines should pass.
* [ ] All warnings and errors in the Reports tab should be fixed or explained.
