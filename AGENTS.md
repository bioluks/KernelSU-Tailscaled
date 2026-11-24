# AGENTS.md — KernelSU-Tailscaled

Always-load context for agents working in this repo. Deep docs exist; read them
before touching their area. This file is the index + the invariants that cost
hours when violated.

## What this repo is

A KernelSU/KernelSU-Next Magisk-style module running a patched Tailscale
daemon natively on Android **without occupying the VPN slot**. State lives in
`/data/adb/kernelsu-tailscaled` (TS_DIR), isolated from other modules; explicit
migration for legacy installs. Fork of WayneShao/ryukora work; default branch
is `master`.

## Layout

| Path | Purpose |
|---|---|
| `module.prop`, `update-kernelsu.json`, `CHANGELOG.txt` | Version + public update feed |
| `files/` | Bundled engines (`tailscale*{,-arm64,-arm}`), hev tunnel binaries, `VERSION.txt`, `engine-build.json`, `manifest.sha256` |
| `engine/patches/` | Two patches over pristine upstream: `0001-old-kernel-netlink`, `0002-android-dns` |
| `scripts/` | Installer/runtime control: `build_engine.py`, `build_release.py`, `runtime-control.sh`, `verify-package.py` |
| `tailscale/scripts/` | Runtime: `tailscale-service` (commands/status), `tailscale-daemon`, `tailscale-supervisor`, `system-dns.sh` (opt-in DNS takeover), `common.sh`, `json.awk` |
| `webroot-src/` + `webroot/` | Module WebUI (TypeScript/vitest/Playwright); `webroot/assets/app.js` is **committed build output** |
| `android/` | Companion APK: plain Java, programmatic UI, custom Python build (`build.py`) |
| `tests/` | Python unittest suite (Linux-only, runs in docker) + fake runtime binaries in `tests/runtime/` |
| `docs/` | `distribution/release-acceptance.md` (publication gate + device evidence), `verification/` (dated reports), `releases/` (release notes), `research/` |

## Hard invariants

### Versioning
- `versionCode` is a **single monotonic integer** (current `30050005`). KernelSU
  orders updates by it; `kst_activate` rejects an equal-or-lower code with
  different bundle bytes. Every build iteration on a device consumes a code.
- `version=` embeds the engine: `X.Y.Z_tailscaleA.B.C`. `+` is rejected by
  `android/build.py`'s filename-safe regex; `-` makes the publish workflow treat
  the tag as a prerelease. The suffix must match `files/VERSION.txt`
  (`tests/test_metadata.py` enforces it).
- The public feed on `master` must never point at a ZIP URL that does not exist
  yet. Advance it only after the release is published (see Release flow).

### Engine (Tailscale)
- `scripts/build_engine.py` constants `VERSION`, `REVISION`,
  `SOURCE_SHA256`, `TOOLCHAIN` are the single source of the engine version.
  `files/VERSION.txt`, `files/engine-build.json`, `files/manifest.sha256` are
  **generated**; never hand-edit. Upstream bumps may raise the `go.mod` Go
  requirement (1.104.7 needed go1.27.4) — bump TOOLCHAIN and the CI
  `go-version` in lockstep.
- Patches must apply cleanly with `git apply --check` to the pristine tarball
  (`.local/engine-build/` caches it; ignored). Rebuild is expected to be
  **byte-identical across two runs** in the pinned container.
- When regenerating the manifest, put `files/hev-socks5-tunnel-linux-*` into
  the output dir first or the manifest will miss them.

### Runtime & state
- Generations live at `$TS_DIR/releases/<versionCode>-<digest>`; `$TS_DIR/current`
  symlinks the active one. Manager staging (`/data/adb/modules_update/…`) is
  promoted **on reboot**; a no-reboot flash switches the runtime immediately.
- `control.sh <action>` dispatches to `scripts/runtime-control.sh` →
  `tailscale-service`. Known actions are allowlisted in both; new commands must
  be added there and (for the WebUI) in `webroot-src/src/api.ts` `ACTIONS`.
- `status-json` is a public contract consumed by the WebUI (`model.ts`) and the
  companion (`MainActivity`). It must stay valid JSON; unknown fields must be
  optional for older consumers.

### System-wide DNS (`tailscale/scripts/system-dns.sh`) — the expensive lessons
- **Opt-in, default off.** While never enabled, no netfilter command runs.
- Target = **first IPv4 resolver from `tailscale dns status --json`**, not
  quad-100: tailnet resolvers ignore queries from non-tailnet sources, and
  Android's netd (root) sends with carrier CGNAT source addresses.
- Requires **SNAT in nat POSTROUTING** (`-o tailscale0 -d <target> -p udp/tcp
  --dport 53 -j SNAT --to-source <node tailnet IP>`), or resolvers never answer.
- Exclude tailscaled by **socket mark** `-m mark --mark 0x80000/0xff0000
  -j RETURN`. A uid-0 exclusion also skips Android's own resolver (netd is root).
- IPv6: NAT path when the kernel has IPv6 NAT; otherwise REJECT v6 port 53 so
  queries fall back to the redirected v4 path.
- Probe before enabling: a public name **and** the node's own MagicDNS
  `SelfDNSName` must get answers through the chosen resolver (server banner
  lines must not count — compare the address field).
- Persist `target4 target6 node4 node6` in the flag file; re-apply on
  daemon start (retry ~3s) and from supervisor maintenance, remove on stop.
