# Publication acceptance

> The project was renamed to TailMate in 4.0.0. Entries for earlier versions
> refer to the old name KernelSU-Tailscaled, its module id
> `kernelsu-tailscaled`, state directory `/data/adb/kernelsu-tailscaled`, and
> `KernelSU-Tailscaled-*` artifact names; they are kept unchanged as evidence.

Both prereleases and stable releases require the maintainer's explicit acceptance
of the exact tested version before publication. Automated tests, a successful
installation, and an immediate ping do not replace that acceptance.

Before requesting acceptance:

1. Build and verify the complete ZIP; record its SHA-256.
2. Install that artifact and verify identity/configuration preservation.
3. With explicit reboot authorization, verify boot activation without manually
   starting the service afterward. Observe sustained service health and check
   WebUI and peer connectivity.
4. Present actual evidence and unresolved limitations to the maintainer.
5. Publish only after the maintainer confirms their testing passed for that
   version. Tag pushes trigger publication and are subject to the same gate.

The GitHub source repository and KernelSU store have separate distribution
steps, but this acceptance requirement applies to both, including beta releases.

On 2026-10-22, v2.0.5-beta.1 was withdrawn at the maintainer's request. Its Release
and local/remote tag were removed. beta.2 and beta.3 are local test versions;
neither has been approved for publication.

## v2.0.5 authorization (2026-10-22)

The maintainer explicitly authorized publication after the requested real Xiaomi
old-engine-to-new-engine upgrade test passes, followed by an Issue #1 mention.
This supersedes the earlier requirement to wait for a separate manual acceptance
message for this release only. The test passed with identity/config preservation
and connectivity verified. Final ZIP checks and device installation remain part
of the release gate. No additional reboot or store synchronization is implied.

## v3.1.0 authorization (2026-11-10)

The maintainer explicitly requested separate-branch development, ADB verification,
merging into `all-features-merged`, and an appropriately versioned new release for
the companion share-sheet, Settings and transfer-notification improvements.
This authorizes publication after those checks; no additional manual-acceptance
message, Android reboot or store synchronization was requested. ARM64 hot-upgrade
verification preserved the node identity, IPs and exact Tailscale preferences.
Boot activation was not re-tested for this companion-focused release.

## v3.2.0 authorization (2026-11-17)

After reviewing the compatibility implementation and F1 results, the maintainer
explicitly requested publishing the separate compatibility branch, merging its
fixes into `all-features-merged`, and generating a new appropriately versioned
release. This authorizes GitHub publication after CI and exact stable artifact
verification; no additional acceptance prompt is required. Google Play and
KernelSU store publication are not authorized by this request.

The v3.1.1-dev.3 ARM64 candidate passed native LAN Headscale registration, direct
UDP, hash-matched transfers, preference/identity continuity and an explicitly
authorized boot test. v3.2.0 promotes the same engine/runtime/app source with
release metadata and documentation changes. The candidate evidence and remaining
limits are in `docs/verification/2026-11-17-f1-native-compatibility.md`.

## v3.3.0 authorization (2026-11-20)

The maintainer explicitly requested separate-branch development for the live
Tailscale DNS on/off switch in the WebUI, merging into `all-features-merged`, and
publishing a new release highlighting the added Tailscale DNS switch.
This authorizes GitHub publication after CI passes and exact stable artifacts
are verified; no additional manual acceptance prompt is required.
Direct hardware verification on OnePlus 9 Pro (LE2123) over USB ADB verified live
toggling via `control.sh enable-dns`, `control.sh disable-dns` and `control.sh toggle-dns`
and preference persistence across daemon restarts. Full test suites (Vitest, Playwright,
Python runtime behavior) passed clean.

## v3.3.1 upgrade verification record

