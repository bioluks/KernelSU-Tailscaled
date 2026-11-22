#!/usr/bin/env python3
"""Build the Android compatibility engine from verified upstream source and patches."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.104.7"
REVISION = "cbc74fe48ef1b1649f69a4d9a602319427e25102"
SOURCE_URL = f"https://github.com/tailscale/tailscale/archive/refs/tags/v{VERSION}.tar.gz"
SOURCE_SHA256 = "fa1c922b33d0f6db6b073800d7a62fa84081323d247b21547c63a98ed7ecac14"
TOOLCHAIN = "go1.27.4"
COMPATIBILITY = "android-compat-1"
BUILD_TAGS = "ts_omit_clientupdate"
TEST_PACKAGES = ("./net/netmon", "./net/dnscache", "./feature/androiddns", "./net/dns", "./cmd/tailscale")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_archive(cache: Path) -> Path:
    archive = cache / f"tailscale-v{VERSION}.tar.gz"
    if archive.exists():
        if sha256(archive) != SOURCE_SHA256:
            raise ValueError(f"Cached source hash mismatch: {archive}")
        return archive
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
                shutil.copyfileobj(response, stream)
            stream.close()
            if sha256(temporary) != SOURCE_SHA256:
                raise ValueError("Upstream source hash mismatch")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    return archive


def prepare_source(archive: Path, work: Path, patches: list[Path]) -> Path:
    with tarfile.open(archive, "r:gz") as source:
        for member in source.getmembers():
            if not (member.isfile() or member.isdir() or member.issym()):
                raise ValueError(f"Unexpected source member type: {member.name}")
        source.extractall(work, filter="data")
    source = work / f"tailscale-{VERSION}"
    patch_environment = dict(os.environ, GIT_CEILING_DIRECTORIES=str(work))
    for patch in patches:
        subprocess.run(["git", "apply", "--check", str(patch)], cwd=source,
                       env=patch_environment, check=True)
        subprocess.run(["git", "apply", str(patch)], cwd=source,
                       env=patch_environment, check=True)
    return source


def build(output: Path, cache: Path, test_only: bool) -> None:
    patches = sorted((ROOT / "engine" / "patches").glob("*.patch"))
    if not patches:
        raise ValueError("Engine compatibility patches are required")
    patch_hashes = {p.name: sha256(p) for p in patches}
    patch_set = hashlib.sha256(json.dumps(patch_hashes, sort_keys=True).encode()).hexdigest()
    environment = dict(os.environ, GOTOOLCHAIN=TOOLCHAIN, GOWORK="off", CGO_ENABLED="0")
    for key in ("GOOS", "GOARCH", "GOARM", "GOFLAGS"):
        environment.pop(key, None)
    reported = subprocess.check_output(["go", "version"], env=environment, text=True)
    if reported.split()[2] != TOOLCHAIN:
        raise ValueError(f"Expected {TOOLCHAIN}, got {reported.strip()}")
    cache.mkdir(parents=True, exist_ok=True)
    archive = source_archive(cache)
    with tempfile.TemporaryDirectory(prefix="source-", dir=cache) as temporary:
        source = prepare_source(archive, Path(temporary), patches)
        if test_only:
            if platform.system() != "Linux":
                raise ValueError("Engine regression tests require Linux; use the pinned Go container")
            subprocess.run(["go", "test", "-mod=readonly", "-tags", BUILD_TAGS, "-count=1", *TEST_PACKAGES],
                           cwd=source, env=environment, check=True)
            return
        output.mkdir(parents=True, exist_ok=True)
        long_version = f"{VERSION}-kst-{COMPATIBILITY}-{patch_set[:12]}"
        flags = (f"-s -w -buildid= -X tailscale.com/version.shortStamp={VERSION} "
                 f"-X tailscale.com/version.longStamp={long_version} "
                 f"-X tailscale.com/version.gitCommitStamp={REVISION}")
        # Build everything before replacing any installed payload.
        generated = Path(temporary) / "binaries"
        generated.mkdir()
        for architecture in ("arm", "arm64"):
            target_env = dict(environment, GOOS="linux", GOARCH=architecture, GOARM="7")
            for program in ("tailscale", "tailscaled"):
                name = f"{program}-{architecture}"
                subprocess.run(["go", "build", "-mod=readonly", "-trimpath", "-buildvcs=false",
                                "-tags", BUILD_TAGS, "-ldflags", flags, "-o", str(generated / name), f"./cmd/{program}"],
                               cwd=source, env=target_env, check=True)
        provenance = {
            "upstreamVersion": VERSION, "upstreamCommit": REVISION,
            "sourceUrl": SOURCE_URL, "sourceSha256": SOURCE_SHA256,
            "toolchain": TOOLCHAIN, "compatibility": COMPATIBILITY,
            "patches": patch_hashes, "patchSetSha256": patch_set,
            "longVersion": long_version,
            "build": {"GOOS": "linux", "CGO_ENABLED": "0", "GOARM": "7",
                      "trimpath": True, "buildvcs": False, "tags": BUILD_TAGS, "ldflags": flags},
            "binaries": {p.name: sha256(p) for p in sorted(generated.iterdir())},
        }
        for binary in generated.iterdir():
            shutil.copyfile(binary, output / binary.name)
            (output / binary.name).chmod(0o755)
        (output / "VERSION.txt").write_text(f"v{VERSION}\n", encoding="ascii")
        (output / "engine-build.json").write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n")
        payloads = sorted(p for p in output.iterdir()
                          if p.is_file() and (p.name in provenance["binaries"] or
                                             p.name.startswith("hev-socks5-tunnel-linux-") or
                                             p.name in ("VERSION.txt", "engine-build.json")))
        (output / "manifest.sha256").write_text("".join(
            f"{sha256(p)}  files/{p.name}\n" for p in payloads), encoding="ascii")
        print(json.dumps(provenance, indent=2, sort_keys=True))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "files")
    parser.add_argument("--cache", type=Path, default=ROOT / ".local" / "engine-build")
    parser.add_argument("--test-only", action="store_true")
    args = parser.parse_args()
    build(args.output.resolve(), args.cache.resolve(), args.test_only)


if __name__ == "__main__":
    main()
