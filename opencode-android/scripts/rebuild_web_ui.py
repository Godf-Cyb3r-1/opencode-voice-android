#!/usr/bin/env python3
"""Rebuild the bundled GUI from the included, hash-pinned upstream source."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / 'runtime/web-ui-lock.json').read_text())
archive = ROOT / lock['source_archive']
h = hashlib.sha256()
with archive.open('rb') as source:
    for block in iter(lambda: source.read(1024 * 1024), b''): h.update(block)
if h.hexdigest() != lock['source_archive_sha256']: raise SystemExit('Upstream source archive checksum mismatch')
version = subprocess.check_output(['bun', '--version'], text=True).strip()
if version != lock['bun_version']: raise SystemExit('Use Bun ' + lock['bun_version'] + ' to rebuild this GUI')
with tempfile.TemporaryDirectory(prefix='opencode-gui-source-') as folder:
    subprocess.run(['tar', '-xzf', str(archive), '-C', folder], check=True)
    subprocess.run(['git', 'apply', str(ROOT / 'runtime/android-ui.patch')], cwd=folder, check=True)
    subprocess.run(['bun', 'install', '--ignore-scripts', '--frozen-lockfile', '--cwd', folder], check=True)
    env = dict(os.environ, OPENCODE_CHANNEL='prod', VITE_OPENCODE_ANDROID_HOST='1')
    for name in ['SENTRY_AUTH_TOKEN', 'SENTRY_ORG', 'SENTRY_PROJECT', 'VITE_SENTRY_DSN']:
        env.pop(name, None)
    subprocess.run(['bun', 'run', '--cwd', str(Path(folder) / 'packages/app'), 'build'], check=True, env=env)
    subprocess.run(['python3', str(ROOT / 'scripts/prepare_web_ui.py'), str(Path(folder) / 'packages/app/dist')], check=True)
