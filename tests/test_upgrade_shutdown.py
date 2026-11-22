import hashlib
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MKSH = shutil.which('mksh')
FLOCK = shutil.which('flock')

# Both sides use the real util-linux flock on the FD that mksh passes down.
# Gates stop execution before or after a particular attempt, never substitute
# an acquisition or change its exit status.
FLOCK_GATE = r'''
import os
import subprocess
import sys
import time
from pathlib import Path

root = Path(os.environ['FIXTURE_ROOT'])
real_flock = os.environ['REAL_FLOCK']
role = os.environ.get('LOCK_ROLE')
args = sys.argv[1:]
if args != ['-n', '9'] or not role:
    os.execv(real_flock, [real_flock, *args])


def gate(name):
    deadline = time.monotonic() + 25
    while not (root / name).exists():
        if time.monotonic() >= deadline:
            sys.exit(f'timed out waiting for {name}')
        time.sleep(0.01)


first = not any((root / f'{role}.{event}').exists() for event in ('acquired', 'contended'))
if first and role == 'old':
    (root / 'old.ready').touch()
    gate('old.try')
result = subprocess.run([real_flock, *args], pass_fds=(9,)).returncode
if first:
    if result not in (0, 1):
        sys.exit(result)  # A broken FD is not contention.
    outcome = 'contended' if result == 1 else 'acquired'
    (root / f'{role}.{outcome}').touch()
    gate(f'{role}.proceed')
elif not result and role == 'old':
    (root / 'old.reacquired').touch()
sys.exit(result)
'''

# Only hold the old supervisor's idle sleep. Other sleeps (including the
# controller lock's retry interval) run normally. A foreground sleep would
# prevent mksh from taking the supervisor's TERM trap while this gate is held.
SLEEP_GATE = r'''
import os
import signal
import sys
from pathlib import Path

root = Path(os.environ['FIXTURE_ROOT'])
record = Path(os.environ['TS_DIR']) / 'run/supervisor.pid'
if (os.environ.get('LOCK_ROLE') == 'old' and record.is_file()
        and record.read_text().partition('\n')[0] == str(os.getppid())):
    def on_term(*_):
        (root / 'old.idle-terminated').touch()
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        os.kill(os.getpid(), signal.SIGTERM)

    signal.signal(signal.SIGTERM, on_term)
    (root / 'old.idle').touch()
    signal.alarm(25)
    signal.pause()
    sys.exit('idle barrier unexpectedly returned')
os.execv(os.environ['REAL_SLEEP'], [os.environ['REAL_SLEEP'], *sys.argv[1:]])
'''


@unittest.skipUnless(sys.platform == 'linux' and MKSH and FLOCK,
                     'Requires Linux, mksh and flock')
class UpgradeShutdownTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='kst-upgrade-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.state = self.root / 'state'
        self.run_dir = self.state / 'run'
        self.mod = self.root / 'module'
        self.old = self.state / 'releases/old'
        self.staged = self.root / 'staged'
        self.commands = self.root / 'commands'
        for directory in (self.run_dir, self.state / 'config', self.mod, self.commands):
            directory.mkdir(parents=True)
        (self.commands / 'sh').symlink_to(Path(MKSH).resolve())
        self.write_python('flock', FLOCK_GATE)
        self.write_python('sleep', SLEEP_GATE)
        self.fake_runtime = (ROOT / 'tests/runtime/fake_runtime.py').read_text().partition('\n')[2]
        for name in ('getprop', 'ip', 'nc'):
            self.write_python(name, self.fake_runtime)
        self.make_bundle(self.old, 1)
        self.make_bundle(self.staged, 2)
        (self.state / 'current').symlink_to(self.old)
        (self.state / 'tailscaled.state').write_text('IMPORTED\n')
        (self.root / 'status.json').write_text('{"BackendState":"NeedsLogin","Health":[]}\n')
        (self.root / 'prefs.json').write_text('{"CorpDNS":false,"Hostname":"fixture"}\n')
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        # Native mode uses only our fake binaries; invalid Taildrop config
        # keeps receiver startup away from Android shared-storage paths.
        (self.state / 'config/module.conf').write_text(
            f'MODE=native\nWEB_LISTEN=127.0.0.1:{port}\nTAILDROP_DIR=/dev/null\n')
        self.env = dict(PATH=f'{self.commands}:{os.defpath}', LC_ALL='C',
                        BUNDLE_DIR=str(self.old), MODDIR=str(self.mod), TS_DIR=str(self.state),
                        KST_SHELL=MKSH, KST_BUSYBOX=str(self.root / 'no-busybox'),
                        KST_STAGED_DIR=str(self.staged), KST_LEGACY_DIR=str(self.root / 'legacy'),
                        KST_LEGACY_MODULE=str(self.root / 'legacy-module'),
                        KST_LOCK_TIMEOUT='15', KST_STOP_TIMEOUT='3', KST_START_TIMEOUT='5',
                        FIXTURE_ROOT=str(self.root), REAL_FLOCK=FLOCK,
                        REAL_SLEEP=shutil.which('sleep', path=os.defpath))
        self.controller = self.old / 'scripts/runtime-control.sh'

    def write_python(self, name, body, directory=None):
        path = (directory or self.commands) / name
        path.write_text(f'#!{sys.executable}\n' + body)
        path.chmod(0o700)

    def make_bundle(self, bundle, code):
        shutil.copytree(ROOT / 'tailscale', bundle / 'tailscale')
        (bundle / 'scripts').mkdir()
        (bundle / 'bin').mkdir()
        shutil.copyfile(ROOT / 'control.sh', bundle / 'control.sh')
        for name in ('activate-runtime.sh', 'bundle-lib.sh', 'runtime-control.sh',
                     'migrate-legacy.sh', 'status-summary.sh', 'hot-finalize.sh'):
            shutil.copyfile(ROOT / 'scripts' / name, bundle / 'scripts' / name)
        for name in ('tailscale', 'tailscaled'):
            self.write_python(name, self.fake_runtime, bundle / 'bin')
        (bundle / 'module.prop').write_text(
            f'id=kernelsu-tailscaled\nversion=3.3.{code}\nversionCode={code}\n')
        (bundle / 'engine-version').write_text('1.102.3\n')
        (bundle / 'bundle.sha256').write_text(''.join(
            f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(bundle)}\n'
            for path in sorted(bundle.rglob('*')) if path.is_file()))

    def spawn(self, *args, role='', bundle=None):
        # Each shell has a private process group even if its daemon or a
        # blocked supervisor pass outlives it. Register cleanup before waiting.
        output = tempfile.TemporaryFile(mode='w+t')
        self.addCleanup(output.close)
        env = dict(self.env, LOCK_ROLE=role, BUNDLE_DIR=str(bundle or self.old))
        proc = subprocess.Popen([MKSH, *map(str, args)], env=env, stdin=subprocess.DEVNULL,
                                stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        self.addCleanup(self.reap_group, proc)
        return proc, output

    @staticmethod
    def reap_group(proc):
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.wait(timeout=5)

    def assert_success(self, proc, output):
        try:
            proc.wait(timeout=25)
        except subprocess.TimeoutExpired:
            self.reap_group(proc)
            output.seek(0)
            self.fail(f'mksh command timed out: {proc.args!r}\n{output.read()}')
        output.seek(0)
        self.assertEqual(0, proc.returncode, output.read())

    def check(self, *args, bundle=None):
        self.assert_success(*self.spawn(*args, bundle=bundle))

    def wait_for(self, event):
        deadline = time.monotonic() + 8
        while not (self.root / event).exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue((self.root / event).exists(), f'Did not reach {event}')

    def advance(self, event):
        (self.root / event).touch()

    def start_old_supervisor(self):
        scripts = self.old / 'tailscale/scripts'
        self.check(scripts / 'tailscale-daemon', 'start')
        (self.run_dir / 'wanted').write_text('1\n')
        supervisor, _ = self.spawn(scripts / 'tailscale-supervisor', role='old')
        self.wait_for('old.ready')
        self.check('-c', '. "$BUNDLE_DIR/tailscale/scripts/common.sh" && '
                   'write_pid "$SUPERVISOR_PID" "$1" "$SUPERVISOR_SCRIPT" && '
                   'supervisor_health', 'fixture', str(supervisor.pid))
        return supervisor

    def successor_state(self):
        return ((self.run_dir / 'supervisor.pid').read_bytes(),
                (self.run_dir / 'tailscaled.pid').read_bytes(),
                (self.run_dir / 'tailscaled.sock').stat().st_ino)

    def assert_handoff(self, supervisor):
        current = (self.state / 'current').resolve(strict=True)
        self.assertNotEqual(self.old, current)
        self.assertEqual(self.old, (self.state / 'previous').resolve(strict=True))
        self.assertIn('versionCode=2\n', (current / 'module.prop').read_text())
        self.assertEqual('1\n', (self.run_dir / 'wanted').read_text())
        self.assertEqual('running\n', (self.run_dir / 'lifecycle').read_text())
        self.assertFalse((self.run_dir / 'activation-failure').exists())
        self.assertEqual(2, len((self.root / 'starts').read_text().splitlines()))
        successor = (self.run_dir / 'supervisor.pid').read_text().splitlines()
        self.assertNotEqual(str(supervisor.pid), successor[0])
        self.assertEqual(str(current / 'tailscale/scripts/tailscale-supervisor'), successor[3])
        self.check('-c', '. "$BUNDLE_DIR/tailscale/scripts/common.sh" && '
                   'supervisor_health && daemon_running', bundle=current)
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(2)
            client.connect(str(self.run_dir / 'tailscaled.sock'))

    def test_controller_first_handoff_rejects_old_waiting_reconciliation(self):
        supervisor = self.start_old_supervisor()
        apply, output = self.spawn(self.controller, 'apply-staged', role='apply')
        self.wait_for('apply.acquired')
        self.advance('old.try')
        self.wait_for('old.contended')
        self.advance('apply.proceed')
        self.assert_success(apply, output)
        # This mksh is waiting for the foreground lock subshell, which is
        # still gated, so the controller must use the bounded KILL fallback.
        self.assertEqual(-signal.SIGKILL, supervisor.wait(timeout=5))
        before = self.successor_state()
        self.advance('old.proceed')
        self.wait_for('old.reacquired')
        # A controller command takes FD 9 after the orphaned pass, so these
        # checks cannot race that pass's effects on the successor.
        self.check(self.controller, 'status-json')
        self.assertEqual(before, self.successor_state())
        log = self.state / 'logs/supervisor.log'
        if log.exists():
            self.assertNotIn('Process identity unresolved', log.read_text())
        self.assert_handoff(supervisor)

    def test_supervisor_first_handoff_terminates_idle_mksh_without_sigkill(self):
        supervisor = self.start_old_supervisor()
        self.advance('old.try')
        self.wait_for('old.acquired')
        apply, output = self.spawn(self.controller, 'apply-staged', role='apply')
        self.wait_for('apply.contended')
        self.assertEqual(self.old, (self.state / 'current').resolve(strict=True))
        self.advance('old.proceed')
        self.wait_for('old.idle')
        self.advance('apply.proceed')
        self.assert_success(apply, output)
        self.assertEqual(0, supervisor.wait(timeout=5), 'Expected the old supervisor TERM trap, not SIGKILL')
        self.wait_for('old.idle-terminated')
        self.assert_handoff(supervisor)
