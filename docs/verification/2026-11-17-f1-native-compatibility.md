# Native Android compatibility — local v3.1.1-dev.3

Local candidate only. No release, tag, store submission or publication is authorized
by these checks. The stable update feed remains v3.1.0.

## Root cause and implementation

On Poco F1 (beryllium), crDroid Android 15/API 35, KernelSU Next 1.1.1, kernel
4.9.337-perf-g0b3e38ef6c40, the previous engine's optional strict netlink setup
returned `ENOPROTOOPT` for `NETLINK_EXT_ACK`. An observed strace then showed
`SO_BINDTODEVICE("lo")`: default-interface discovery had failed even though
Android's interface routing table had a working default route.

The engine now retries that read-only route connection without the optional
strict options only for `ENOPROTOOPT`. Supporting kernels retain strict behavior;
permission and route-dump failures remain errors. Android-gated DNS setup also
connects the cached control resolver to dnsproxyd and includes that integration
in the standalone CLI. There are no device/interface-name checks, main-table
route copies, custom firewall hooks, SELinux changes, or VpnService.

Fresh profile defaults remain pending until upstream actually persists a login
profile. They are reapplied once per daemon generation before first login;
existing/persisted, logged-out and migrated identities are not reinitialized.

The two binaries are built from pinned upstream source with the small patches in
`engine/patches/`, not distributed as unmodified official binaries. See
`engine/README.md` and installed `engine-build.json`. Both use
`ts_omit_clientupdate`; the CLI update command returns unknown subcommand and
`POST /localapi/v0/update/install` returns 404. Module-level standalone engine
prepare/apply are also blocked so compatibility patches cannot be overwritten.
A newer selected engine causes a downgrade to be rejected before runtime stop.

## Artifacts tested

| Artifact | SHA-256 |
| --- | --- |
| Universal v3.1.1-dev.3 ZIP | `01d394510c2c474ccd4e8dcfedd59dc9191230cd1dbfd8e9af6dfe613b796cbd` |
| ARM v3.1.1-dev.3 ZIP | `886f55102a9cd709b8090879fcd546577223c2e195df032d3af6d2686faaad89` |
| ARM64 v3.1.1-dev.3 ZIP installed on F1 | `4f91b8d14e55710a87ac31dfaa2f23bf19920bd89268563a5abc1214444afd12` |
| Companion v3.1.1-dev.3 APK installed on F1 | `6b85833a35f84ee065f7c435e4ec4b067d8a8ba322ca50e6a883f424a4564f9b` |

The APK retains release certificate SHA-256
`0924BB045985C248E9DDF9E9139E090AEAC72446AE94CF41D8896AF5DC626AE3`.
All three ZIPs passed installed-bundle, architecture, payload and provenance
verification and reproduced byte-for-byte. All four engine binaries and their
provenance reproduced identically using pinned Go 1.27.3 on macOS and Linux.

## Checks performed

- Five affected upstream Go packages passed on modern Linux, including simulated
  unsupported-option fallback, genuine-error propagation, normal route selection,
  modern/legacy dnsproxyd cache lookups, and missing-resolv.conf DNS-disabled use.
  `go mod verify` passed. Hardened builds ran the same packages with the updater
  omission build tag.
- Linux shell tests: 51 runtime behavior, 36 install/lifecycle and 48
  engine-update/package/metadata tests passed. Initial host/environment failures
  (BSD mv, old Python lacking tar filters, missing unzip) were resolved by using
  Linux/Python 3.13 and installing the actual test prerequisites; no tests skipped
  to hide these failures.
- Typecheck, 29 focused frontend tests and frontend build passed. Three existing
  engine browser scenarios passed at desktop/mobile/compact sizes. The actual
  compatibility-policy renderer was visually checked in English and Chinese at
  360px using a throwaway page; this is not a claim of physical KernelSU Details
  navigation verification.
- Complete ARM64 candidate installed through ksud, with SELinux Enforcing. No
  custom global boot scripts, AFWall, network proxy or ADB reverse path was used.
- Fresh initialization and pre-login restart retained the Android hostname,
  accept-dns=false, accept-routes=false and no exit node.
- Native interactive registration reached a private, disposable Headscale 0.29.6
  server over the LAN, not loopback forwarding. Both nodes belonged to one test
  user with file sharing enabled. Native lifecycle/components reached running
  with an empty engine Health list.
- Direct UDP peer ping passed (75ms before reboot, 4ms after network discovery).
  Netcheck with the daemon's native bind setting reported UDP true, public IPv4
  and DERP latency measurements; Android DNS resolved without resolv.conf hacks.
- A 32MiB file selected in the real companion document picker was sent in the
  background and retrieved with matching SHA-256. Automatic return delivery and
  duplicate-name preservation also matched hashes. No proxy was required.
- Stop/start/restart and hot upgrade to the final candidate preserved the node
  ID, tailnet addresses and exact projected preferences.
- One explicitly authorized real reboot changed boot ID. Without manual runtime
  start, supervisor, daemon, native data plane, loopback panel and receiver all
  became running, with the same identity/preferences and no Health warnings.
  Wi-Fi obtained a different LAN address. Early discovery probes timed out,
  then DERP replied and direct UDP re-established automatically.
- Final dev.3 postboot companion send and module receive used a unique 1MiB file:
  SHA-256 `6b9047c53fb4f6f42f85c01f4bf0394df8d26f21603b1b5438d9d76b1f287eb2`.
  Receiving succeeded with the companion force-stopped. A separate ordinary
  Android curl fetched 32MiB through the tailnet with the same file hash.

Host evidence/artifacts are under ignored `dist/f1-compat-20261117/`. The old
logged-out test state was moved, with user approval, to the private on-device
`/data/adb/kst-f1-precompat-20261117/` backup rather than erased.

## Limits

This removes a capability-specific old-kernel failure; it is not certification of
all kernels/ROMs. ARMv7 and ARM64 payloads were built; only ARM64 executed on the
F1. This patched candidate has not been rerun on the OnePlus, ARM32 hardware,
Magisk/APatch, every Android release, or simultaneous third-party VPNs. Cellular
handover, IPv6-only networks and multi-hour screen-off/battery behavior were not
established. Root permissions, kernel TUN and Android networking remain required.
Upstream's first gateway-bearing default-route heuristic remains; more complex
multi-network selection needs separate evidence. Kernel/ROM restrictions can
still prevent operation and must be reported, not silently bypassed.

## User handoff

At the user's request, the F1 was logged out of the disposable Headscale account
and the two task-owned lab containers were removed. The fixed module and
release-signed companion v3.1.1-dev.3 remain installed. The backend reports
`NeedsLogin`; no test server/account is required for normal use. The old private
test-state backup remains untouched. No release or push was performed.

Changes are on `fix/android-native-compatibility`; `all-features-merged` remains
at its original pre-fix commit. The candidate is for local testing and has not
been promoted to the stable update feed.
