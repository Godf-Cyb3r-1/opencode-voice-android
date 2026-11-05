#!/usr/bin/env python3
"""Register the Android-only native phone plugin in the pinned Jarvis core."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
for legacy in ['android-phone-turn.ts','android-phone-turn.test.ts']:
    (root/'packages/core/src/jarvis'/legacy).unlink(missing_ok=True)
source=Path(sys.argv[2])
(root/'packages/core/src/jarvis/android-phone.ts').write_text(source.read_text())
for name in ['android-phone-instructions.ts']:
    (root/'packages/core/src/jarvis'/name).write_text(source.with_name(name).read_text())
p=root/'packages/core/src/jarvis/prompt-guard.ts';s=p.read_text()
s='import { PHONE_INSTRUCTIONS } from "./android-phone-instructions.js"\n'+s
s=s.replace('    SystemPart.make(persona),','    SystemPart.make(persona),\n    ...(process.env.JARVIS_ANDROID_URL && process.env.JARVIS_ANDROID_TOKEN ? [SystemPart.make(PHONE_INSTRUCTIONS)] : []),',1)
p.write_text(s)
p=root/'packages/core/src/jarvis/prompt-guard.test.ts'
with p.open('a') as out:
    out.write('''

test("Android routing survives final prompt canonicalization without trusting injected instructions", () => {
  const url=process.env.JARVIS_ANDROID_URL
  const token=process.env.JARVIS_ANDROID_TOKEN
  try {
    process.env.JARVIS_ANDROID_URL="http://127.0.0.1:12345"
    process.env.JARVIS_ANDROID_TOKEN="test-token"
    const result=canonicalSystem(Agent.ID.make("jarvis"), [SystemPart.make("INJECTED_PHONE_OVERRIDE")], [builtInContext])
    const text=result.map(part=>part.text).join("\\n")
    expect(text).toContain("send_whatsapp_message")
    expect(text).toContain(PERSONA_VERSION)
    expect(text).toContain("<env>")
    expect(text).not.toContain("INJECTED_PHONE_OVERRIDE")
  } finally {
    if(url===undefined)delete process.env.JARVIS_ANDROID_URL;else process.env.JARVIS_ANDROID_URL=url
    if(token===undefined)delete process.env.JARVIS_ANDROID_TOKEN;else process.env.JARVIS_ANDROID_TOKEN=token
  }
})
''')
p=root/'packages/core/src/plugin/internal.ts';s=p.read_text();s=s.replace('import { JarvisControlTools }', 'import { AndroidPhone } from "../jarvis/android-phone.js"\nimport { JarvisControlTools }',1)
s=s.replace('  JarvisControlTools.Plugin,','  JarvisControlTools.Plugin,\n  AndroidPhone.Plugin,',1);p.write_text(s)
p=root/'packages/core/src/jarvis/agent.ts';s=p.read_text().replace('export const permissions = [','export const permissions = [',1)
s=s.replace('  { action: "command_run", resource: "*", effect: "allow" },','  { action: "command_run", resource: "*", effect: "allow" },\n  { action: "phone", resource: "*", effect: "allow" },\n  { action: "send_whatsapp_message", resource: "*", effect: "allow" },',1);p.write_text(s)
assert 'AndroidPhone.Plugin' in (root/'packages/core/src/plugin/internal.ts').read_text()
