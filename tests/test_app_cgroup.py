import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class LeaveAppCgroupTests(unittest.TestCase):
    """Android kills an app's uid_N/pid_M group on task removal; the runtime must leave it."""

    def run_helper(self, cgroup, mounts):
        with tempfile.TemporaryDirectory(prefix='tm-cgroup-') as directory:
            root = Path(directory)
            for mount in ('v2', 'memcg', 'cpuset'):
                (root / mount).mkdir()
                (root / mount / 'cgroup.procs').write_text('')
            (root / 'self').write_text(cgroup)
            (root / 'mounts').write_text(mounts.replace('ROOT', directory))
            script = ('. "$1/scripts/bundle-lib.sh"; tm_leave_app_cgroup; echo "$$"')
            result = subprocess.run(
                ['sh', '-c', script, 'sh', str(ROOT)], capture_output=True, text=True, timeout=10,
                env={'PATH': '/usr/bin:/bin', 'TM_BUSYBOX': '/nonexistent',
                     'TM_CGROUP_SELF': str(root / 'self'), 'TM_CGROUP_MOUNTS': str(root / 'mounts')})
            self.assertEqual(0, result.returncode, result.stderr)
            pid = result.stdout.strip()
            return pid, {m: (root / m / 'cgroup.procs').read_text() for m in ('v2', 'memcg', 'cpuset')}

    def test_moves_out_of_the_per_app_group_on_the_matching_hierarchy(self):
        pid, procs = self.run_helper(
            '3:memory:/apps\n2:cpuset:/top-app\n0::/uid_10503/pid_748\n',
            'none ROOT/v2 cgroup2 rw,nosuid 0 0\n'
            'none ROOT/memcg cgroup rw,memory 0 0\n'
            'none ROOT/cpuset cgroup rw,cpuset,noprefix 0 0\n')
        self.assertEqual(pid + '\n', procs['v2'])
        # Scheduling and memory groups are not per-app process groups; keep them.
        self.assertEqual('', procs['memcg'])
        self.assertEqual('', procs['cpuset'])

    def test_v1_app_groups_use_the_mount_carrying_their_controllers(self):
        pid, procs = self.run_helper(
            '3:memory:/uid_10503/pid_748\n0::/\n',
            'none ROOT/v2 cgroup2 rw 0 0\nnone ROOT/memcg cgroup rw,nosuid,memory 0 0\n')
        self.assertEqual(pid + '\n', procs['memcg'])
        self.assertEqual('', procs['v2'])

    def test_root_shell_outside_app_groups_is_untouched(self):
        _, procs = self.run_helper('0::/\n', 'none ROOT/v2 cgroup2 rw 0 0\n')
        self.assertEqual({'v2': '', 'memcg': '', 'cpuset': ''}, procs)


if __name__ == '__main__':
    unittest.main()
