#!/usr/bin/env python3
"""Build the TailMate companion APK with only Python, a JDK, and Android SDK tools.

Default mode signs with the release key from ANDROID_KEYSTORE et al.; --debug signs
with a private developer key; --unsigned emits the zipaligned unsigned release APK
(for F-Droid's build server, which signs or verifies it separately).
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"
MIN_API = 26
TARGET_API = 36
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
SIGNING_VARIABLES = (
    "ANDROID_KEYSTORE", "ANDROID_KEY_ALIAS",
    "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_PASSWORD",
)
DEBUG_KEYSTORE = Path.home() / ".local/share/tailmate/debug.keystore"
REPRODUCIBLE_BUILD_TOOLS = "36.0.0"
REPRODUCIBLE_JDK = "25"
# apksigner >= 35 writes signatures that F-Droid's signature copying
# (apksigcopier) cannot reproduce, so f-droid.org would refuse to publish the
# developer-signed APK: https://f-droid.org/docs/Reproducible_Builds/
SIGNING_BUILD_TOOLS = "34.0.0"


def run(command: list[str | Path], *, env: dict[str, str] | None = None,
        private: bool = False) -> None:
    # Never echo signing arguments or signing-tool diagnostics: either can contain secrets.
    try:
        result = subprocess.run([str(part) for part in command], cwd=ROOT, env=env,
                                stdin=subprocess.DEVNULL, capture_output=True, text=True)
    except OSError:
        raise ValueError(f"Could not execute {Path(command[0]).name}") from None
    if result.returncode:
        if not private:
            sys.stderr.write(result.stdout)
            sys.stderr.write(result.stderr)
        raise ValueError(f"{Path(command[0]).name} failed (exit {result.returncode})")
    if not private:
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)


def module_version() -> tuple[str, str]:
    properties = {}
    for line in (ROOT / "module.prop").read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            properties[key] = value.strip()
    version = properties.get("version", "")
    code = properties.get("versionCode", "")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[_-][A-Za-z0-9._-]+)?", version):
        raise ValueError("module.prop must contain a filename-safe version")
    if not code.isascii() or not code.isdigit() or not 0 < int(code) <= 2100000000:
        raise ValueError("module.prop must contain a valid Android versionCode")
    return version, str(int(code))


def sdk_tools(sdk: Path, pinned: bool) -> tuple[Path, Path]:
    # Release and unsigned APKs must be byte-identical across hosts so f-droid.org
    # can verify and ship the developer-signed APK; that requires these exact tools.
    platforms = [sdk / "platforms" / name / "android.jar"
                 for name in (("android-36",) if pinned else ("android-36", "android-36.1"))]
    android_jar = next((path for path in platforms if path.is_file()), None)
    if android_jar is None:
        raise ValueError("Install SDK platform android-36")
    requested = os.environ.get("ANDROID_BUILD_TOOLS_VERSION")
    if pinned:
        if requested and requested != REPRODUCIBLE_BUILD_TOOLS:
            raise ValueError(f"Release and unsigned builds require Build Tools {REPRODUCIBLE_BUILD_TOOLS}")
        candidates = [sdk / "build-tools" / REPRODUCIBLE_BUILD_TOOLS]
    elif requested:
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", requested):
            raise ValueError("ANDROID_BUILD_TOOLS_VERSION must name a stable Build Tools version")
        candidates = [sdk / "build-tools" / requested]
    else:
        candidates = sorted(
            (path for path in (sdk / "build-tools").glob("*")
             if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", path.name)),
            key=lambda path: tuple(int(part) for part in path.name.split(".")), reverse=True,
        )
    tools = next((path for path in candidates
                  if all((path / tool).is_file()
                         for tool in ("aapt2", "d8", "zipalign", "apksigner"))), None)
    if tools is None:
        raise ValueError("Install Android SDK Build Tools "
                         f"{REPRODUCIBLE_BUILD_TOOLS if pinned else ''} with aapt2, d8, zipalign, and apksigner")
    return tools, android_jar


def signing_tools(sdk: Path) -> Path:
    signer = sdk / "build-tools" / SIGNING_BUILD_TOOLS / "apksigner"
    if not signer.is_file():
        raise ValueError(f"Install Android SDK Build Tools {SIGNING_BUILD_TOOLS} (its apksigner signs releases)")
    return signer


def jdk_tool(name: str, pinned: bool) -> Path:
    java_home = os.environ.get("JAVA_HOME")
    path = Path(java_home) / "bin" / name if java_home else None
    if path is None or not path.is_file():
        found = shutil.which(name)
        if found is None:
            raise ValueError(f"Install a JDK providing {name} (javac must support --release 8)")
        path = Path(found)
    if pinned:
        # `javac -version` prints e.g. "javac 25.0.2" (stdout on modern JDKs).
        result = subprocess.run([str(path), "-version"], capture_output=True, text=True)
        major = re.search(r"\b(\d+)[.\s]", result.stdout + result.stderr + " ")
        if major is None or major.group(1) != REPRODUCIBLE_JDK:
            raise ValueError(f"Release and unsigned builds require JDK {REPRODUCIBLE_JDK} (set JAVA_HOME)")
    return path


def signing(debug: bool, debug_keystore: Path) -> tuple[Path, str, dict[str, str]]:
    environment = os.environ.copy()
    if not debug:
        missing = [name for name in SIGNING_VARIABLES if not environment.get(name)]
        if missing:
            raise ValueError("Release signing requires " + ", ".join(missing))
        keystore = Path(environment["ANDROID_KEYSTORE"]).expanduser().resolve()
        if not keystore.is_file():
            raise ValueError("ANDROID_KEYSTORE must identify an existing keystore")
        return keystore, environment["ANDROID_KEY_ALIAS"], environment

    keystore = debug_keystore.expanduser().resolve()
    if keystore == ROOT or ROOT in keystore.parents:
        raise ValueError("The developer debug keystore must be outside the repository")
    environment["ANDROID_KEYSTORE_PASSWORD"] = "android"
    environment["ANDROID_KEY_PASSWORD"] = "android"
    if not keystore.exists():
        keystore.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        old_umask = os.umask(0o077)
        try:
            # keytool's :env form keeps passwords out of process arguments and logs.
            run([jdk_tool("keytool", False), "-genkeypair", "-noprompt", "-keystore", keystore,
                 "-storetype", "PKCS12", "-alias", "androiddebugkey", "-keyalg", "RSA",
                 "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug",
                 "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
                 "-keypass:env", "ANDROID_KEY_PASSWORD"], env=environment, private=True)
        finally:
            os.umask(old_umask)
    if not keystore.is_file():
        raise ValueError("The debug keystore is not a regular file")
    keystore.chmod(0o600)
    return keystore, "androiddebugkey", environment


def manifest(output: Path, version: str, code: str, debug: bool) -> None:
    ET.register_namespace("android", ANDROID_NS)
    tree = ET.parse(ROOT / "android/AndroidManifest.xml")
    root = tree.getroot()
    root.set(f"{{{ANDROID_NS}}}versionCode", code)
    root.set(f"{{{ANDROID_NS}}}versionName", version)
    sdk = root.find("uses-sdk")
    if sdk is None:
        sdk = ET.Element("uses-sdk")
        root.insert(0, sdk)
    sdk.set(f"{{{ANDROID_NS}}}minSdkVersion", str(MIN_API))
    sdk.set(f"{{{ANDROID_NS}}}targetSdkVersion", str(TARGET_API))
    application = root.find("application")
    if application is None:
        raise ValueError("AndroidManifest.xml has no application")
    application.set(f"{{{ANDROID_NS}}}debuggable", "true" if debug else "false")
    tree.write(output, encoding="utf-8", xml_declaration=True)


def unsigned_apk(resources: Path, dex: Path, output: Path) -> None:
    # Normalize every ZIP entry, not just DEX, and keep resources.arsc uncompressed.
    # Stored entries also avoid host/zlib-dependent compressed bytes.
    with zipfile.ZipFile(resources) as source:
        entries = {name: source.read(name) for name in source.namelist()}
        if len(entries) != len(source.infolist()):
            raise ValueError("Resource APK contains duplicate ZIP entries")
    dex_files = sorted(dex.glob("classes*.dex"))
    if not dex_files or dex_files[0].name != "classes.dex":
        raise ValueError("d8 did not generate classes.dex")
    for path in dex_files:
        if path.name in entries:
            raise ValueError("Resource APK unexpectedly contains DEX")
        entries[path.name] = path.read_bytes()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, entries[name])


def build(args: argparse.Namespace) -> Path:
    version, code = module_version()
    # Signing is mandatory unless explicitly unsigned, and is validated before compiling.
    if not args.unsigned:
        keystore, alias, environment = signing(args.debug, args.debug_keystore)
    pinned = not args.debug
    tools, android_jar = sdk_tools(args.sdk.expanduser().resolve(), pinned)
    javac = jdk_tool("javac", pinned)
    signer = (None if args.unsigned else
              signing_tools(args.sdk.expanduser().resolve()) if pinned else tools / "apksigner")
    build_dir = ROOT / "android/build"
    build_dir.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    suffix = "-unsigned" if args.unsigned else ""
    output = args.output.resolve() / f"TailMate-companion-v{version}{suffix}.apk"
    with tempfile.TemporaryDirectory(prefix="apk-", dir=build_dir) as directory:
        work = Path(directory)
        compiled, generated, classes, dex = [work / name for name in ("res", "java", "classes", "dex")]
        for path in (compiled, generated, classes, dex):
            path.mkdir()
        manifest_file = work / "AndroidManifest.xml"
        manifest(manifest_file, version, code, args.debug)
        # https://developer.android.com/tools/aapt2#compile and #link
        run([tools / "aapt2", "compile", "--dir", "android/res", "-o", compiled])
        resources = work / "resources.apk"
        run([tools / "aapt2", "link", "-o", resources, "--manifest", manifest_file,
             "-I", android_jar, "--java", generated, "--version-code", code,
             "--version-name", version, "--replace-version",
             "--compile-sdk-version-code", str(TARGET_API), "--compile-sdk-version-name", "16",
             *sorted(compiled.glob("*.flat"))])
        sources = sorted((ROOT / "android/src").rglob("*.java"))
        if not sources:
            raise ValueError("No Android Java sources found")
        run([javac, "--release", "8", "-encoding", "UTF-8", "-proc:none",
             "-g" if args.debug else "-g:none", "-classpath", android_jar, "-d", classes,
             *sources, *sorted(generated.rglob("*.java"))])
        # https://developer.android.com/tools/d8#general-usage
        run([tools / "d8", "--debug" if args.debug else "--release", "--min-api", str(MIN_API),
             "--lib", android_jar, "--output", dex, *sorted(classes.rglob("*.class"))])
        unsigned = work / "unsigned.apk"
        unsigned_apk(resources, dex, unsigned)
        aligned = work / "aligned.apk"
        # Alignment must precede signing: https://developer.android.com/tools/apksigner
        run([tools / "zipalign", "-f", "4", unsigned, aligned])
        if args.unsigned:
            run([tools / "zipalign", "-c", "4", aligned])
            shutil.copyfile(aligned, build_dir / "companion-unsigned.apk")
            os.replace(aligned, output)
            return output
        signed = work / "signed.apk"
        assert signer is not None
        run([signer, "sign", "--ks", keystore, "--ks-key-alias", alias,
             "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
             "--min-sdk-version", str(MIN_API), "--v1-signing-enabled", "false",
             "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
             "--v4-signing-enabled", "false", "--out", signed, aligned],
            env=environment, private=True)
        run([signer, "verify", "--min-sdk-version", str(MIN_API), signed])
        run([tools / "zipalign", "-c", "4", signed])
        shutil.copyfile(aligned, build_dir / "companion-unsigned.apk")
        os.replace(signed, output)
    return output


def main() -> int:
    sdk_default = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_default:
        sdk_default = str(Path.home() / ("Library/Android/sdk" if sys.platform == "darwin" else "Android/Sdk"))
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sdk", type=Path, default=Path(sdk_default), help="Android SDK directory")
    parser.add_argument("--output", type=Path, default=ROOT / "dist", help="APK output directory")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--debug", action="store_true", help="Explicitly use a private developer signing key")
    mode.add_argument("--unsigned", action="store_true",
                      help="Emit the zipaligned unsigned release APK; needs no keystore")
    parser.add_argument("--debug-keystore", type=Path, default=DEBUG_KEYSTORE,
                        help="Developer keystore outside the repository (used only with --debug)")
    args = parser.parse_args()
    try:
        output = build(args)
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"APK build failed: {error}", file=sys.stderr)
        return 1
    kind = "unsigned" if args.unsigned else "debug" if args.debug else "release"
    print(f"Built {kind} APK: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
