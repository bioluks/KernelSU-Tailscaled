import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def engine_version() -> str:
    # scripts/build_engine.py is the single source of the engine version; the
    # binaries and files/VERSION.txt are build outputs, not committed.
    source = (ROOT / 'scripts/build_engine.py').read_text(encoding='utf-8')
    return 'v' + re.search(r'^VERSION = "([0-9.]+)"$', source, re.MULTILINE).group(1)


class MetadataTests(unittest.TestCase):
    def test_bundled_tailscale_has_standalone_android_support(self):
        self.assertEqual('v1.104.7', engine_version())

    def test_engine_lock_matches_build_recipe(self):
        lock = json.loads((ROOT / 'engine/engine-build.json').read_text())
        self.assertEqual(engine_version(), 'v' + lock['upstreamVersion'])

    def test_current_release_identity(self):
        props = dict(line.split('=', 1) for line in (ROOT / 'module.prop').read_text().splitlines() if '=' in line)
        self.assertEqual('TailMate', props['id'])
        self.assertEqual('TailMate', props['name'])
        self.assertEqual('https://raw.githubusercontent.com/bioluks/TailMate/master/update.json',
                         props['updateJson'])

    def test_stable_feed_is_consistent(self):
        meta = json.loads((ROOT / 'update.json').read_text())
        props = dict(line.split('=', 1) for line in (ROOT / 'module.prop').read_text().splitlines() if '=' in line)
        source = json.loads((ROOT / 'module.json').read_text())['sourceUrl']
        self.assertRegex(meta['version'], r'^v[0-9]+\.[0-9]+\.[0-9]+(?:[_-][A-Za-z0-9._-]+)?$')
        self.assertLessEqual(meta['versionCode'], int(props['versionCode']))
        self.assertEqual(
            f"{source}/releases/download/{meta['version']}/TailMate-{meta['version']}.zip",
            meta['zipUrl'],
        )
        if '-' not in props['version']:
            self.assertEqual(f"v{props['version']}", meta['version'])
            self.assertEqual(int(props['versionCode']), meta['versionCode'])

    def test_predecessor_bridge_feed_mirrors_update_json(self):
        # Installs of the predecessor (KernelSU-Tailscaled) poll update-kernelsu.json
        # through the redirected old repository URL until it is removed.
        self.assertEqual(
            json.loads((ROOT / 'update.json').read_text()),
            json.loads((ROOT / 'update-kernelsu.json').read_text()),
        )

    def test_module_and_feed_versions_carry_the_bundled_engine(self):
        props = dict(line.split('=', 1) for line in (ROOT / 'module.prop').read_text().splitlines() if '=' in line)
        engine = engine_version()
        self.assertRegex(engine, r'^v[0-9]+\.[0-9]+\.[0-9]+$')
        self.assertTrue(props['version'].endswith('_tailscale' + engine[1:]),
                        f"module version {props['version']} does not carry {engine}")
        meta = json.loads((ROOT / 'update.json').read_text())
        self.assertEqual(f"v{props['version']}", meta['version'])
