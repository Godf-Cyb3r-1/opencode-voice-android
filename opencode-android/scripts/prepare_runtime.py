#!/usr/bin/env python3
"""Fetch hash-pinned inputs and assemble the APK's embedded Android rootfs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
import gzip

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def fetch(spec, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and sha256(dest) == spec['sha256']:
        return dest
    temporary = dest.with_suffix('.partial')
    req = urllib.request.Request(spec['url'], headers={'User-Agent': 'OpenCode-Android-build/0.1'})
    try:
        with urllib.request.urlopen(req, timeout=60) as response, temporary.open('wb') as out:
            while block := response.read(1024 * 1024):
                out.write(block)
        if sha256(temporary) != spec['sha256']:
            raise RuntimeError(f'Checksum mismatch: {dest.name}')
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)
    return dest


def prepare(cache):
    lock = json.loads((ROOT / 'runtime/lock.json').read_text())
    bootstrap = fetch(lock['bootstrap'], cache / 'bootstrap-aarch64.zip')
    package = fetch(lock['runtime'], cache / 'opencode.deb')
    binary = cache / 'opencode'
    if not binary.exists() or sha256(binary) != lock['runtime']['binary_sha256']:
        proc = subprocess.Popen(['dpkg-deb', '--fsys-tarfile', str(package)], stdout=subprocess.PIPE)
        found = False
        with tarfile.open(fileobj=proc.stdout, mode='r|') as archive:
            for member in archive:
                if member.name.lstrip('./') == 'data/data/com.termux/files/usr/bin/opencode':
                    with archive.extractfile(member) as source, binary.open('wb') as out:
                        while block := source.read(1024 * 1024):
                            out.write(block)
                    found = True
        proc.stdout.close()
        if proc.wait() != 0 or not found:
            raise RuntimeError('Could not extract the Android OpenCode binary')
        if sha256(binary) != lock['runtime']['binary_sha256']:
            raise RuntimeError('Extracted binary checksum mismatch')
    payload = ROOT / 'app/src/main/assets/engine'
    payload.mkdir(parents=True, exist_ok=True)
    with (payload / 'opencode.payload').open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0, compresslevel=6) as out, binary.open('rb') as source:
        while block := source.read(1024 * 1024): out.write(block)
    (payload / 'opencode.gz').unlink(missing_ok=True)
    (payload / 'manifest.json').write_text(json.dumps({'version': lock['opencode']['version'], 'commit': lock['opencode']['commit'], 'sha256': lock['runtime']['binary_sha256'], 'size': binary.stat().st_size}, indent=2) + '\n')
    destination = ROOT / 'app/src/main/cpp/bootstrap-aarch64.zip'
    additions = {
        'bin/opencode-android-session': ROOT / 'runtime/opencode-android-session',
        'share/opencode-android/lock.json': ROOT / 'runtime/lock.json',
    }
    with tempfile.NamedTemporaryFile(dir=destination.parent, suffix='.zip', delete=False) as temp:
        temporary = Path(temp.name)
    try:
        with zipfile.ZipFile(bootstrap) as source, zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as out:
            for entry in source.infolist():
                if entry.filename not in additions:
                    out.writestr(entry, source.read(entry))
            for name, path in additions.items():
                info = zipfile.ZipInfo(name, date_time=(2026, 10, 27, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o100700 if name.startswith('bin/') else 0o100600) << 16
                with path.open('rb') as source, out.open(info, 'w', force_zip64=True) as target:
                    while block := source.read(1024 * 1024):
                        target.write(block)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    destination.with_suffix('.sha256').write_text(sha256(destination) + '\n')
    print(f'Prepared embedded OpenCode {lock["opencode"]["version"]}: {destination.stat().st_size:,} bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=ROOT / '.cache')
    prepare(parser.parse_args().cache.resolve())
