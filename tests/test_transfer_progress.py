import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def jdk_tool(name):
    home = os.environ.get('JAVA_HOME')
    if home and (Path(home) / 'bin' / name).is_file():
        return str(Path(home) / 'bin' / name)
    return shutil.which(name)


@unittest.skipUnless(jdk_tool('javac') and jdk_tool('java'), 'JDK required for pure-Java companion tests')
class TransferProgressTests(unittest.TestCase):
    def test_real_progress_model_handles_sizes_rates_stalls_and_file_reset(self):
        with tempfile.TemporaryDirectory(prefix='tm-progress-') as directory:
            compiled = subprocess.run(
                [jdk_tool('javac'), '--release', '8', '-d', directory,
                 str(ROOT / 'android/src/com/askingmeric/tailmate/TransferProgress.java'),
                 str(ROOT / 'tests/runtime/TransferProgressTest.java')],
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                [jdk_tool('java'), '-cp', directory, 'com.askingmeric.tailmate.TransferProgressTest'],
                capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_companion_input_rules_match_the_webui(self):
        with tempfile.TemporaryDirectory(prefix='tm-input-') as directory:
            compiled = subprocess.run(
                [jdk_tool('javac'), '--release', '8', '-d', directory,
                 str(ROOT / 'android/src/com/askingmeric/tailmate/ModuleInput.java'),
                 str(ROOT / 'tests/runtime/ModuleInputTest.java')],
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                [jdk_tool('java'), '-cp', directory, 'com.askingmeric.tailmate.ModuleInputTest'],
                capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