The maintainer reported an in-place KernelSU Manager upgrade failure
(`current-stop-failed` / retained supervisor process record). A shell probe
demonstrated foreground `mksh` trap deferral, but did not establish the cause
of the retained record. v3.3.1 made the supervisor's idle sleep interruptible,
increased stop timeouts, and added bounded exit verification and a stop retry.
CI passed. A successful Manager upgrade with the exact v3.3.1 ZIP and separate
maintainer acceptance of that artifact were not recorded.

## Local no-reboot test candidates (2026-11-22)

The ARM64 `3.3.2-test.1` ZIP (SHA-256
`0bf06fccf103495b8c3601ae1a87449d42c5995d9b5ea15cbb65854eae707587`)
flashed successfully through KernelSU-Next Manager on the OnePlus 9 Pro
(v3.4.2 Manager, from module v3.3.0) and Poco F1 (v1.1.1 Manager, from module
v3.1.1-dev.3). Both phones also upgraded from `test.1` to the ARM64 `test.2` ZIP
with revised WebUI wording (SHA-256
`604d1d9bcfecab637421141720e42d04a5b424467984c37114330c2e97297831`).
Each upgrade preserved the Android boot ID, saved state, configuration, and
projected preferences. The selected runtime and installed WebUI matched the
candidate ZIP; the supervisor and daemon ran after installation.

Manager still offered Reboot and staging remained pending. Neither device had a
tailnet address before or after the test: the Poco needed login; the OnePlus's
Headscale control endpoint refused connections. Peer connectivity, deferred
staging cleanup, and post-reboot activation were not verified. These local test
builds are not approved for publication.
The test.2 runtime uses version code `30030003`. An in-place upgrade on these
two devices must use a higher code; different bytes at the same code are rejected.

## v3.3.2 stable verification

Version code `30030004` advances v3.3.1 and both local test candidates. All
three ZIPs passed package verification and matched the Linux rebuild of the
committed source byte for byte.

| ZIP | SHA-256 |
| --- | --- |
| `KernelSU-Tailscaled-v3.3.2.zip` | `37116543aab083d9e2d650aa51caca3ba3ea44a7c9adcd9c313e7b8e39a81b7e` |
| `KernelSU-Tailscaled-arm-v3.3.2.zip` | `4bd19343c58045f9ce0832cbf5edf12ecdd895abd8a565afa12eacbdb6a3a75e` |
| `KernelSU-Tailscaled-arm64-v3.3.2.zip` | `d99771926f074f793bb995c7d0c5bcfd5bbca916171732c9e76840a9d4e3d1ea` |

KernelSU-Next Manager flashed the exact universal ZIP on the Poco F1 and the
exact ARM64 ZIP on the OnePlus 9 Pro, both upgrading from `3.3.2-test.2`.
Both Managers reported Flash success. The running bundles and installed
WebUI matched their ZIP manifests. The boot IDs, saved state, configuration,
and projected preferences were unchanged; both supervisors and daemons ran.
Both Manager WebUIs opened and reported v3.3.2 as the current runtime and as
the staged version.

With explicit maintainer reboot authorization, both devices were rebooted and
verified:

- **Poco F1:** Boot ID changed to `5d614962-5778-423f-8879-70da50bfff3f`.
  Manager promoted the module (`version=3.3.2`, `versionCode=30030004`) and
  cleared staging (`stagedVersion=null`). Tailscale supervisor and daemon started
  automatically on boot with `activeVersion=3.3.2`. State hash remained
  `79c16abe739060e957e3254c9d068428a30d4dcd4d8ef4f642ed7d7e4644845f`.
- **OnePlus 9 Pro:** Boot ID changed to `35fe6a03-c2d3-4095-b98a-8367a604a87c`.
  Manager promoted the module (`version=3.3.2`, `versionCode=30030004`) and
  cleared staging (`stagedVersion=null`). Tailscale supervisor and daemon started
  automatically on boot with `activeVersion=3.3.2`. State hash remained
  `851301181283ac49d7b05675bf6787649afd45bff430ef2fa7fa23d515502c06`.

