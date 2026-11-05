#!/usr/bin/env python3
"""Align the Android composer with the configured primary agent."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
def edit(path,old,new):
    file=root/path
    text=file.read_text()
    assert old in text, f'Pinned source changed: {path}'
    file.write_text(text.replace(old,new))
edit('packages/app/src/providers/models/agent.ts',
    'items: T[], name?: string)',
    'items: T[], name?: string, defaultAgent?: string)')
edit('packages/app/src/providers/models/agent.ts',
    'return items.find((item) => item.name === name) ?? items.find((item) => item.name === "build") ?? items[0]',
    '''return items.find((item) => item.name === name) ?? items.find((item) => item.name === defaultAgent) ?? items.find((item) => item.name === "build") ?? items[0]''')
edit('packages/app/src/providers/models/selection.tsx',
    'const agentsVisible = createMemo(() => settings.visibility.customAgents() || hasCustomAgent(list()))',
    'const agentsVisible = createMemo(() => settings.visibility.customAgents() || hasCustomAgent(list()) || list().some((item) => item.name === "jarvis"))')
edit('packages/app/src/providers/models/selection.tsx',
    'current: list()[0]?.name,', 'current: undefined,')
edit('packages/app/src/providers/models/selection.tsx',
    '''    const pickAgent = (name: string | undefined) => {
      return resolveAgent(list(), name)
    }''',
    '''    const configuredAgent = () => {
      const entry = data.location.config.list({ directory: sdk().directory })
        ?.findLast((entry) => entry.type === "document" && entry.info.default_agent !== undefined)
      return (entry?.type === "document" ? entry.info.default_agent : undefined) ?? "jarvis"
    }

    const pickAgent = (name: string | undefined) => {
      return resolveAgent(list(), name, configuredAgent())
    }''')
edit('packages/app/src/providers/models/selection.tsx',
    'setStore("current", items[0]?.name)', 'setStore("current", resolveAgent(items, undefined, configuredAgent())?.name)')
edit('packages/app/src/providers/models/selection.tsx',
    '(agentsVisible() ? store.current : "build")', '(agentsVisible() ? store.current : undefined)')
test=root/'packages/app/src/providers/models/agent.test.ts'
with test.open('a') as out:
    out.write('''

test("configured Jarvis beats the alphabetically first Default agent", () => {
  expect(resolveAgent([{ name: "default" }, { name: "jarvis" }], undefined, "jarvis")?.name).toBe("jarvis")
})

test("an explicit worker choice remains selected", () => {
  expect(resolveAgent([{ name: "default" }, { name: "jarvis" }], "default", "jarvis")?.name).toBe("default")
})

test("a stale choice resolves to the configured primary", () => {
  expect(resolveAgent([{ name: "default" }, { name: "jarvis" }], "removed", "jarvis")?.name).toBe("jarvis")
})

test("a missing configured primary retains the upstream fallback", () => {
  expect(resolveAgent([{ name: "default" }, { name: "build" }], undefined, "jarvis")?.name).toBe("build")
  expect(resolveAgent([], undefined, "jarvis")).toBeUndefined()
})
''')
print('Patched Android agent selection without changing worker-tool authorization')
