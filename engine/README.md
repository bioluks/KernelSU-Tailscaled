# Android compatibility engine

The bundled engine is built from Tailscale v1.104.2 plus the reviewed patches in
`patches/`. It is not an unmodified official Tailscale binary. Its long version
contains `kst-android-compat-1` and the patch-set hash. `files/engine-build.json`
records the upstream commit/archive hash, exact Go toolchain, patch hashes,
compiler flags and output hashes; the installed bundle includes the same record.

## Changes

- Retry the read-only route netlink connection without optional strict socket
  options only when the kernel returns `ENOPROTOOPT`. Keep strict behavior on
  supporting kernels and propagate permission/route-dump errors. This fixes the
  Linux 4.9 failure where default-interface discovery otherwise fails and socket
  protection binds outbound traffic to loopback.
- Reuse Android's dnsproxyd-backed resolver for the cached resolver used by the
  control client; include that Android-gated resolver in the standalone CLI.
  Ordinary Linux resolver behavior is unchanged. No public DNS server is pinned.

No routes are copied into the main table, no interface names or kernel-version
strings are matched, no firewall rules are injected, and SELinux remains enforced.
Upstream's default-route heuristic is retained; this is not a claim that every
multi-network/VPN configuration is supported.

## Reproduce

Python 3.12+ (tar extraction filters), Git, and Go are required. The recipe pins
Go 1.27.3, verifies the upstream archive, applies patches to a fresh source tree,
and builds static Linux ARMv7/ARM64 CLI and daemon binaries with trimmed paths
and deterministic stamps:

```sh
python3 scripts/build_engine.py
```

Run engine tests on Linux:

```sh
python3 scripts/build_engine.py --test-only
```

The pinned source archive and temporary source trees are kept only under ignored
`.local/`; no downloaded source tree or credentials belong in version control.
Go dependencies are verified against upstream's unchanged `go.sum` with
`-mod=readonly`. CI rebuilds all four engines and compares bytes and provenance.

Both binaries use upstream's `ts_omit_clientupdate` build tag. This removes
the CLI self-update command and daemon self-update installation endpoint,
including automatic/control-plane initiated replacement, rather than merely
hiding a UI action.

The compatibility marker `tailscale/config/engine-compat` prevents standalone
unpatched engine updates from silently undoing the fixes. Install a newer full
module for engine updates. Previously downloaded engine data is preserved;
activation refuses to downgrade a newer selected engine.

## Upstream references

- https://github.com/tailscale/tailscale/tree/v1.104.2
- https://github.com/tailscale/tailscale/blob/v1.104.2/net/netmon/interfaces_linux.go
- https://github.com/tailscale/tailscale/blob/v1.104.2/net/netns/netns_linux.go
- https://github.com/tailscale/tailscale/tree/v1.104.2/feature/androiddns
- https://github.com/mdlayher/netlink/blob/v1.11.2/conn_linux.go
