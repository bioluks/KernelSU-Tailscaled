#!/usr/bin/python3
"""Minimal iptables/ip6tables/nslookup fixture for system-DNS tests."""
import json
import os
import sys
from pathlib import Path

root = Path(os.environ['FAKE_NETFILTER_ROOT'])
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with (root / 'netfilter-calls.log').open('a') as stream:
    stream.write(json.dumps([name, *args]) + '\n')

if name == 'nslookup':
    server = args[1] if len(args) > 1 else '100.100.100.100'
    failing = (root / 'dns-probe-fail').exists() or (
        (root / 'dns-self-fail').exists() and len(args) > 0 and args[0] == 'phone.lan')
    print('Server:\t\t' + server)
    print('Address 1:\t' + server + '\tmagicdns.localhost-tailscale-daemon')
    print('')
    if failing:
        print("nslookup: can't resolve 'example.com'")
        sys.exit(1)
    print('Name:\texample.com')
    print('Address 1:\t1.1.1.1\texample.com')
    print('Address 2:\t1.0.0.1\texample.com')
    sys.exit(0)

state_file = root / 'netfilter.json'
state = json.loads(state_file.read_text()) if state_file.exists() else {}

if (name == 'ip6tables' and os.environ.get('FAKE_IP6TABLES_NO_NAT') == '1'
        and '-t' in args and args[args.index('-t') + 1] == 'nat'):
    sys.stderr.write("ip6tables: can't initialize ip6tables table 'nat': Table does not exist\n")
    sys.exit(1)


def value(flag):
    if flag in args:
        return args[args.index(flag) + 1]
    return None


COMMANDS = {'-N', '-F', '-X', '-A', '-I', '-D', '-C', '-S', '-L', '-P'}
index = next((i for i, item in enumerate(args) if item in COMMANDS), None)
if index is None:
    sys.exit(0)
command = args[index]
rest = args[index + 1:]
table_name = value('-t') or 'filter'
chains = state.setdefault(table_name, {})
for builtin in ('PREROUTING', 'INPUT', 'OUTPUT', 'FORWARD', 'POSTROUTING'):
    chains.setdefault(builtin, [])


def save():
    state_file.write_text(json.dumps(state, indent=0, sort_keys=True))


def rule_text(items):
    return ' '.join(items)


if command in ('-S', '-L', '-P'):
    if command == '-S':
        target = rest[0] if rest and not rest[0].startswith('-') else None
        if target and target not in chains:
            sys.exit(1)
        if target is None:
            builtin_names = ('PREROUTING', 'INPUT', 'OUTPUT', 'FORWARD', 'POSTROUTING')
            for chain_name, rules in chains.items():
                if chain_name not in builtin_names:
                    print('-N ' + chain_name)
                for rule in rules:
                    print('-A ' + chain_name + ' ' + rule)
        else:
            for rule in chains[target]:
                print('-A ' + target + ' ' + rule)
    sys.exit(0)
if command == '-N':
    if not rest or rest[0] in chains:
        sys.exit(1)
    chains[rest[0]] = []
    save()
    sys.exit(0)
if command == '-F':
    if rest and not rest[0].startswith('-'):
        if rest[0] not in chains:
            sys.exit(1)
        chains[rest[0]] = []
    else:
        for key in chains:
            chains[key] = []
    save()
    sys.exit(0)
if command == '-X':
    if not rest or rest[0] not in chains or chains[rest[0]]:
        sys.exit(1)
    del chains[rest[0]]
    save()
    sys.exit(0)
if not rest:
    sys.exit(1)
chain_name = rest[0]
if chain_name not in chains:
    sys.exit(1)
if command == '-A':
    chains[chain_name].append(rule_text(rest[1:]))
elif command == '-I':
    if len(rest) > 1 and rest[1].isdigit():
        position = max(0, int(rest[1]) - 1)
        chains[chain_name].insert(position, rule_text(rest[2:]))
    else:
        chains[chain_name].insert(0, rule_text(rest[1:]))
elif command == '-D':
    if len(rest) > 1 and rest[1].isdigit():
        position = int(rest[1]) - 1
        if position >= len(chains[chain_name]):
            sys.exit(1)
        del chains[chain_name][position]
    else:
        rule = rule_text(rest[1:])
        if rule not in chains[chain_name]:
            sys.exit(1)
        chains[chain_name].remove(rule)
elif command == '-C':
    if len(rest) > 1 and rest[1].isdigit():
        sys.exit(0 if int(rest[1]) <= len(chains[chain_name]) else 1)
    sys.exit(0 if rule_text(rest[1:]) in chains[chain_name] else 1)
save()
sys.exit(0)
