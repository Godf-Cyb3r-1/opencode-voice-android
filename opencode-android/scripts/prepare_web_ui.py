#!/usr/bin/env python3
"""Bundle the matching upstream UI, excluding source maps and build metadata."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dist', type=Path, help='Built upstream packages/app/dist directory')
    args = parser.parse_args()
    source = args.dist.resolve()
    if not (source / 'index.html').is_file(): raise SystemExit('Build the upstream UI before packaging.')
    destination = ROOT / 'app/src/main/assets/web-ui'
    if destination.exists(): shutil.rmtree(destination)
    destination.mkdir(parents=True)
    files = []
    hashes = {}
    for path in sorted(source.rglob('*')):
        if not path.is_file() or path.suffix == '.map': continue
        relative = path.relative_to(source).as_posix()
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        files.append(relative)
        hashes[relative] = hashlib.sha256(target.read_bytes()).hexdigest()
    lock = json.loads((ROOT / 'runtime/lock.json').read_text())
    manifest = {'android_revision': 'android-0.4.0', 'opencode_version': lock['opencode']['version'], 'opencode_commit': lock['opencode']['commit'], 'files': files, 'sha256': hashes}
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Bundled {len(files)} upstream UI files ({sum((destination / name).stat().st_size for name in files):,} bytes)')


if __name__ == '__main__': main()
