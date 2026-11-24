import json
import shutil
import unittest
from test_runtime_behavior import ROOT, RuntimeHarness

class SystemDnsTests(RuntimeHarness):
    def setUp(self):
        super().setUp()
        for name in ('iptables', 'ip6tables', 'nslookup'):
            shutil.copy(ROOT / 'tests/runtime/fake_netfilter.py', self.bin / name)
            (self.bin / name).chmod(0o755)
        self.env['FAKE_NETFILTER_ROOT'] = str(self.root)
        self.netfilter_state = self.root / 'netfilter.json'

    def netfilter(self):
        if not self.netfilter_state.exists():
            return {}
        return json.loads(self.netfilter_state.read_text())

    def rules(self, table='nat', chain='KST_SYSDNS'):
        return self.netfilter().get(table, {}).get(chain, [])

    def jumps(self, table='nat'):
        return self.netfilter().get(table, {}).get('OUTPUT', [])

    def assert_absent(self):
        self.assertEqual([], self.rules())
        self.assertEqual([], self.rules('filter', 'KST_SYSDNS6'))
        self.assertFalse(any('KST_SYSDNS' in rule for rule in self.jumps()))
        self.assertFalse(any('KST_SYSDNS' in rule for rule in self.jumps('filter')))

    def preload_netfilter(self, state):
        self.netfilter_state.write_text(json.dumps(state))

    def test_system_dns_is_opt_in_by_default(self):
        self.start()

        status = self.status()

        self.assertEqual({'enabled': False, 'applied': False}, status['systemDns'])
        self.assert_absent()
        self.assertFalse((self.state / 'system-dns-enabled').exists())

    def test_system_dns_requires_a_working_resolver_probe(self):
        self.start()
        (self.root / 'dns-probe-fail').touch()

        result = self.run_command('enable-system-dns')

        self.assertNotEqual(0, result.returncode)
        self.assertIn('probe', result.stderr)
        self.assert_absent()
        self.assertFalse((self.state / 'system-dns-enabled').exists())

    def test_enable_applies_owned_chains_and_disable_removes_them(self):
        self.start()

        result = self.run_command('enable-system-dns')
        self.assertEqual(0, result.returncode, result.stderr)

        chain = self.rules()
        self.assertIn('100.64.0.6:53', ' '.join(chain))
        self.assertTrue(any('--mark 0x80000/0xff0000' in rule for rule in chain))
        self.assertTrue(any('127.0.0.0/8 -j RETURN' in rule for rule in chain))
        self.assertTrue(any('-p udp' in rule and '--dport 53' in rule and '-j SNAT' in rule
                            and '--to-source 100.100.1.1' in rule for rule in self.rules('nat', 'POSTROUTING')))
        self.assertTrue(any('-p tcp' in rule and '--dport 53' in rule and '-j SNAT' in rule
                            and '--to-source 100.100.1.1' in rule for rule in self.rules('nat', 'POSTROUTING')))
        self.assertFalse(any('192.168.0.0/16' in rule for rule in chain))
        jumps = self.jumps()
        self.assertEqual(1, jumps.count('-p udp --dport 53 -j KST_SYSDNS'))
        self.assertEqual(1, jumps.count('-p tcp --dport 53 -j KST_SYSDNS'))
        self.assertTrue((self.state / 'system-dns-enabled').exists())
        status = self.status()
        self.assertEqual({'enabled': True, 'applied': True}, status['systemDns'])
        codes = {item['code']: item['severity'] for item in status['diagnostics']}
        self.assertEqual('info', codes['system-dns-active'])

        result = self.run_command('disable-system-dns')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_absent()
        self.assertFalse((self.state / 'system-dns-enabled').exists())
        self.assertEqual({'enabled': False, 'applied': False}, self.status()['systemDns'])

    def test_enable_is_idempotent_and_sets_accept_dns(self):
        (self.root / 'prefs.json').write_text(json.dumps({'Hostname': '', 'CorpDNS': False}))
        self.start()

        for _ in range(2):
            result = self.run_command('enable-system-dns')
            self.assertEqual(0, result.returncode, result.stderr)

        jumps = self.jumps()
        self.assertEqual(1, jumps.count('-p udp --dport 53 -j KST_SYSDNS'))
        self.assertEqual(1, jumps.count('-p tcp --dport 53 -j KST_SYSDNS'))
        prefs = json.loads((self.root / 'prefs.json').read_text())
        self.assertTrue(prefs['CorpDNS'])

    def test_stopping_the_daemon_removes_rules_and_restart_reapplies(self):
        self.start()
        self.assertEqual(0, self.run_command('enable-system-dns').returncode)
        self.assertTrue(self.rules())

        self.assertEqual(0, self.run_command('stop').returncode)
        self.assert_absent()
        self.assertTrue((self.state / 'system-dns-enabled').exists())
        self.assertEqual({'enabled': True, 'applied': False}, self.status()['systemDns'])
        codes = {item['code']: item['severity'] for item in self.status()['diagnostics']}
        self.assertEqual('warning', codes['system-dns-inactive'])

        self.start()
        self.assertTrue(self.rules())
        self.assertEqual({'enabled': True, 'applied': True}, self.status()['systemDns'])

    def test_ipv6_without_nat_rejects_port_53_instead_of_leaking(self):
        self.env['FAKE_IP6TABLES_NO_NAT'] = '1'
        self.start()

        result = self.run_command('enable-system-dns')

        self.assertEqual(0, result.returncode, result.stderr)
        chain6 = self.rules('filter', 'KST_SYSDNS6')
        self.assertTrue(any('REJECT' in rule and 'icmp6-port-unreachable' in rule for rule in chain6))
        self.assertTrue(any('REJECT' in rule and 'tcp-reset' in rule for rule in chain6))
        jumps6 = self.jumps('filter')
        self.assertIn('-p udp --dport 53 -j KST_SYSDNS6', jumps6)
        self.assertIn('-p tcp --dport 53 -j KST_SYSDNS6', jumps6)
        self.assertEqual([], self.rules('nat', 'KST_SYSDNS6'))
        self.assertTrue(self.rules())

    def test_enable_refuses_active_foreign_dns_redirect(self):
        self.preload_netfilter({'nat': {
            'OUTPUT': ['-p udp --dport 53 -j TS_DNS_ENFORCE'],
            'TS_DNS_ENFORCE': ['-p udp --dport 53 -j DNAT --to-destination 100.64.0.3:53'],
        }})
        self.start()

        result = self.run_command('enable-system-dns')

        self.assertNotEqual(0, result.returncode)
        self.assertIn('Another DNS redirect', result.stderr)
        self.assert_absent()
        self.assertFalse((self.state / 'system-dns-enabled').exists())

    def test_enable_requires_magicdns_answers_from_the_resolver(self):
        (self.root / 'dns-self-fail').touch()
        self.start()

        result = self.run_command('enable-system-dns')

        self.assertNotEqual(0, result.returncode)
        self.assertIn('probe', result.stderr)
        self.assert_absent()
        self.assertFalse((self.state / 'system-dns-enabled').exists())

    def test_resolver_change_reapplies_on_daemon_restart(self):
        self.start()
        self.assertEqual(0, self.run_command('enable-system-dns').returncode)
        (self.root / 'dns-status.json').write_text(json.dumps({
            'Resolvers': [{'Addr': '100.64.0.9'}],
            'CurrentTailnet': {'MagicDNSEnabled': True, 'MagicDNSSuffix': 'lan',
                               'SelfDNSName': 'phone.lan'},
        }))

        self.assertEqual(0, self.run_command('restart').returncode)

        chain = self.rules()
        self.assertIn('100.64.0.9:53', ' '.join(chain))
        self.assertFalse(any('100.64.0.6' in rule for rule in chain))
        self.assertTrue(any('-d 100.64.0.9' in rule and 'SNAT' in rule
                            for rule in self.rules('nat', 'POSTROUTING')))

    def test_dormant_foreign_dns_chain_is_reported_after_enable(self):
        self.preload_netfilter({'nat': {
            'TS_DNS_ENFORCE': ['-p udp --dport 53 -j DNAT --to-destination 100.64.0.3:53'],
        }})
        self.start()

        result = self.run_command('enable-system-dns')
        self.assertEqual(0, result.returncode, result.stderr)

        codes = {item['code']: item['severity'] for item in self.status()['diagnostics']}
        self.assertEqual('warning', codes['system-dns-other-module'])

    def test_stale_android_dns_health_is_translated_to_info(self):
        self.write_status(Health=['Tailscale failed to set the DNS configuration of your device: '
                                  'atomicWriteFile: open /etc/resolv.conf.abc.tmp: read-only file system'])
        self.start()

        status = self.status()

        codes = {item['code']: item['severity'] for item in status['diagnostics']}
        self.assertNotIn('upstream-health', codes)
        self.assertEqual('info', codes['android-system-dns'])
        self.assertEqual('running', status['lifecycle'])


if __name__ == '__main__':
    unittest.main()
