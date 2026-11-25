import hashlib
import os
import unittest
from pathlib import Path

from tests import test_complete_install


IDENTITY = b'{"fixture":"predecessor-identity"}\n'
CONFIG = b"MODE=native\nDEVICE_HOSTNAME=handed-over\n"


def snapshot(root):
    return {path.relative_to(root).as_posix(): (hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None)
            for path in sorted(root.rglob("*"))}


@unittest.skipUnless(os.name == "posix", "Execute under Linux/WSL for POSIX installer behavior")
class PredecessorHandoverTests(unittest.TestCase):
    def setUp(self):
        # Reuse the complete-install harness (real ZIP, customize.sh, install-runtime.sh)
        # by composition so its own tests are not collected a second time here.
        self.harness = test_complete_install.CompleteInstallTests(
            "test_same_zip_completes_two_independent_fresh_installs")
        self.harness.setUp()
        self.addCleanup(self.harness.doCleanups)

    def device(self, name):
        env = self.harness.device(name)
        base = Path(env["TM_ACTIVE_DIR"]).parents[1]
        env["TM_PREDECESSOR_DIR"] = str(base / "kernelsu-tailscaled")
        env["TM_PREDECESSOR_MODULE"] = str(base / "modules/kernelsu-tailscaled")
        return env

    def predecessor(self, env):
        state = Path(env["TM_PREDECESSOR_DIR"])
        (state / "config").mkdir(parents=True)
        (state / "tailscaled.state").write_bytes(IDENTITY)
        (state / "config/module.conf").write_bytes(CONFIG)
        (state / "hostname.initialized").touch()
        (state / "profile-data/nested").mkdir(parents=True)
        (state / "profile-data/nested/prefs").write_text("profile\n")
        (state / "files").mkdir()
        (state / "files/pending.txt").write_text("taildrop\n")
        # Generations, engines and runtime files stay with the predecessor.
        (state / "releases/1-abc").mkdir(parents=True)
        (state / "run").mkdir()
        (state / "run/lifecycle").write_text("running\n")
        module = Path(env["TM_PREDECESSOR_MODULE"])
        module.mkdir(parents=True)
        (module / "module.prop").write_text("id=kernelsu-tailscaled\n")
        (module / "control.sh").write_text(
            '#!/bin/sh\nprintf "%s:%s\\n" "$1" "$TS_DIR" >> "$TRACE.predecessor"\n')
        return state, module

    def test_install_hands_over_predecessor_identity_and_settings(self):
        env = self.device("handover")
        old_state, old_module = self.predecessor(env)
        before = snapshot(old_state)
        result = self.harness.install(env)
        state = Path(env["TS_DIR"])
        self.assertEqual(IDENTITY, (state / "tailscaled.state").read_bytes())
        self.assertEqual(0o600, (state / "tailscaled.state").stat().st_mode & 0o777)
        self.assertEqual(CONFIG, (state / "config/module.conf").read_bytes())
        self.assertTrue((state / "hostname.initialized").is_file())
        self.assertEqual("profile\n", (state / "profile-data/nested/prefs").read_text())
        self.assertEqual("taildrop\n", (state / "files/pending.txt").read_text())
        self.assertEqual("kernelsu-tailscaled\n", (state / "handover-origin").read_text())
        self.assertFalse(list(state.glob(".handover.*")))
        self.assertFalse((state / "releases/1-abc").exists())
        # Copy, never move: the predecessor state is byte-for-byte intact.
        self.assertEqual(before, snapshot(old_state))
        self.assertTrue((old_module / "remove").is_file())
        self.assertEqual(f"stop:{old_state}\n", Path(env["TRACE"] + ".predecessor").read_text())
        self.assertIn("copied from KernelSU-Tailscaled", result.stdout)
        self.assertIn(str(old_state), result.stdout)
        # The handed-over identity counts as present, so the runtime starts.
        self.assertTrue((state / "run/fake-running").is_file())
        self.assertFalse((state / "migration-required").exists())

        # Reinstalling TailMate over itself is a no-op for the handover.
        (old_module / "remove").unlink()
        self.harness.install(env)
        self.assertFalse((old_module / "remove").exists())
        self.assertEqual(f"stop:{old_state}\n", Path(env["TRACE"] + ".predecessor").read_text())
        self.assertEqual(IDENTITY, (state / "tailscaled.state").read_bytes())

    def test_existing_tailmate_identity_leaves_predecessor_untouched(self):
        env = self.device("existing")
        old_state, old_module = self.predecessor(env)
        state = Path(env["TS_DIR"])
        state.mkdir(parents=True)
        own = b'{"fixture":"tailmate-identity"}\n'
        (state / "tailscaled.state").write_bytes(own)
        before_state = snapshot(old_state)
        before_module = snapshot(old_module)
        result = self.harness.install(env)
        self.assertEqual(own, (state / "tailscaled.state").read_bytes())
        self.assertFalse((state / "config/module.conf").exists())
        self.assertFalse((state / "profile-data").exists())
        self.assertFalse((state / "handover-origin").exists())
        self.assertEqual(before_state, snapshot(old_state))
        self.assertEqual(before_module, snapshot(old_module))
        self.assertFalse((old_module / "remove").exists())
        self.assertFalse(Path(env["TRACE"] + ".predecessor").exists())
        self.assertNotIn("KernelSU-Tailscaled", result.stdout)

    def test_fresh_install_without_predecessor_is_noop(self):
        env = self.device("fresh")
        result = self.harness.install(env)
        state = Path(env["TS_DIR"])
        self.harness.assert_usable(env)
        self.assertFalse((state / "tailscaled.state").exists())
        self.assertFalse((state / "handover-origin").exists())
        self.assertFalse(Path(env["TM_PREDECESSOR_DIR"]).exists())
        self.assertFalse(Path(env["TM_PREDECESSOR_MODULE"]).exists())
        self.assertNotIn("KernelSU-Tailscaled", result.stdout)

    def test_symlinked_predecessor_state_is_refused(self):
        env = self.device("symlink")
        old_state, old_module = self.predecessor(env)
        outside = Path(env["TM_ACTIVE_DIR"]).parents[1] / "outside.state"
        outside.write_bytes(IDENTITY)
        (old_state / "tailscaled.state").unlink()
        (old_state / "tailscaled.state").symlink_to(outside)
        result = self.harness.install(env, ok=False)
        self.assertIn("symlink-predecessor-state", result.stderr)
        state = Path(env["TS_DIR"])
        self.assertFalse((state / "tailscaled.state").exists())
        self.assertFalse((state / "handover-origin").exists())
        self.assertFalse((state / "current").exists())
        self.assertFalse((old_module / "remove").exists())
        self.assertFalse(Path(env["TRACE"] + ".predecessor").exists())
        self.assertEqual(IDENTITY, outside.read_bytes())


if __name__ == "__main__":
    unittest.main()
