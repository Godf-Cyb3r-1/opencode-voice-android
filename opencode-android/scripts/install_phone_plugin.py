#!/usr/bin/env python3
"""Register the Android-only native phone plugin in the pinned Jarvis core."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
source=Path(sys.argv[2])
(root/'packages/core/src/jarvis/android-phone.ts').write_text(source.read_text())
p=root/'packages/core/src/plugin/internal.ts';s=p.read_text();s=s.replace('import { JarvisControlTools }', 'import { AndroidPhone } from "../jarvis/android-phone.js"\nimport { JarvisControlTools }',1)
s=s.replace('  JarvisControlTools.Plugin,','  JarvisControlTools.Plugin,\n  AndroidPhone.Plugin,',1);p.write_text(s)
p=root/'packages/core/src/jarvis/agent.ts';s=p.read_text().replace('export const permissions = [','export const permissions = [',1)
s=s.replace('  { action: "command_run", resource: "*", effect: "allow" },','  { action: "command_run", resource: "*", effect: "allow" },\n  { action: "phone", resource: "*", effect: "allow" },',1);p.write_text(s)
assert 'AndroidPhone.Plugin' in (root/'packages/core/src/plugin/internal.ts').read_text()
