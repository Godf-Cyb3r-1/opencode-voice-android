#!/usr/bin/env python3
"""Check that every bundled upstream UI asset matches its manifest."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify():
    folder = ROOT / 'app/src/main/assets/web-ui'
    manifest = json.loads((folder / 'manifest.json').read_text())
    lock = json.loads((ROOT / 'runtime/lock.json').read_text())
    if manifest['opencode_version'] != lock['opencode']['version'] or manifest['opencode_commit'] != lock['opencode']['commit']:
        raise RuntimeError('UI and engine versions do not match')
    names = manifest['files']
    if 'index.html' not in names or len(names) != len(set(names)):
        raise RuntimeError('Invalid UI manifest')
    for name in names:
        path = (folder / name).resolve()
        if not path.is_relative_to(folder.resolve()) or not path.is_file():
            raise RuntimeError('Missing or unsafe UI asset: ' + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != manifest['sha256'][name]:
            raise RuntimeError('UI asset checksum mismatch: ' + name)
    print(f'Verified {len(names)} bundled upstream UI assets, matching OpenCode {manifest["opencode_version"]}')


if __name__ == '__main__': verify()
