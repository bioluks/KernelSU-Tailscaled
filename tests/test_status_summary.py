import hashlib
import os
import shutil
import signal
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SUMMARY = ROOT / "scripts/status-summary.sh"

# A real subprocess exchanges IP data with the fixture's Unix socket. It can
# deliberately block and ignore TERM so the timeout must actually kill it.
CLI = r'''
import os
import signal
import socket
import sys
import time
from pathlib import Path

signal.signal(signal.SIGTERM, signal.SIG_IGN)
with Path(os.environ["SUMMARY_CLI_CALLS"]).open("a") as calls:
    calls.write(str(os.getpid()) + "\n")
if len(sys.argv) != 4 or sys.argv[2:] != ["ip", "-4"]:
    sys.exit(2)
with socket.socket(socket.AF_UNIX) as client:
    client.connect(sys.argv[1].removeprefix("--socket="))
    client.sendall(b"ip -4\n")
    reply = client.recv(4096)
if reply == b"HANG\n":
    while True:
        time.sleep(3600)
sys.stdout.buffer.write(reply)
'''


@unittest.skipUnless(os.name == "posix" and shutil.which("timeout"),
                     "POSIX shell and timeout required; run on Linux")
class StatusSummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="kst-summary-", dir="/tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / "state/releases/selected"
        self.state = self.root / "state"
        self.module = self.root / "module"
        self.staged = self.root / "staged"
        (self.bundle / "bin").mkdir(parents=True)
        (self.state / "run").mkdir(parents=True)
        self.current = self.state / "current"
        self.current.symlink_to(self.bundle, target_is_directory=True)
        self.module.mkdir()
        self.staged.mkdir()
        scripts = self.bundle / "tailscale/scripts"
        scripts.mkdir(parents=True)
        shutil.copy(ROOT / "tailscale/scripts/engine-lib.sh", scripts)
        self.version = "v1.102.3"
        (self.bundle / "engine-version").write_text(self.version + "\n")
        self.lifecycle = self.state / "run/lifecycle"
        self.lifecycle.write_text("running\n")
        self.module_prop = self.module / "module.prop"
        self.original = (
            "id=kernelsu-tailscaled\n"
            "name=KernelSU-Tailscaled\n"
            "version=2.0.4\n"
            "versionCode=20000010\n"
            "author=fixture\n"
            "description=original description\n"
            "updateJson=https://example.invalid/module.json?channel=stable\n"
            "custom=value=with=equals\n"
        )
        self.module_prop.write_text(self.original)
        cli = self.bundle / "bin/tailscale"
        cli.write_text(f"#!{sys.executable}\n" + CLI)
        cli.chmod(0o755)
        self.calls = self.root / "cli-calls"
        self.env = dict(os.environ, BUNDLE_DIR=str(self.bundle), TS_DIR=str(self.state),
                        MODDIR=str(self.module), KST_STAGED_DIR=str(self.staged),
                        KST_BUSYBOX=str(self.root / "missing-busybox"),
                        KST_CLI_TIMEOUT="1", SUMMARY_CLI_CALLS=str(self.calls))
        self.addCleanup(self.stop_clients)
        self.reply = b"100.64.0.10\n"
        self.requests = []
        self.on_ip_query = None
        fixture = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                fixture.requests.append(self.rfile.readline())
                if fixture.on_ip_query is not None:
                    fixture.on_ip_query()
                self.wfile.write(fixture.reply)

        self.socket_path = self.state / "run/tailscaled.sock"
        self.server = socketserver.UnixStreamServer(str(self.socket_path), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=2)
            self.server = None

    def stop_clients(self):
        if self.calls.exists():
            for pid in self.calls.read_text().splitlines():
                try:
                    os.kill(int(pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def summarize(self, timeout=8):
        process = subprocess.Popen(["/bin/sh", str(SUMMARY)], env=self.env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True)
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            self.stop_clients()
            process.communicate(timeout=2)
            self.fail("status summary exceeded its CLI timeout")
        self.assertEqual(0, process.returncode, stdout + stderr)

    def assert_description(self, description):
        self.assertEqual(self.original.replace("description=original description",
                                               "description=" + description),
                         self.module_prop.read_text())
        self.assertEqual(0o644, self.module_prop.stat().st_mode & 0o777)

    def test_unchanged_summary_keeps_inode_mtime_and_contents(self):
        self.summarize()
        self.assert_description(
            "[service is running] Tailscale(v1.102.3) | 100.64.0.10 | UI ready")
        fixed_time = 1_700_000_000_000_000_000
        os.utime(self.module_prop, ns=(fixed_time, fixed_time))
        before = self.module_prop.stat()
        content = self.module_prop.read_bytes()
        for _ in range(3):
            self.summarize()
            after = self.module_prop.stat()
            self.assertEqual((before.st_ino, before.st_mtime_ns),
                             (after.st_ino, after.st_mtime_ns))
            self.assertEqual(content, self.module_prop.read_bytes())
        self.assertEqual([], list(self.module.glob("module.prop.tmp.*")))

    def test_ip_and_lifecycle_changes_replace_description_atomically(self):
        (self.staged / "module.prop").write_text("version=2.1.0\n")
        self.summarize()
        old_text = self.module_prop.read_text()
        old_inode = self.module_prop.stat().st_ino
        self.reply = b"100.64.0.20\n"
        self.lifecycle.write_text("degraded\n")
        (self.bundle / "engine-version").write_text("v1.104.1\n")
        with self.module_prop.open() as old_file:
            self.summarize()
            self.assertEqual(old_text, old_file.read())
        self.assertNotEqual(old_inode, self.module_prop.stat().st_ino)
        self.assert_description(
            "[service degraded] Tailscale(v1.104.1) | 100.64.0.20 | UI ready"
            " | update pending reboot: 2.1.0")

    def stage_update(self):
        metadata = "version=2.1.0\nversionCode=20100000\n"
        engine_version = (self.bundle / "engine-version").read_bytes()
        manifest = f"{hashlib.sha256(engine_version).hexdigest()}  engine-version\n"
        for directory in (self.bundle, self.staged):
            (directory / "module.prop").write_text(metadata)
            (directory / "bundle.sha256").write_text(manifest)
            (directory / "engine-version").write_bytes(engine_version)
        (self.module / "update").touch()

    def test_selected_update_reports_manager_promotion_pending_without_mutating_stage(self):
        self.stage_update()
        staged_before = {path: path.read_bytes() for path in self.staged.iterdir()}
        selected_metadata = (self.bundle / "module.prop").read_bytes()
        stale = self.bundle.with_name("previous")
        stale.mkdir()
        (stale / "engine-version").write_text("v1.100.0\n")
        self.env["BUNDLE_DIR"] = str(stale)

        self.summarize()

        self.assert_description(
            "[service is running] Tailscale(v1.102.3) | 100.64.0.10 | UI ready"
            " | runtime active; Manager promotion pending")
        self.assertEqual(staged_before, {path: path.read_bytes() for path in self.staged.iterdir()})
        self.assertEqual(selected_metadata, (self.bundle / "module.prop").read_bytes())
        self.assertEqual(self.bundle, self.current.readlink())
        self.assertTrue((self.module / "update").is_file())

    def test_stale_caller_cannot_report_unselected_update_as_active(self):
        self.stage_update()
        rollback = self.bundle.with_name("rollback")
        shutil.copytree(self.bundle, rollback)
        (rollback / "module.prop").write_text("version=2.1.0\nversionCode=20000010\n")
        self.current.unlink()
        self.current.symlink_to(rollback, target_is_directory=True)

        self.summarize()

        self.assert_description(
            "[service is running] Tailscale(v1.102.3) | 100.64.0.10 | UI ready"
            " | update pending reboot: 2.1.0")

    def test_equivalent_version_codes_match_despite_different_version_text(self):
        self.stage_update()
        (self.staged / "module.prop").write_text("version=Different label\nversionCode=020100000\n")

        self.summarize()

        self.assert_description(
            "[service is running] Tailscale(v1.102.3) | 100.64.0.10 | UI ready"
            " | runtime active; Manager promotion pending")

    def test_matching_caller_bundle_is_not_active_without_current(self):
        self.stage_update()
        self.current.unlink()

        self.summarize()

        self.assert_description(
            "[service is running] Tailscale(v1.102.3) | 100.64.0.10 | UI ready"
            " | update pending reboot: 2.1.0")

    def test_switch_during_ip_probe_does_not_publish_stale_summary(self):
        self.stage_update()
        successor = self.bundle.with_name("successor")
        successor.mkdir()
        next_current = self.state / ".current-next"
        next_current.symlink_to(successor, target_is_directory=True)
        self.on_ip_query = lambda: next_current.replace(self.current)

        self.summarize()

        self.assertEqual(successor, self.current.readlink())
        self.assertEqual(self.original, self.module_prop.read_text())

    def test_matching_version_text_is_not_release_identity(self):
        for name, selected, staged in (
            ("module.prop", "version=2.1.0\nversionCode=20000010\n", "version=2.1.0\nversionCode=20100000\n"),
            ("module.prop", "version=2.1.0\n", "version=2.1.0\n"),
            ("module.prop", "version=2.1.0\nversionCode=invalid\n", "version=2.1.0\nversionCode=invalid\n"),
            ("bundle.sha256", "different manifest\n", None),
            ("bundle.sha256", None, None),
            ("bundle.sha256", "", ""),
        ):
            with self.subTest(name=name, selected=selected, staged=staged):
                self.stage_update()
                if selected is None:
                    (self.bundle / name).unlink()
                else:
                    (self.bundle / name).write_text(selected)
                if staged is not None:
                    (self.staged / name).write_text(staged)
                self.summarize()
                self.assert_description(
                    "[service is running] Tailscale(v1.102.3) | 100.64.0.10 | UI ready"
                    " | update pending reboot: 2.1.0")

    def test_matching_update_waits_for_tailnet_ip_before_cleanup(self):
        self.stage_update()
        self.reply = b""

        self.summarize()

        self.assert_description(
            "[service is running] Tailscale(v1.102.3) | cleanup awaits tailnet IP | UI ready"
            " | runtime active; Manager promotion pending")
        self.assertEqual([b"ip -4\n"], self.requests)

    def test_nonrunning_lifecycle_never_invokes_cli_even_with_socket(self):
        for lifecycle, state in (("stopped", "service is stopped"),
                                 ("disabled", "service is stopped"),
                                 ("failed", "service failed"),
                                 ("starting", "service starting"),
                                 ("", "service unknown")):
            with self.subTest(lifecycle=lifecycle):
                self.lifecycle.write_text(lifecycle + "\n")
                self.summarize()
                self.assert_description(
                    f"[{state}] Tailscale(v1.102.3) | not ready | UI ready")
                self.assertFalse(self.calls.exists(), "stopped daemon was queried")
        self.assertEqual([], self.requests)

    def test_missing_or_nonsocket_path_never_invokes_cli(self):
        self.stop_server()
        self.socket_path.unlink()
        for lifecycle in ("running", "degraded"):
            for regular_file in (False, True):
                with self.subTest(lifecycle=lifecycle, regular_file=regular_file):
                    self.lifecycle.write_text(lifecycle + "\n")
                    if regular_file:
                        self.socket_path.touch()
                    self.summarize()
                    state = "service is running" if lifecycle == "running" else "service degraded"
                    self.assert_description(
                        f"[{state}] Tailscale(v1.102.3) | not ready | UI unavailable")
                    self.assertFalse(self.calls.exists(), "missing socket launched CLI")
                    self.socket_path.unlink(missing_ok=True)

    def assert_hanging_cli_is_bounded(self, deadline=3):
        self.reply = b"HANG\n"
        started = time.monotonic()
        self.summarize(timeout=deadline)
        self.assertLess(time.monotonic() - started, deadline)
        self.assertEqual(1, len(self.calls.read_text().splitlines()))
        self.assertEqual(1, len(self.requests))
        self.assert_description(
            "[service is running] Tailscale(v1.102.3) | not ready | UI ready")

    def test_hanging_cli_uses_timeout_when_busybox_is_not_executable(self):
        busybox = self.root / "busybox"
        busybox.touch(mode=0o644)
        self.env["KST_BUSYBOX"] = str(busybox)
        self.assert_hanging_cli_is_bounded()

    def test_hanging_cli_has_default_timeout(self):
        self.env.pop("KST_CLI_TIMEOUT")
        self.assert_hanging_cli_is_bounded(deadline=6)

    @unittest.skipUnless(shutil.which("busybox"), "BusyBox required for its real timeout applet")
    def test_hanging_cli_uses_busybox_without_path_timeout(self):
        self.env["KST_BUSYBOX"] = shutil.which("busybox")
        commands = self.root / "commands"
        commands.mkdir()
        for name in ("awk", "chmod", "cut", "head", "mv", "readlink", "rm", "sed", "tr"):
            (commands / name).symlink_to(shutil.which(name))
        self.env["PATH"] = str(commands)
        self.assert_hanging_cli_is_bounded()

    def test_version_and_ip_are_sanitized(self):
        version = "v1.102.3|" + "x" * 70
        (self.bundle / "engine-version").write_text(version + "\nignored\n")
        self.reply = b"100.64.0.10|label\rvalue\n100.64.0.99\n"
        self.summarize()
        self.assert_description(
            f"[service is running] Tailscale({version.replace('|', ' ')[:48]})"
            " | 100.64.0.10 label value | UI ready")

    def test_missing_description_is_appended(self):
        original = self.original.replace("description=original description\n", "")
        self.module_prop.write_text(original)
        self.summarize()
        self.assertEqual(original + "description=[service is running] Tailscale(v1.102.3)"
                         " | 100.64.0.10 | UI ready\n", self.module_prop.read_text())


if __name__ == "__main__":
    unittest.main()
