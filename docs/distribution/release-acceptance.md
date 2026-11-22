# Publication acceptance

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
