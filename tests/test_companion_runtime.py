import importlib.util
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_apk_builder():
    spec = importlib.util.spec_from_file_location('tailmate_apk_build', ROOT / 'android/build.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CompanionUnsignedBuildTests(unittest.TestCase):
    """--unsigned must need no keystore and emit the same aligned bytes signed mode keeps."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='tm-apk-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        (self.root / 'android/src').mkdir(parents=True)
        shutil.copyfile(ROOT / 'module.prop', self.root / 'module.prop')
        shutil.copyfile(ROOT / 'android/AndroidManifest.xml', self.root / 'android/AndroidManifest.xml')
        (self.root / 'android/src/Stub.java').write_text('class Stub {}\n')
        self.builder = load_apk_builder()
        self.builder.ROOT = self.root
        self.tools = Path(self.temp.name) / 'build-tools'
        self.builder.sdk_tools = lambda sdk, pinned: (self.tools, Path(self.temp.name) / 'android.jar')
        self.builder.jdk_tool = lambda name, pinned: Path(name)
        self.calls = []
        self.builder.run = self.fake_run

    def fake_run(self, command, *, env=None, private=False):
        name, args = Path(command[0]).name, [str(part) for part in command[1:]]
        self.calls.append(name)
        if name == 'aapt2' and args[0] == 'link':
            self.manifest = Path(args[args.index('--manifest') + 1]).read_text()
            with zipfile.ZipFile(args[args.index('-o') + 1], 'w') as apk:
                apk.writestr('AndroidManifest.xml', b'binary-manifest')
                apk.writestr('resources.arsc', b'table')
        elif name == 'd8':
            (Path(args[args.index('--output') + 1]) / 'classes.dex').write_bytes(b'dex\n')
        elif name == 'zipalign' and args[0] == '-f':
            shutil.copyfile(args[-2], args[-1])
        elif name == 'apksigner':
            raise AssertionError('unsigned mode must not sign')

    def test_unsigned_mode_needs_no_signing_and_matches_reproducibility_copy(self):
        output = Path(self.temp.name) / 'dist'
        environment = {name: os.environ.pop(name) for name in self.builder.SIGNING_VARIABLES
                       if name in os.environ}
        self.addCleanup(os.environ.update, environment)
        args = self.builder.argparse.Namespace(sdk=Path('sdk'), output=output, debug=False,
                                               unsigned=True, debug_keystore=Path('unused'))
        version, _ = self.builder.module_version()

        built = self.builder.build(args)

        self.assertEqual(output.resolve() / f'TailMate-companion-v{version}-unsigned.apk', built)
        self.assertEqual((self.root / 'android/build/companion-unsigned.apk').read_bytes(), built.read_bytes())
        with zipfile.ZipFile(built) as apk:
            self.assertEqual(['AndroidManifest.xml', 'classes.dex', 'resources.arsc'], apk.namelist())
        self.assertIn('debuggable="false"', self.manifest)
        self.assertNotIn('apksigner', self.calls)

    def test_unsigned_and_debug_are_mutually_exclusive(self):
        result = subprocess.run([sys.executable, str(ROOT / 'android/build.py'), '--debug', '--unsigned'],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(2, result.returncode)
        self.assertIn('not allowed with argument', result.stderr)


@unittest.skipUnless(os.name == 'posix' and Path('/proc').exists(), 'Linux runtime ownership tests')
class CompanionRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='tm-companion-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / 'module'
        self.state = self.root / 'state'
        self.bundle.mkdir()
        shutil.copytree(ROOT / 'tailscale', self.bundle / 'tailscale')
        shutil.copytree(ROOT / 'scripts', self.bundle / 'scripts')
        shutil.copyfile(ROOT / 'control.sh', self.bundle / 'control.sh')
        (self.bundle / 'engine-version').write_text('1.104.2\n')
        self.bin = self.bundle / 'bin'
        self.bin.mkdir()
        (self.state / 'run').mkdir(parents=True)
        (self.state / 'config').mkdir()
        (self.state / 'logs').mkdir()
        self.env = dict(os.environ, MODDIR=str(self.bundle), TS_DIR=str(self.state),
                        BUNDLE_DIR=str(self.bundle), FIXTURE_ROOT=str(self.root),
                        TM_SHELL='/bin/sh', TM_BUSYBOX='/nonexistent',
                        TM_LEGACY_DIR=str(self.root / 'legacy'),
                        TM_CLI_TIMEOUT='2', TM_LOCK_TIMEOUT='1')
        self.targets = '100.100.1.2\tpeer\n100.100.1.3\tother\toffline; last seen 1m0s ago\n'
        (self.root / 'targets').write_text(self.targets)
        cli = self.bin / 'tailscale'
        cli.write_text('#!' + sys.executable + '\n' + '''import json, os, sys, time
from pathlib import Path
root = Path(os.environ['FIXTURE_ROOT'])
args = sys.argv[1:]
if '--targets' in args:
    sys.stdout.write((root / 'targets').read_text())
    sys.exit(int(os.environ.get('TARGETS_EXIT', '0')))
(root / 'send.json').write_text(json.dumps({'argv': args, 'pid': os.getpid(), 'cli': sys.argv[0]}))
if os.environ.get('SEND_BLOCK'):
    while True: time.sleep(0.1)
(root / 'payload').write_bytes(sys.stdin.buffer.read())
sys.exit(int(os.environ.get('SEND_EXIT', '0')))
''')
        cli.chmod(0o755)
        daemon = self.bin / 'tailscaled'
        daemon.write_text('#!' + sys.executable + '\nimport time\nwhile True: time.sleep(0.1)\n')
        daemon.chmod(0o755)
        self.daemon = subprocess.Popen([str(daemon), '--socket=' + str(self.state / 'run/tailscaled.sock')])
        self.addCleanup(self.stop_daemon)
        result = self.common('write_pid "$DAEMON_PID" "$1" "$TAILSCALED_BIN" "--socket=$SOCKET_FILE"', str(self.daemon.pid))
        self.assertEqual(0, result.returncode, result.stderr)

    def stop_daemon(self):
        if self.daemon.poll() is None:
            self.daemon.terminate()
        self.daemon.wait(timeout=5)

    def common(self, body, *arguments):
        return subprocess.run(['/bin/sh', '-c', '. "$BUNDLE_DIR/tailscale/scripts/common.sh"; ' + body,
                               'fixture', *arguments], env=self.env, capture_output=True, text=True, timeout=10)

    def command(self, action, data=b'', **environment):
        return subprocess.run(['/bin/sh', str(self.bundle / 'control.sh'), action], input=data,
                              env=dict(self.env, **environment), cwd=self.root, capture_output=True, timeout=10)

    def test_targets_keep_official_annotations_and_failures(self):
        result = self.command('taildrop-targets')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.targets.encode(), result.stdout)
        self.assertEqual(19, self.command('taildrop-targets', TARGETS_EXIT='19').returncode)
        result = self.command('taildrop-send', b'100.100.1.2\nfile.txt\npayload', TARGETS_EXIT='19')
        self.assertEqual(19, result.returncode, result.stderr)
        self.assertFalse((self.root / 'send.json').exists())

    def test_send_preserves_binary_stdin_argument_boundaries_and_cli_failure(self):
        filename = "报告 ' $(touch injected).txt"
        payload = bytes(range(256)) * 8 + b'\n\x00last\n'
        result = self.command('taildrop-send', b'100.100.1.2\n' + filename.encode() + b'\n' + payload,
                              SEND_EXIT='23')
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual(payload, (self.root / 'payload').read_bytes())
        call = json.loads((self.root / 'send.json').read_text())
        self.assertEqual(['--socket=' + str(self.state / 'run/tailscaled.sock'), 'file', 'cp',
                          '--name=' + filename, '-', '100.100.1.2:'], call['argv'])
        self.assertFalse((self.root / 'injected').exists())

    def test_send_rejects_malformed_headers_and_noneligible_targets(self):
        for header in [b'100.100.1.9\nfile.txt\n', b'100.100.1.02\nfile.txt\n',
                       b'100.100.1.2;echo bad\nfile.txt\n', b'100.100.1.2\n../file\n',
                       b'100.100.1.2\nfile\\name\n', b'100.100.1.2\nfile\rname\n',
                       b'100.100.1.2\nfile\x00name\n', b'100.100.1.2\nfile\xc2\x85name\n',
                       b'100.100.1.2\nfile\xffname\n', b'100.100.1.2\n\n',
                       b'100.100.1.2\n' + b'x' * 256 + b'\n', b'100.100.1.2\nunterminated']:
            with self.subTest(header=header):
                self.assertNotEqual(0, self.command('taildrop-send', header).returncode)
                self.assertFalse((self.root / 'send.json').exists())

    def test_disabled_and_migration_states_block_both_actions(self):
        for marker in [self.bundle / 'disable', self.bundle / 'remove',
                       self.state / 'migration-required', self.state / 'migration.pending',
                       self.state / 'migration-awaiting-enable']:
            marker.touch()
            try:
                for action in ['taildrop-targets', 'taildrop-send']:
                    with self.subTest(marker=marker.name, action=action):
                        self.assertNotEqual(0, self.command(action, b'100.100.1.2\nfile.txt\n').returncode)
            finally:
                marker.unlink()
        self.assertFalse((self.root / 'send.json').exists())

    def test_send_uses_effective_engine(self):
        (self.bundle / 'tailscale/config/engine-compat').unlink(missing_ok=True)
        engine = self.state / 'engines/1.104.3'
        (engine / 'bin').mkdir(parents=True)
        shutil.copyfile(self.bin / 'tailscale', engine / 'bin/tailscale')
        shutil.copyfile(self.bin / 'tailscaled', engine / 'bin/tailscaled')
        for path in (engine / 'bin').iterdir():
            path.chmod(0o755)
        (engine / 'engine-version').write_text('1.104.3\n')
        (self.state / 'engine-current').symlink_to(engine)
        self.stop_daemon()
        self.daemon = subprocess.Popen([str(engine / 'bin/tailscaled'), '--socket=' + str(self.state / 'run/tailscaled.sock')])
        record = self.common('write_pid "$DAEMON_PID" "$1" "$TAILSCALED_BIN" "--socket=$SOCKET_FILE"', str(self.daemon.pid))
        self.assertEqual(0, record.returncode, record.stderr)
        result = self.command('taildrop-send', b'100.100.1.3\nfile.txt\ncontents')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(engine / 'bin/tailscale'), json.loads((self.root / 'send.json').read_text())['cli'])

    def test_send_with_compat_marker_rejects_newer_external_engine(self):
        (self.bundle / 'tailscale/config/engine-compat').write_text('android-compat-1\n')
        engine = self.state / 'engines/1.104.3'
        (engine / 'bin').mkdir(parents=True)
        shutil.copyfile(self.bin / 'tailscale', engine / 'bin/tailscale')
        shutil.copyfile(self.bin / 'tailscaled', engine / 'bin/tailscaled')
        (engine / 'engine-version').write_text('1.104.3\n')
        (self.state / 'engine-current').symlink_to(engine)
        record = self.common('write_pid "$DAEMON_PID" "$1" "$TAILSCALED_BIN" "--socket=$SOCKET_FILE"', str(self.daemon.pid))
        self.assertNotEqual(0, record.returncode)
        self.assertIn('downgrade rejected', record.stderr)
        result = self.command('taildrop-send', b'100.100.1.3\nfile.txt\ncontents')
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b'downgrade rejected', result.stderr)
    def test_send_exec_preserves_pid_and_does_not_hold_controller_lock(self):
        send = subprocess.Popen(['/bin/sh', str(self.bundle / 'control.sh'), 'taildrop-send'],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=dict(self.env, SEND_BLOCK='1'))
        self.addCleanup(lambda: send.poll() is not None or send.kill())
        try:
            send.stdin.write(b'100.100.1.2\nfile.txt\n')
            send.stdin.flush()
            deadline = time.monotonic() + 5
            while not (self.root / 'send.json').exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            call = json.loads((self.root / 'send.json').read_text())
            self.assertEqual(send.pid, call['pid'])
            result = self.common('with_control_lock true')
            self.assertEqual(0, result.returncode, result.stderr)
            send.terminate()
            send.wait(timeout=5)
            self.assertEqual(-signal.SIGTERM, send.returncode)
        finally:
            if send.poll() is None:
                send.kill()
                send.wait(timeout=5)
            send.stdin.close()
            send.stdout.close()
            send.stderr.close()


@unittest.skipUnless(os.name == 'posix' and shutil.which('awk'), 'POSIX awk log tests')
class CompanionReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='tm-receipt-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        am = self.bin / 'am'
        am.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                      'from pathlib import Path\n'
                      'with (Path(os.environ["FIXTURE_ROOT"]) / "broadcasts").open("a") as out:\n'
                      '    out.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                      'sys.exit(int(os.environ.get("BROADCAST_EXIT", "0")))\n')
        am.chmod(0o755)
        self.env = dict(os.environ, LC_ALL='C', FIXTURE_ROOT=str(self.root),
                        PATH=str(self.bin) + ':' + os.environ['PATH'])

    def logger(self, text, **environment):
        return subprocess.run(['awk', '-v', 'taildrop_notify=1', '-v', 'destination=' + str(self.root / 'taildrop.log'),
                               '-f', str(ROOT / 'tailscale/scripts/taildrop-filter.awk'),
                               '-f', str(ROOT / 'tailscale/scripts/log-filter.awk')],
                              input=text, text=True, capture_output=True,
                              env=dict(self.env, **environment), cwd=self.root, timeout=10)

    def broadcasts(self):
        path = self.root / 'broadcasts'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_only_complete_unambiguous_receipts_broadcast_safely_quoted_basename(self):
        filename = "报告's $(touch injected).txt"
        result = self.logger('waiting for file...wrote ' + filename + ' as ' + filename + ' (7 bytes)\n'
                             'moved 1/1 files\nwaiting for file...')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([['broadcast', '--user', 'current', '--include-stopped-packages',
                           '-n', 'com.askingmeric.tailmate/.ReceiveReceiver',
                           '-a', 'com.askingmeric.tailmate.RECEIVED', '--es', 'filename', filename]],
                         self.broadcasts())
        self.assertFalse((self.root / 'injected').exists())

    def test_receipt_names_containing_as_and_conflict_renames_are_supported(self):
        result = self.logger('wrote photo as sent.jpg as photo as sent.jpg (4 bytes)\n'
                             'wrote photo as sent.jpg as photo as sent (1).jpg (4 bytes)\n'
                             'wrote .hidden as  (99).hidden (0 bytes)\n'
                             'moved 3/3 files\n')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['photo as sent.jpg', 'photo as sent (1).jpg', ' (99).hidden'],
                         [call[-1] for call in self.broadcasts()])


    def test_errors_multiline_traversal_and_ambiguous_events_never_broadcast(self):
        result = self.logger('waiting for file...\nmoved 0/0 files\n'
                             'failed to write file.txt: disk full\n'
                             'wrote x as ../x (4 bytes)\n'
                             'wrote x as a\\b (4 bytes)\n'
                             'wrote a as b as c (4 bytes)\n'
                             'wrote x\ny as x\ny (4 bytes)\n'
                             'wrote x as x (4 bytes) suffix\n'
                             'wrote evil\nwrote fake as fake (4 bytes)\n as evil\n'
                             'wrote fake as fake (4 bytes)\n (4 bytes)\n'
                             'moved 1/1 files\n'
                             'wrote ' + 'x' * 256 + ' as x (4 bytes)\n')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.broadcasts())

    def test_next_batch_notifies_after_post_summary_receive_error(self):
        result = self.logger('waiting for file...wrote good.txt as good.txt (4 bytes)\n'
                             'moved 1/2 files\n'
                             'failed to write queued.txt: disk full\n'
                             'wrote queued.txt as queued.txt (7 bytes)\n'
                             'moved 1/1 files\n')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['good.txt', 'queued.txt'], [call[-1] for call in self.broadcasts()])

    def test_failed_optional_broadcast_keeps_filtered_bounded_logging(self):
        result = self.logger('wrote x as x (4 bytes)\nmoved 1/1 files\n' +
                             ('tskey-auth-SECRET\n' + 'x' * 2048 + '\n') * 150,
                             BROADCAST_EXIT='1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(self.broadcasts()))
        log = self.root / 'taildrop.log'
        self.assertLessEqual(log.stat().st_size, 262144)
        self.assertNotIn('SECRET', log.read_text())


if __name__ == '__main__':
    unittest.main()
