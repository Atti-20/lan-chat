#!/usr/bin/env python3
"""Test-only loopback screenshot helper; captures the complete simulator, including IME."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import json
import os
import re
import subprocess
import tempfile

OUT = Path(os.environ.get('PROBE_EVIDENCE_DIR', str(Path(__file__).resolve().parents[3] / 'output/flutter-prototype-2026-09-08')))
ADB = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'


class Capture(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != '/capture':
            self.send_error(404)
            return
        length = int(self.headers.get('Content-Length', '0'))
        if length not in range(1, 1025):
            self.send_error(400)
            return
        data = json.loads(self.rfile.read(length))
        name = data.get('name', '')
        platform = data.get('platform')
        if not re.fullmatch(r'[a-z0-9-]{1,80}', name) or platform not in ('ios', 'android'):
            self.send_error(400)
            return
        path = OUT / (platform + '-system-' + name + '.png')
        try:
            if platform == 'ios':
                # Simulator's service cannot write directly to some external volumes.
                with tempfile.TemporaryDirectory(prefix='meshx-sim-capture-') as temp:
                    capture = Path(temp) / 'capture.png'
                    subprocess.run(['xcrun', 'simctl', 'io', 'booted', 'screenshot', str(capture)],
                        check=True, capture_output=True, timeout=15)
                    path.write_bytes(capture.read_bytes())
            else:
                result = subprocess.run([str(ADB), '-s', 'emulator-5554', 'exec-out', 'screencap', '-p'],
                    check=True, capture_output=True, timeout=15)
                path.write_bytes(result.stdout)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"captured":true}')
        except (subprocess.SubprocessError, OSError):
            self.send_error(500, 'Simulator capture failed')

    def log_message(self, format, *args):
        pass


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    print('Local simulator screenshot helper: 127.0.0.1:18382', flush=True)
    HTTPServer(('127.0.0.1', 18382), Capture).serve_forever()
