#!/usr/bin/env python3
"""Non-LLM smoke checks on a connected Android device. Never uninstalls apps."""
import argparse
from pathlib import Path
import shlex
import subprocess
import time

PREFIX = '/data/data/com.termux/files/usr'
HOME = '/data/data/com.termux/files/home'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='ADB device serial (recommended)')
    parser.add_argument('--apk', type=Path, help='Optional APK to install/update without deleting data')
    args = parser.parse_args()
    adb = ['adb'] + (['-s', args.serial] if args.serial else [])

    def run(*command, timeout=60, check=True):
        return subprocess.run(adb + list(command), check=check, text=True, capture_output=True, timeout=timeout)

    run('get-state')
    arch = run('shell', 'getprop', 'ro.product.cpu.abilist').stdout.strip()
    if 'arm64-v8a' not in arch:
        raise SystemExit('This APK requires an ARM64 Android device.')
    page_size = run('shell', 'getconf', 'PAGESIZE').stdout.strip()
    if page_size != '4096': raise SystemExit('This runtime requires 4 KB memory pages; reported: ' + page_size)
    if args.apk:
        if not args.apk.is_file(): raise SystemExit('APK does not exist.')
        install = run('install', '-r', str(args.apk.resolve()), timeout=180, check=False)
        if install.returncode:
            raise SystemExit('Installation failed; no app was uninstalled.\n' + install.stdout + install.stderr)
    package = run('shell', 'dumpsys', 'package', 'com.termux').stdout
    if 'versionName=0.4.0' not in package or 'versionCode=10004' not in package:
        raise SystemExit('Expected OpenCode Local build not detected. Refusing to probe another Termux installation.')
    run('shell', 'am', 'start', '-n', 'com.termux/com.termux.app.OpenCodeWebActivity')

    def shell(command, check=True):
        return run('shell', 'run-as', 'com.termux', PREFIX + '/bin/bash', '--login', '-c', shlex.quote(command), check=check)

    ready = False
    for _ in range(30):
        probe = run('shell', 'run-as', 'com.termux', '/system/bin/test', '-x', PREFIX + '/bin/opencode', check=False)
        if probe.returncode == 0:
            ready = True
            break
        time.sleep(1)
    if not ready: raise SystemExit('Bootstrap did not become ready. Unlock the phone and inspect the app.')
    version = ''
    for _ in range(90):
        probe = shell('opencode --version', check=False)
        version = probe.stdout.strip()
        if '2.1.4' in version: break
        time.sleep(1)
    if '2.1.4' not in version: raise SystemExit('Unexpected OpenCode version: ' + version)
    print('PASS: embedded OpenCode version: ' + version)
    shell('mkdir -p "$HOME/projects/default" && printf "%s\\n" opencode-local-smoke > "$HOME/projects/default/.opencode-local-smoke"')
    marker = shell('cat "$HOME/projects/default/.opencode-local-smoke"').stdout.strip()
    if marker != 'opencode-local-smoke': raise SystemExit('Workspace write/read failed.')
    print('PASS: local shell execution and workspace write/read')
    run('shell', 'am', 'start', '-n', 'com.termux/com.termux.app.OpenCodeWebActivity')
    if shell('cat "$HOME/projects/default/.opencode-local-smoke"').stdout.strip() != marker:
        raise SystemExit('Workspace persistence failed.')
    print('PASS: activity reopening preserves the workspace file')
    print('NOT TESTED: model-driven tools, session restoration after process death, background lifetime, plugins/MCPs.')
    print('The smoke marker remains in the default workspace; no existing project files were changed.')


if __name__ == '__main__':
    main()
