import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MetadataTests(unittest.TestCase):
    def test_bundled_tailscale_has_standalone_android_support(self):
        self.assertEqual(
            'v1.104.7',
            (ROOT / 'files/VERSION.txt').read_text(encoding='ascii').strip(),
        )

    def test_current_release_identity(self):
        props = dict(line.split('=', 1) for line in (ROOT / 'module.prop').read_text().splitlines() if '=' in line)
        self.assertEqual('kernelsu-tailscaled', props['id'])
        self.assertEqual('KernelSU-Tailscaled', props['name'])
        self.assertIn('/KernelSU-Tailscaled/', props['updateJson'])
        self.assertNotEqual('update.json', props['updateJson'].rsplit('/', 1)[-1])

    def test_retired_feed_is_absent_and_stable_feed_is_consistent(self):
        self.assertFalse((ROOT / 'update.json').exists())
        meta = json.loads((ROOT / 'update-kernelsu.json').read_text())
        props = dict(line.split('=', 1) for line in (ROOT / 'module.prop').read_text().splitlines() if '=' in line)
        source = json.loads((ROOT / 'module.json').read_text())['sourceUrl']
        self.assertRegex(meta['version'], r'^v[0-9]+\.[0-9]+\.[0-9]+(?:[_-][A-Za-z0-9._-]+)?$')
        self.assertLessEqual(meta['versionCode'], int(props['versionCode']))
        self.assertEqual(
            f"{source}/releases/download/{meta['version']}/KernelSU-Tailscaled-{meta['version']}.zip",
            meta['zipUrl'],
        )
        if '-' not in props['version']:
            self.assertEqual(f"v{props['version']}", meta['version'])
            self.assertEqual(int(props['versionCode']), meta['versionCode'])

    def test_module_and_feed_versions_carry_the_bundled_engine(self):
        props = dict(line.split('=', 1) for line in (ROOT / 'module.prop').read_text().splitlines() if '=' in line)
        engine = (ROOT / 'files/VERSION.txt').read_text(encoding='ascii').strip()
        self.assertRegex(engine, r'^v[0-9]+\.[0-9]+\.[0-9]+$')
        self.assertTrue(props['version'].endswith('_tailscale' + engine[1:]),
                        f"module version {props['version']} does not carry {engine}")
        meta = json.loads((ROOT / 'update-kernelsu.json').read_text())
        self.assertEqual(f"v{props['version']}", meta['version'])