Neither phone had a tailnet IP (Poco needed login; OnePlus Headscale endpoint
refused connections), so peer connectivity was not verified. The ARMv7 ZIP was
verified offline only. All other release acceptance criteria were met.

## v3.4.0_tailscale1.104.7 stable verification

The engine is rebuilt from pinned upstream Tailscale v1.104.7 source
(`cbc74fe48ef1b1649f69a4d9a602319427e25102`, archive SHA-256
`fa1c922b33d0f6db6b073800d7a62fa84081323d247b21547c63a98ed7ecac14`) with the
same two reviewed patches applied unchanged, using go1.27.4. Both
architectures built twice in the pinned container are byte-identical, and the
engine regression packages pass. `files/manifest.sha256` verifies.

| ZIP | SHA-256 |
| --- | --- |
| `KernelSU-Tailscaled-v3.4.0_tailscale1.104.7.zip` | `fbd3889591a41314342230b1ac5b5774167e6c5e1d59666aad54bf9ca96442cc` |
| `KernelSU-Tailscaled-arm-v3.4.0_tailscale1.104.7.zip` | `c6136af81d272647eec598b54b06f1417e7562b1602512be263f5278295639dc` |
| `KernelSU-Tailscaled-arm64-v3.4.0_tailscale1.104.7.zip` | `ff28bc847b1a1e6b7599504c00150620f4e04ff5be20f2d455ab40aadc1a66cd` |

KernelSU-Next Manager flashed the exact ARM64 ZIP on the OnePlus 9 Pro and the
exact universal ZIP on the Poco F1, both upgrading from v3.3.2. Both Managers
reported Flash success; the running bundles, installed WebUI, and companion
status all report `3.4.0_tailscale1.104.7` with Tailscale 1.104.7.

With explicit reboot authorization both phones rebooted and KernelSU promoted
the module (`versionCode=30040000`), cleared staging, and auto-started the
supervisor, daemon, and WebUI. `tailscaled.state` and `config/module.conf`
hashes are unchanged from the pre-flash snapshot on both phones (only the boot
IDs changed: OnePlus `db4b0e84-2b2c-4fcf-a675-80c2d130a439`, Poco
`7519d51e-cc21-45d1-84ac-e8f106eb3f47`); projected preferences are unchanged.

Tailnet peer connectivity was not exercised: the maintainer's Headscale server
was down during the window, so the OnePlus could not reach its control
endpoint, and the Poco still needs login. The redesigned companion landing,
settings, and send screens were verified on both phones by UI dump and
screenshot. The ARMv7 ZIP was verified offline only.

## v3.5.0_tailscale1.104.7 — opt-in system-wide DNS verification

Enabling redirects Android's port-53 queries to the tailnet nameservers
delivered by the coordination server and source-NATs them to the node's
tailnet address, because tailnet resolvers ignore carrier CGNAT sources.

| ZIP | SHA-256 |
| --- | --- |
| `KernelSU-Tailscaled-v3.5.0_tailscale1.104.7.zip` | `4a1df93e51775106d6f423a9786ad970320372a3eab0a2caeba0d90131bb70cd` |
| `KernelSU-Tailscaled-arm-v3.5.0_tailscale1.104.7.zip` | `22a301327c3c1a993489b7073d1fd040d65e4ce01f62890deb89cbd1a3f29896` |
| `KernelSU-Tailscaled-arm64-v3.5.0_tailscale1.104.7.zip` | `8d0ecc6e47f9da61ef4f7101b80347bc68b08063306c79d908e4c5df6e0d4612` |

Device evidence (OnePlus 9 Pro, Android 16, mobile data and Wi-Fi):

- `tcpdump` captures show every Android resolver query leaving via
  `tailscale0` from `100.64.0.2` to the primary resolver `100.64.0.6:53`,
  with answers returning; a fresh public name gets a quick authoritative
  NXDOMAIN, `example.com` resolves, and the MagicDNS name `phone.lan`
  resolves to `100.64.0.2` through Android's own resolver.
