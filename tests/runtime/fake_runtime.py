#!/usr/bin/python3
import json
import os
import signal
import socket
import sys
import time
from pathlib import Path

root = Path(os.environ['FIXTURE_ROOT'])
name = Path(sys.argv[0]).name
args = sys.argv[1:]

def option(prefix):
    return next(item.split('=', 1)[1] for item in args if item.startswith(prefix + '='))

def default_preferences():
    return {'Hostname': '', 'CorpDNS': True, 'RouteAll': False, 'ExitNodeIP': '',
            'ExitNodeID': '', 'AdvertiseRoutes': [], 'LoggedOut': True,
            'WantRunning': False, 'Persist': {'PrivateNodeKey': 'PRIVATE_SENTINEL'}}

def write_preferences(prefs):
    (root / 'prefs.json').write_text(json.dumps(prefs))
    profile = json.loads((root / 'profile.json').read_text())
    # Upstream keeps preferences only in memory until the profile has an ID/Key.
    if profile.get('ID') and profile.get('Key'):
        state = Path(os.environ['TS_DIR']) / 'tailscaled.state'
        state.write_text(json.dumps({'machine': 'PRIVATE_SENTINEL',
                                     '_profiles': 'eyJmaXh0dXJlIjp7fX0=',
                                     'profile': profile, 'prefs': prefs}))

def serve(sock):
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    while True:
        time.sleep(0.1)

if name == 'tailscaled':
    (root / 'daemon-environment.json').write_text(json.dumps({
        'bind': os.environ.get('TS_FORCE_LINUX_BIND_TO_DEVICE'),
    }))
    with (root / 'starts').open('a') as stream:
        stream.write(str(time.monotonic()) + '\n')
    if (root / 'daemon-fail').exists():
        sys.exit(1)
    state = Path(option('--state'))
    if state.exists():
        try:
            saved = json.loads(state.read_text())
        except json.JSONDecodeError:
            # Tests that seed IMPORTED represent a pre-existing persisted identity.
            saved = {'profile': {'ID': 'imported', 'Key': 'profile-imported'},
                     'prefs': json.loads((root / 'prefs.json').read_text())}
    else:
        saved = {}
    profile = saved.get('profile', {'ID': '', 'Key': ''})
    (root / 'profile.json').write_text(json.dumps(profile))
    (root / 'prefs.json').write_text(json.dumps(saved.get('prefs', default_preferences())))
    if not state.exists():
        state.write_text(json.dumps({'machine': 'PRIVATE_SENTINEL'}))
    sock = socket.socket(socket.AF_UNIX)
    sock.bind(option('--socket'))
    sock.listen()
    serve(sock)
elif name == 'tailscale':
    if not any(command in args for command in ('web', 'version')):
        with (root / 'cli-calls').open('a') as stream:
            stream.write(json.dumps(args) + '\n')
    if 'version' in args:
        print('1.102.3')
    elif 'web' in args:
        if (root / 'web-start-delay').exists():
            time.sleep(float((root / 'web-start-delay').read_text()))
        sock = socket.socket()
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        host, port = option('--listen').split(':')
        sock.bind((host, int(port)))
        sock.listen()
        serve(sock)
    elif 'status' in args:
        if (root / 'cli-hang').exists():
            time.sleep(20)
        print((root / 'status.json').read_text())
    elif 'prefs' in args:
        print((root / 'prefs.json').read_text())
    elif 'ip' in args:
        for address in json.loads((root / 'status.json').read_text())['TailscaleIPs']:
            if ':' not in address:
                print(address)
    elif 'localapi' in args:
        method, endpoint, *body = args[args.index('localapi') + 1:]
        if (root / 'localapi-fail').exists():
            sys.exit(1)
        if method == 'GET' and endpoint == '/localapi/v0/profiles/current':
            print((root / 'profile.json').read_text())
        elif method == 'PATCH' and endpoint == '/localapi/v0/prefs':
            patch = json.loads(body[0])
            prefs = json.loads((root / 'prefs.json').read_text())
            prefs.update({key: value for key, value in patch.items() if not key.endswith('Set')})
            write_preferences(prefs)
            print(json.dumps(prefs))
        elif method == 'POST' and endpoint == '/localapi/v0/login-interactive':
            status = json.loads((root / 'status.json').read_text())
            server = json.loads((root / 'prefs.json').read_text())['ControlURL']
            status.update(BackendState='NeedsLogin', AuthURL=server + '/register/TEST_LOGIN_SECRET')
            (root / 'status.json').write_text(json.dumps(status))
        elif method != 'POST' or endpoint != '/localapi/v0/start':
            sys.exit(2)
    elif 'set' in args:
        with (root / 'sets').open('a') as stream:
            stream.write(json.dumps(args) + '\n')
        prefs = json.loads((root / 'prefs.json').read_text())
        for argument in args[args.index('set') + 1:]:
            flag, value = argument.split('=', 1)
            if flag == '--hostname':
                prefs['Hostname'] = value
            elif flag == '--accept-dns':
                prefs['CorpDNS'] = value == 'true'
            elif flag == '--accept-routes':
                prefs['RouteAll'] = value == 'true'
            elif flag == '--exit-node':
                prefs.update(ExitNodeIP=value, ExitNodeID='')
            elif flag == '--advertise-routes':
                prefs['AdvertiseRoutes'] = value.split(',') if value else []
            else:
                sys.exit(2)
        write_preferences(prefs)
    elif 'up' in args:
        prefs = json.loads((root / 'prefs.json').read_text())
        prefs.update(LoggedOut=False, WantRunning=True)
        prefs['Persist'].update(NodeID='fixture-node',
                                UserProfile={'LoginName': 'fixture@example.com'})
        (root / 'profile.json').write_text(json.dumps({'ID': 'fixture', 'Key': 'profile-fixture'}))
        write_preferences(prefs)
        status = json.loads((root / 'status.json').read_text())
        status.update(BackendState='Running', TailscaleIPs=['100.100.1.1'])
        (root / 'status.json').write_text(json.dumps(status))
    elif 'logout' in args:
        (root / 'profile.json').write_text(json.dumps({'ID': '', 'Key': ''}))
        write_preferences(default_preferences())
        (Path(os.environ['TS_DIR']) / 'tailscaled.state').write_text(
            json.dumps({'machine': 'PRIVATE_SENTINEL', '_profiles': 'e30='}))
        status = json.loads((root / 'status.json').read_text())
        status.update(BackendState='NeedsLogin', TailscaleIPs=[])
        (root / 'status.json').write_text(json.dumps(status))
    else:
        sys.exit(2)
elif name == 'ip':
    with (root / 'ip-calls').open('a') as stream:
        stream.write(' '.join(args) + '\n')
    if 'link' in args:
        sys.exit(0 if (root / 'link').exists() else 1)
    if 'address' in args or 'addr' in args:
        print((root / 'addresses').read_text())
    elif 'route' in args:
        if 'mark' in args and (root / 'marked-route-fail').exists():
            print('RTNETLINK answers: Network is unreachable', file=sys.stderr)
            sys.exit(2)
        print((root / 'routes').read_text())
elif name == 'nc':
    try:
        with socket.create_connection((args[-2], int(args[-1])), timeout=0.2):
            pass
    except OSError:
        sys.exit(1)
elif name == 'settings':
    print((root / 'device-name').read_text() if (root / 'device-name').exists() else 'Fixture Phone')
elif name == 'getprop':
    if args == ['ro.build.version.sdk']:
        print('36' if (root / 'android-runtime').exists() else '')
    else:
        print('fixture-product')
