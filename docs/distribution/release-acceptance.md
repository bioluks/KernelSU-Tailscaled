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
