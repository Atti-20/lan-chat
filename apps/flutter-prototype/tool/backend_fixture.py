#!/usr/bin/env python3
"""Start an isolated current-JAR backend. Secrets stay in ignored local output."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(os.environ.get('PROBE_OUTPUT_DIR', str(ROOT / 'output/flutter-prototype-2026-09-08'))).resolve()
NAME = os.environ.get('PROBE_FIXTURE_NAME', 'meshx-flutter-probe')
MYSQL = NAME + '-mysql'
REDIS = NAME + '-redis'
HTTP_PORT = int(os.environ.get('PROBE_HTTP_PORT', '18381'))
MYSQL_PORT = int(os.environ.get('PROBE_MYSQL_PORT', '13316'))
REDIS_PORT = int(os.environ.get('PROBE_REDIS_PORT', '16389'))



def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def free(port):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', port))


def start():
    if not NAME.startswith('meshx-') or '/' in NAME:
        raise SystemExit('Fixture name must use the meshx- prefix without path separators')
    OUT.mkdir(parents=True, exist_ok=True)
    state_file = OUT / 'backend-state.json'
    if state_file.exists():
        raise SystemExit('Fixture state already exists. Inspect it or stop this fixture first.')
    for port in (MYSQL_PORT, REDIS_PORT, HTTP_PORT):
        free(port)
    password = secrets.token_urlsafe(30)
    env_file = OUT / 'mysql.env'
    env_file.write_text('MYSQL_ROOT_PASSWORD=' + password +
        '\nMYSQL_DATABASE=lan_chat\nMESHX_ORGANIZATION_ID=org-flutter-probe\n')
    env_file.chmod(0o600)
    run('docker', 'run', '-d', '--name', MYSQL, '--label', 'meshx.fixture=flutter-prototype',
        '-p', f'127.0.0.1:{MYSQL_PORT}:3306', '--env-file', str(env_file),
        '--mount', 'type=bind,source=' + str(ROOT / 'deploy/mysql-init.sh') +
            ',target=/docker-entrypoint-initdb.d/001-init.sh,readonly',
        '--mount', 'type=bind,source=' + str(ROOT / 'sql/init.sql') +
            ',target=/opt/meshx/init.sql,readonly',
        'mysql:8.4', '--character-set-server=utf8mb4', '--collation-server=utf8mb4_general_ci',
        '--default-time-zone=+08:00')
    run('docker', 'run', '-d', '--name', REDIS, '--label', 'meshx.fixture=flutter-prototype',
        '-p', f'127.0.0.1:{REDIS_PORT}:6379', 'redis:7.4-alpine')
    state = {'mysql': MYSQL, 'redis': REDIS, 'port': HTTP_PORT,
             'databaseTimeZone': '+08:00', 'javaTimeZone': 'Asia/Shanghai'}
    state_file.write_text(json.dumps(state))
    print('Waiting for isolated database business tables...', flush=True)
    for _ in range(90):
        result = subprocess.run(['docker', 'exec', MYSQL, 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h 127.0.0.1 -uroot lan_chat '
            '-e "SELECT 1 FROM organization LIMIT 0; SELECT 1 FROM device_session LIMIT 0;"'],
            capture_output=True)
        if result.returncode == 0:
            break
        time.sleep(1)
    else:
        raise SystemExit('Database initialization failed; inspect fixture container logs.')
    jars = ([Path(os.environ['PROBE_JAR'])] if os.environ.get('PROBE_JAR') else
        sorted((ROOT / 'services/server/target').glob('*.jar')))
    if not jars:
        raise SystemExit('Build the current backend with ./mvnw -DskipTests package first.')
    env = dict(os.environ)
    env.update({
        'DB_URL': f'jdbc:mysql://127.0.0.1:{MYSQL_PORT}/lan_chat?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionCollation=utf8mb4_general_ci',
        'DB_USERNAME': 'root', 'DB_PASSWORD': password,
        'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(REDIS_PORT), 'REDIS_PASSWORD': '',
        'JWT_SECRET': secrets.token_urlsafe(48), 'MESHX_ORGANIZATION_ID': 'org-flutter-probe',
        'LANCHAT_NODE_ID': 'flutter-probe', 'LANCHAT_NODE_NAME': 'MeshX 对照实验室',
        'LANCHAT_ORGANIZATION_NAME': '独立验证空间',
        'LANCHAT_PRIVATE_DEPLOYMENT': 'false', 'LANCHAT_SELF_REGISTRATION_ENABLED': 'true',
        'LANCHAT_DISCOVERY_ENABLED': 'true', 'LANCHAT_CLUSTER_ENABLED': 'false',
        'AUTH_COOKIE_SECURE': 'false', 'TUNNEL_ENABLED': 'false',
        'FILE_STORAGE_TYPE': 'LOCAL', 'FILE_STORAGE_PATH': str(OUT / 'uploads'),
        'FILE_STAGING_PATH': str(OUT / 'staging'), 'LANCHAT_LOG_FILE': str(OUT / 'server.log'),
        'CORS_ALLOWED_ORIGINS': f'http://127.0.0.1:{HTTP_PORT},http://localhost:{HTTP_PORT},http://localhost:5194,http://127.0.0.1:5194',
        'WEBSOCKET_ALLOWED_ORIGINS': f'http://127.0.0.1:{HTTP_PORT},http://localhost:{HTTP_PORT},http://localhost:5194,http://127.0.0.1:5194',
    })
    # Maven may rebuild target/ while this fixture stays alive. Never run an open JAR that a build overwrites.
    runtime_jar = OUT / 'backend-runtime.jar'
    shutil.copy2(jars[0], runtime_jar)
    with (OUT / 'server-console.log').open('w') as log:
        proc = subprocess.Popen(['java', '-Duser.timezone=Asia/Shanghai', '-jar', str(runtime_jar), f'--server.port={HTTP_PORT}',
            '--lanchat.node.id=flutter-probe'], env=env, cwd=ROOT,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    state['pid'] = proc.pid
    state_file.write_text(json.dumps(state))
    for _ in range(90):
        if proc.poll() is not None:
            raise SystemExit('Backend exited; inspect server-console.log.')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{HTTP_PORT}/api/v1/node/info', timeout=1) as r:
                if json.load(r)['code'] == 200:
                    print(f'Fixture ready: http://127.0.0.1:{HTTP_PORT}', flush=True)
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise SystemExit('Backend startup timed out; inspect server-console.log.')


def stop():
    state_file = OUT / 'backend-state.json'
    if not state_file.exists():
        return
    state = json.loads(state_file.read_text())
    pid = state.get('pid')
    if pid:
        command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='],
            capture_output=True, text=True).stdout
        if '--lanchat.node.id=flutter-probe' in command:
            os.kill(pid, 15)
    for name in (MYSQL, REDIS):
        label = subprocess.run(['docker', 'inspect', '-f',
            '{{index .Config.Labels "meshx.fixture"}}', name], capture_output=True, text=True).stdout.strip()
        if label == 'flutter-prototype':
            run('docker', 'rm', '-f', '-v', name)
    state_file.unlink()
    (OUT / 'mysql.env').unlink(missing_ok=True)
    print('Stopped only the Flutter fixture.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop'])
    args = parser.parse_args()
    start() if args.action == 'start' else stop()
