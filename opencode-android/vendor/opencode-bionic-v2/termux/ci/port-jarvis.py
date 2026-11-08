#!/usr/bin/env python3
"""Apply only Android build adapters to the pinned M9 source; preserve its core."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
file = root / 'packages/cli/script/build.ts'
s = file.read_text()
patch = (Path(__file__).resolve().parent.parent / 'patches/0001-termux-2x-port.patch').read_text()
plugin = patch[patch.index('+// opentuiAliasPlugin'):patch.index(' const dir =')]
plugin = '\n'.join(line[1:] for line in plugin.splitlines() if line.startswith('+'))
s = s.replace('const dir = path.resolve', plugin + '\nconst dir = path.resolve', 1)
s = s.replace('      appAssetsPlugin,', '      opentuiAliasPlugin,\n      appAssetsPlugin,', 1)
old = '''  const opencodePty = await resolveOpencodePty({
    platform: item.os,
    arch: item.arch,
    ...(item.os === "linux" ? { libc: item.abi ?? "glibc" } : {}),
  })'''
assert old in s, 'Pinned PTY build adapter no longer matches'
s = s.replace(old, '''  const source = path.join(dir, "../../node_modules/@opencode/pty-linux-arm64-gnu/bin/opencode-pty")
  const opencodePty = {
    source,
    version: "0.2.0",
    sha256: createHash("sha256").update(Buffer.from(await Bun.file(source).arrayBuffer())).digest("hex"),
  }''')
s = s.replace('contents: `export default () => require(${JSON.stringify(parcelWatcherPackage)})`,', 'contents: `export default () => { throw new Error("Android uses filesystem watch fallback") }`,')
s = s.replace('minify: true,', 'minify: false,')
s = s.replace('bytecode: true,', 'bytecode: false,').replace('splitting: true,', 'splitting: false,')
file.write_text(s)
file = root / 'packages/cli/script/app-assets.ts'
s = file.read_text().replace('  if (options?.skipBuild)', '  if (process.env.JARVIS_APP_ARCHIVE) return Buffer.from(await Bun.file(process.env.JARVIS_APP_ARCHIVE).arrayBuffer())\n  if (options?.skipBuild)', 1)
file.write_text(s)
# The application archive is built on Linux from this same pinned revision.
assert 'memory_find' in (root / 'packages/core/src/jarvis/agent.ts').read_text()
assert 'JARVIS_PERSONA_VERSION_UNIVERSAL_V2' in (root / 'jarvis/persona.md').read_text()
print('Applied Android adapters; Jarvis persona, memory and controller code retained')

# Install native phone tools without changing the approved persona text.
import subprocess
subprocess.run(["python3", str(Path(__file__).resolve().parent / "install_phone_plugin.py"), str(root), str(Path(__file__).resolve().parent / "android-phone.ts")], check=True)

subprocess.run(["python3", str(Path(__file__).resolve().parent / "patch-conversation.py"), str(root)], check=True)