- Switching Wi-Fi on and off mid-capture: no query to carrier DNS
  (195.175.x) or the LAN resolver (192.168.1.53) was seen; every answered
  query went to the tailnet resolver. Unanswered `127.0.0.1:53` queries from
  Syncthing are unrelated (no port-53 listener exists on the phone).
- The redirect re-applied automatically after reboot (startup retry plus
  supervisor maintenance) and after re-flashing an upgraded runtime.
- The dormant `TS_DNS_ENFORCE`/`TS_HOTSPOT_DNS` chains are reported as a
  warning; an active foreign port-53 redirect refuses enabling.

Limits: Android Private DNS must stay off; apps with built-in DoH bypass the
redirect; this kernel lacks IPv6 NAT so IPv6 port 53 is rejected to force the
IPv4 path; the Poco F1 has no tailnet (still NeedsLogin) and keeps the feature
off. The ARMv7 ZIP was verified offline only.

## v3.6.0_tailscale1.104.7 — companion settings, in-app panel, and Taildrop history

Version code `30060000` advances v3.5.0_tailscale1.104.7; the bundled engine is
unchanged (Tailscale 1.104.7). Companion APK signing certificate unchanged
(`0924bb045985c248e9ddf9e9139e090aeac72446ae94cf41d8896af5dc626ae3`). The three
ZIPs rebuilt from `git archive` of the tagged commit in the pinned container are
byte-identical to the locally built candidates. The companion APK is identified
by its signing certificate (`0924bb…`), not its bytes: signed APK bytes vary per
signing run, so the hash above is the CI-published artifact's.

| Asset | SHA-256 |
| --- | --- |
| `KernelSU-Tailscaled-v3.6.0_tailscale1.104.7.zip` | `83eed5e3f3f6779ad8403588d08cf0681c8d70af857d92c10303860ffc193f06` |
| `KernelSU-Tailscaled-arm-v3.6.0_tailscale1.104.7.zip` | `3937d6c582e0ea6b835ffa5b140e9574bba4d1edb982f3e27307b08eddc93268` |
| `KernelSU-Tailscaled-arm64-v3.6.0_tailscale1.104.7.zip` | `1ad1dda91b76443e8259aa65c71f359a5a78f61c51a01a6a25dfd9902f80a283` |
| `KernelSU-Tailscaled-companion-v3.6.0_tailscale1.104.7.apk` | `8f057b2d5e028ada851a0170daa5f44afb324bcb7ec6caf64a1b6dad050ebe22` (published build) |

Device evidence (Poco F1 `60b09a5f`, KernelSU-Next Manager 1.1.1, Android 15):

- `ksud module install` flashed the exact universal ZIP (SHA-256 verified on
  device) over v3.5.0. The hot install preserved the boot ID and state hash and
  switched the active runtime to `3.6.0_tailscale1.104.7` with the supervisor,
  daemon, data plane and web panel running.
- The installed manager WebUI and `bundle.sha256` matched the ZIP manifest; the
  loopback panel answered HTTP 200 over a forwarded port.
- With explicit reboot authorization, the device rebooted (boot ID
  `a05cbf52-…` → `28142757-6c01-46aa-920e-484913c39619`). The runtime activated
  automatically without a manual start: `lifecycle=running`, all four
  components running, `activeVersion=3.6.0_tailscale1.104.7`, `stagedVersion`
  cleared. `tailscaled.state` hash was unchanged across install and reboot
  (`322121959c…a354`), and the node identity (ID `12`, `100.64.0.8`,
  `fd7a:115c:a1e0::8`) and `Running` backend state were preserved. Peer
  connectivity to the OnePlus (`100.64.0.2`) succeeded after reboot.