- Refuse when another active `--dport 53` redirect exists; warn when a dormant
  foreign chain does (`TS_DNS_ENFORCE` etc. on the OnePlus).
- Toybox quirks: `grep` needs `--` before patterns starting with `-`;
  `grep -E -- '--dport 53'`; toybox `awk` lacks gawk features (no `match()`
  arrays, keep regex conditions simple).

### Status truthfulness
- The daemon cannot write Android's `/etc/resolv.conf`; that expected health
  line is filtered and reported as the info diagnostic `android-system-dns`.
  `dns-accepted` is informational, not a warning.

## Commands

```sh
# Full Linux suite (git needed for static tests):
docker run --rm -v "$(pwd):/repo:ro" -w /repo --entrypoint sh kst-mksh-test:local \
  -ec 'apt-get update -qq && apt-get install -y -qq git >/dev/null; \
       git config --global --add safe.directory /repo; \
       PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests'
# Focused: -p test_system_dns.py, test_runtime_behavior.py, test_metadata.py

# Shell:
#  sh -n each changed script; shellcheck --exclude=SC1090,SC1091,SC2034 each

# WebUI:
npm --prefix webroot-src run typecheck && npm --prefix webroot-src test
npm --prefix webroot-src run build        # commits webroot/assets/app.js
npm --prefix webroot-src run test:browser # Playwright

# Companion (needs Android SDK, ANDROID_HOME):
python3 android/build.py --debug

# Packages:
sh scripts/build-release.sh --use-existing-binaries --output <dir>
python3 scripts/verify-package.py --zip <zip> --arch all|arm|arm64
```

## Release flow

1. Get the change through the **full suite + device verification**; record
   evidence in `docs/distribution/release-acceptance.md` (exact ZIP hashes,
   device results, limits). Reboot authorization is explicit.
2. Build ZIPs from the exact final tree; prove provenance by rebuilding from
   `git archive HEAD` in the pinned container and `cmp`.
3. Tag = `v$(sed -n 's/^version=//p' module.prop)` at the final commit; pushing
   the tag runs `.github/workflows/release.yml` (signed companion) then
   `publish-source.yml` (immutable release with the three ZIPs + APK).
4. Only after the release exists: fast-forward `master`, push, verify the raw
   `update-kernelsu.json` matches. Legacy branch renames keep old raw URLs
   redirecting (verified empirically).

## Test harness facts

- `tests/runtime/fake_runtime.py` fakes `tailscale`/`tailscaled`/`ip`/`nc`;
  `fake_netfilter.py` fakes `iptables`/`ip6tables`/`nslookup` with a state file
  (`FAKE_NETFILTER_ROOT`), builtin chains, `FAKE_IP6TABLES_NO_NAT`, and
  `dns-probe-fail`/`dns-self-fail` controls. `dns status --json` and `ip -4/-6`
  branches exist in the tailscale fake; keep branch order (dns-status must be
  checked before the generic `status` handler).
- Reusable harness base is `RuntimeHarness` in `test_runtime_behavior.py`;
  subclass it instead of duplicating `setUp`.

## Devices (test conventions)

- OnePlus 9 Pro `7a3c3b6d` (KernelSU-Next 3.4.2, Android 16, arm64, Headscale
  tailnet, MagicDNS `phone.lan`) — the user's **primary** phone; avoid
  disruptive tests. Poco F1 `60b09a5f` (KernelSU-Next 1.1.1, Android 15,
  universal ZIP, same tailnet as `poco.lan` / 100.64.0.8) — a **disposable**
  test device; anything goes. Companion root must be granted in Manager.
- Headscale is reachable as `ssh headscale` (LXC); `headscale preauthkeys
  create --user 1 --expiration 30m` joins a device via the module's socket.
- Taildrop between the two phones is verified both ways through the companion.
- Always `uiautomator dump` and find the node before tapping; **stale
  "Flash success" dialogs persist** — dismiss them and verify the install via
  `su -c "readlink -f /data/adb/kernelsu-tailscaled/current"` and
  `modules_update/module.prop`, never via the dialog alone.
- Resolution proofs: `tcpdump -ni tailscale0 "udp port 53"` while `ping`-ing a
  fresh random name and `phone.lan`.

## Gotchas that burned hours

- Android 16 has IPv4 NAT but no IPv6 NAT (on the OnePlus kernel); DoT/DoH
  apps and Android Private DNS bypass port-53 redirects.
- The OnePlus has dormant `TS_DNS_ENFORCE`/`TS_HOTSPOT_DNS` chains from an
  AFWall-style setup — the module warns about them, never touches them.
- `bounded` in `common.sh` is the safe CLI wrapper; use it for all tailscale
  CLI calls.
- Commit the generated `webroot/assets/app.js` after every `npm run build`;
  CI diffs it.
- Android kills an app's whole `uid_N/pid_M` cgroup (v2) when it is removed
  from recents. Anything started from KernelSU Manager's WebUI or the companion
  inherits that group, so the daemon died with the manager ("PhantomProcess …
  died"). `kst_leave_app_cgroup` in `bundle-lib.sh` moves the controller out
  first; verify with `/sys/fs/cgroup/uid_*/pid_*/cgroup.procs`.
- Ad-hoc `adb shell su -c '…$(pidof x)…'` expands on the Mac side; push a
  script to `/data/local/tmp` and run that instead.
