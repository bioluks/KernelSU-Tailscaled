# Android compatibility engine

The bundled engine is built from Tailscale v1.104.7 plus the reviewed patches in
`patches/`. It is not an unmodified official Tailscale binary. Its long version
contains `tm-android-compat-1` (`kst-android-compat-1` before TailMate 4.0.0) and
the patch-set hash. `engine-build.json` (this directory) is the committed lock:
upstream commit/archive hash, exact Go toolchain, patch hashes, compiler flags
and the expected output hashes. The binaries themselves are **not committed**;
every build regenerates them from source into `files/` and must reproduce the
lock byte for byte. The installed bundle includes the same record.

`hev-socks5-tunnel-linux-{arm,arm64}` in `files/` are unmodified upstream
release assets of hev-socks5-tunnel 2.6.8 (`linux-arm32` and `linux-arm64`), used
only in userspace mode. `hev-socks5-tunnel.sha256` pins them to the hashes of
https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.6.8.

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
Go 1.27.4, verifies the upstream archive, applies patches to a fresh source tree,
and builds static Linux ARMv7/ARM64 CLI and daemon binaries with trimmed paths
and deterministic stamps:

```sh
python3 scripts/build_engine.py               # fails unless the output matches the lock
python3 scripts/build_engine.py --update-lock # only for an intended engine change
```

Run engine tests on Linux:

```sh
python3 scripts/build_engine.py --test-only
```

The pinned source archive and temporary source trees are kept only under ignored
`.local/`; no downloaded source tree or credentials belong in version control.
Go dependencies are verified against upstream's unchanged `go.sum` with
`-mod=readonly`. CI builds all four engines from source and checks them
against the lock before packaging.

Both binaries use upstream's `ts_omit_clientupdate` build tag. This removes
the CLI self-update command and daemon self-update installation endpoint,
including automatic/control-plane initiated replacement, rather than merely
hiding a UI action.

The compatibility marker `tailscale/config/engine-compat` prevents standalone
unpatched engine updates from silently undoing the fixes. Install a newer full
module for engine updates. Previously downloaded engine data is preserved;
activation refuses to downgrade a newer selected engine.

## Upstream references

- https://github.com/tailscale/tailscale/tree/v1.104.7
- https://github.com/tailscale/tailscale/blob/v1.104.7/net/netmon/interfaces_linux.go
- https://github.com/tailscale/tailscale/blob/v1.104.7/net/netns/netns_linux.go
- https://github.com/tailscale/tailscale/tree/v1.104.7/feature/androiddns
- https://github.com/mdlayher/netlink/blob/v1.11.2/conn_linux.go