- System-wide DNS re-applied after reboot (`enabled:true, applied:true`).

Limits: the OnePlus 9 Pro (primary device) was not re-flashed or rebooted for
this release; its acceptance is inferred from the Poco and the shared engine.
The ARMv7 ZIP was verified offline only.

## v4.0.0_tailscale1.104.7 — rename to TailMate, predecessor handover

Version code `40000000`. Module id `TailMate`, state `/data/adb/TailMate`,
companion package `com.askingmeric.tailmate`. The engine (Tailscale 1.104.7,
same patches) was rebuilt from source twice in `golang:1.27.4-trixie` with
identical bytes; only the long-version stamp changed (`tm-android-compat-1`).
Engine binaries are no longer committed; `engine/engine-build.json` pins them.
Accepted by the maintainer for publication on 2026-11-25 after the evidence
below; branch CI run 45651588448 passed (engine built from source against the
lock, packages verified and reproducible, unsigned companion built).

| Candidate | SHA-256 |
| --- | --- |
| `TailMate-v4.0.0_tailscale1.104.7.zip` | `e36590ee04c8da170842c09e1fa6b6c57f4b4a9dc7f37396184419990a03cfec` |
| `TailMate-arm-v4.0.0_tailscale1.104.7.zip` | `bcfccaa72d2abc28e9a142aef73be6f16e4c6f68a63ccd85a22aab0779293d26` |
| `TailMate-arm64-v4.0.0_tailscale1.104.7.zip` | `19ba2826327240d20f60b6b8def1ba79bea18a86a0548a1ecdf0ed4219e9e021` |

All three pass `verify-package.py` and rebuild byte-identically. The companion
tested on devices was a debug-signed build; the release APK is signed in CI.

Device evidence, both phones on KernelSU-Tailscaled 3.6.0 before the flash,
universal ZIP installed with `ksud module install`:

- Poco F1 `60b09a5f` (KernelSU-Next 1.1.1, Android 15, kernel 4.9): installer
  printed the handover line; `tailscaled.state` hash identical to the
  predecessor's (`322121959c…`), node `poco` / `100.64.0.8` without re-login;
  pending Taildrop dirs and system-DNS setting copied; the 8 `KST_SYSDNS`
  rules replaced by 8 `TM_SYSDNS` rules; old module flagged `remove`. After an
  authorized reboot the old module was gone, TailMate started on its own, and
  `phone.lan` resolved through MagicDNS.
- OnePlus 9 Pro `7a3c3b6d` (KernelSU-Next 3.4.2, Android 16): same result
  (`273e8187b6…`, `phone` / `100.64.0.2`, `module.conf` copied); the dormant
  foreign `TS_DNS_ENFORCE`/`TS_HOTSPOT_DNS` chains were left untouched. After an
  authorized reboot: old module removed, TailMate running, `poco.lan` resolved.
- Companion: the old app was uninstalled and the new package installed on both;
  root granted in Manager. Status card showed Connected, IPs, Taildrop, system
  DNS and module 4.0.0. Taildrop OnePlus → Poco delivered to Download with the
  "Taildrop file received" notification; Poco → OnePlus via the share sheet
  delivered with the "Files sent" notification.

Limits: Magisk and APatch not tested on a device. ARMv7 ZIP verified offline
only.

Repository rename (2026-11-25): GitHub `bioluks/KernelSU-Tailscaled` →
`bioluks/TailMate`, Forgejo `execd/KernelSU-Tailscaled` → `execd/TailMate`.
Afterwards the 3.x `updateJson`
(`raw.githubusercontent.com/bioluks/KernelSU-Tailscaled/master/update-kernelsu.json`),
the old repo page, an old release asset URL, the old git URL and the old Forgejo
page all still resolved (HTTP 200 via redirect). Existing installs keep polling
the bridge feed; it advances to 4.0.0 only when `master` is fast-forwarded after
the release exists.
