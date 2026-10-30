import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
import gzip

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare_runtime', ROOT / 'scripts/prepare_runtime.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class InputIntegrity(unittest.TestCase):
    def test_wrong_download_hash_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / 'input'
            source.write_bytes(b'corrupt')
            target = Path(d) / 'output'
            with self.assertRaisesRegex(RuntimeError, 'Checksum mismatch'):
                prepare.fetch({'url': source.as_uri(), 'sha256': '0' * 64}, target)
            self.assertFalse(target.exists())
            self.assertFalse(target.with_suffix('.partial').exists())

    def test_cached_input_is_verified_before_reuse(self):
        with tempfile.TemporaryDirectory() as d:
            source, target = Path(d) / 'input', Path(d) / 'output'
            source.write_bytes(b'valid input')
            target.write_bytes(b'old input')
            wanted = hashlib.sha256(source.read_bytes()).hexdigest()
            prepare.fetch({'url': source.as_uri(), 'sha256': wanted}, target)
            self.assertEqual(target.read_bytes(), source.read_bytes())

    @unittest.skipUnless((ROOT / 'app/src/main/cpp/bootstrap-aarch64.zip').exists(), 'Run prepare_runtime.py first')
    def test_embedded_runtime_matches_pinned_binary_and_launcher(self):
        archive = ROOT / 'app/src/main/cpp/bootstrap-aarch64.zip'
        lock = json.loads((ROOT / 'runtime/lock.json').read_text())
        self.assertEqual(prepare.sha256(archive), archive.with_suffix('.sha256').read_text().strip())
        with zipfile.ZipFile(archive) as z:
            with gzip.open(ROOT / 'app/src/main/assets/engine/opencode.payload', 'rb') as f:
                h = hashlib.sha256()
                header = f.read(64)
                self.assertEqual(header[:4], b'\x7fELF')
                self.assertEqual(int.from_bytes(header[18:20], 'little'), 183)  # ARM64
                h.update(header)
                for chunk in iter(lambda: f.read(1024 * 1024), b''): h.update(chunk)
            self.assertEqual(h.hexdigest(), lock['runtime']['binary_sha256'])
            self.assertEqual(z.read('bin/opencode-android-session'), (ROOT / 'runtime/opencode-android-session').read_bytes())
            self.assertIn('SYMLINKS.txt', z.namelist())
            self.assertIn('bin/bash', z.namelist())


class LauncherBehavior(unittest.TestCase):
    def run_launcher(self, user_input, exit_code=0):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            prefix = base / 'usr'
            (prefix / 'bin').mkdir(parents=True)
            state = base / 'home/.local/state/opencode-android'
            state.mkdir(parents=True)
            (state / 'server.connection').write_text('http://127.0.0.1:4102\nabcdefghijklmnopqrstuvxyz0123456789\n')
            log = base / 'invocations'
            binary = prefix / 'bin/opencode'
            binary.write_text('#!/bin/bash\nprintf "%s\\n" "$@" >> "$CALL_LOG"\nexit ' + str(exit_code) + '\n')
            binary.chmod(0o700)
            launcher = base / 'launch.sh'
            text = (ROOT / 'runtime/opencode-android-session').read_text().replace('/data/data/com.termux/files', str(base))
            launcher.write_text(text)
            import os
            env = dict(os.environ, CALL_LOG=str(log))
            result = subprocess.run(['bash', str(launcher)], input=user_input, text=True, capture_output=True, env=env, timeout=5)
            return result, log.read_text() if log.exists() else ''

    def test_launches_real_binary_with_default_workspace(self):
        result, calls = self.run_launcher('0\n')
        self.assertEqual(result.returncode, 0)
        self.assertIn('/home/projects/default', calls)

    def test_nonzero_engine_exit_is_visible_and_recoverable(self):
        result, _ = self.run_launcher('0\n', exit_code=42)
        self.assertIn('exited with code 42', result.stdout)
        self.assertIn('Diagnostics', result.stdout)
        self.assertEqual(result.returncode, 0)

    def test_continue_is_forwarded_to_opencode(self):
        result, calls = self.run_launcher('2\n0\n')
        self.assertEqual(result.returncode, 0)
        self.assertIn('--continue\n', calls)

    def test_relative_workspace_is_rejected(self):
        result, _ = self.run_launcher('3\nrelative/path\n0\n')
        self.assertIn('Use an absolute path', result.stdout)

    def test_eof_closes_without_relaunch_loop(self):
        result, calls = self.run_launcher('')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls.count('/home/projects/default\n'), 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
