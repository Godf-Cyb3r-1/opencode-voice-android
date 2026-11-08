"""Install chronological memory and an opt-in Android-local profile."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
memory = root / "packages/core/src/jarvis/auto-memory.ts"
text = memory.read_text()
anchor = 'if (previous?.text === fact.text) continue'
assert text.count(anchor) == 1
memory.write_text(text.replace(anchor, 'if (previous && (previous.at > at || (previous.at === at && previous.text === fact.text))) continue'))

memory_source = root / "packages/core/src/jarvis/memory.ts"
with memory_source.open("a") as out:
    out.write("""
/** Android has one local owner. Other OpenCode launches retain project scope. */
export function jarvisMemoryKey(projectID: string, android = process.env.JARVIS_ANDROID_PROFILE === "1") {
  return android ? "jarvis:memory:android-profile:v1" : "jarvis:memory:project:" + projectID
}
""")

control = root / "packages/core/src/jarvis/control-tools.ts"
source = control.read_text().replace(
    'import { findMemories, forget, remember }', 'import { findMemories, forget, jarvisMemoryKey, remember }'
)
assert source.count('"jarvis:memory:project:" + parent.projectID') == 3
source = source.replace('"jarvis:memory:project:" + parent.projectID', 'jarvisMemoryKey(parent.projectID)')
source = source.replace('for this OpenCode project', 'for the current Jarvis memory scope')
source = source.replace('Searches facts within this OpenCode project, not other projects.', 'Searches the Android local profile or the current OpenCode project.')
source = source.replace('project-scoped saved memory', 'saved memory')
control.write_text(source)

source = memory.read_text().replace('favourite|favorite)', 'favourite|favorite|fav)')
source = source.replace('  if (negated.test(cleaned)', """  const name = cleaned.match(/^(?:my name is|call me)\\s+([\\p{L}][\\p{L} .'-]{0,58}?)[.!]?$/iu)
  if (name) return [{ id: "auto:preferred-name", text: "The user wants to be called " + name[1]!.trim() + "." }]
  if (negated.test(cleaned)""", 1)
source = source.replace('  const general = cleaned.match', r'''
  const favorite = cleaned.match(/^(?:remember[,:]? )?my (?:fav(?:ou?rite)?) (food|movie|song|game|book|sport) is ([^!?]{1,90})[.!]?$/i)
  if (favorite) return [{ id: "auto:favorite-" + favorite[1]!.toLowerCase(), text: "The user's favorite " + favorite[1]!.toLowerCase() + " is " + favorite[2]!.replace(/\.+$/, "") + "." }]
  const general = cleaned.match''', 1)
source = source.replace('Saved user preferences from earlier conversations in this same OpenCode project.',
    'Saved user preferences from earlier conversations in the current Jarvis memory scope.')
source = source.replace('Do not claim memories from outside this project.', 'Use only the saved facts provided here.')
memory.write_text(source)

request = root / "packages/core/src/session/model-request.ts"
text = request.read_text()
assert 'const oldTexts = alreadyBackfilled' in text
text = 'import { DateTime } from "effect"\n' + text
text = text.replace('import { itemsOf }', 'import { itemsOf, jarvisMemoryKey }')
text = text.replace('const key = "jarvis:memory:project:" + session.projectID', 'const key = jarvisMemoryKey(session.projectID)')
text = text.replace('const stored = yield* kv.get(key)', '''const profile = yield* kv.get(key)
            const stored = profile ?? (process.env.JARVIS_ANDROID_PROFILE === "1"
              ? yield* kv.get("jarvis:memory:project:" + session.projectID)
              : undefined)''')
start = text.index('            const marker = "jarvis:memory:backfill:v1:')
end = text.index('            if (process.env.JARVIS_PROMPT_DIAGNOSTICS', start)
text = text[:start] + '''            const android = process.env.JARVIS_ANDROID_PROFILE === "1"
            const marker = "jarvis:memory:backfill:v3:" + key
            const alreadyBackfilled = (yield* kv.get(marker)) === true
            const oldMessages = alreadyBackfilled || !historyStore
              ? []
              : yield* Effect.gen(function* () {
                  const recent = yield* historyStore.list({
                    ...(android ? {} : { project: session.projectID }), limit: 40, order: "desc",
                  })
                  const histories = yield* Effect.forEach(
                    recent.filter((item) => item.agent === JarvisAgent.ID).slice(0, 12),
                    (item) => historyStore.messages({
                      sessionID: item.id, type: "user", limit: 40, order: "desc",
                    }),
                  )
                  return histories.flat()
                })
            const current = historyStore
              ? yield* historyStore.messages({ sessionID: session.id, type: "user", limit: 1, order: "desc" })
              : []
            const messages = [...oldMessages, ...current]
              .filter((message) => message.type === "user")
              .sort((a, b) => DateTime.toEpochMillis(a.time.created) - DateTime.toEpochMillis(b.time.created))
            let state = (stored as Record<string, unknown> | undefined) ?? {}
            let changed = false
            for (const message of messages) {
              const learned = learnFromUserTexts(state, [message.text], DateTime.toEpochMillis(message.time.created))
              state = learned.state
              changed ||= learned.changed
            }
            if (changed || (!profile && stored)) yield* kv.set(key, state as KV.Value)
            if (!alreadyBackfilled && historyStore) yield* kv.set(marker, true)
''' + text[end:]
text = text.replace('importedUserMessages: oldTexts.length','importedUserMessages: oldMessages.length').replace('currentUserMessages: texts.length','currentUserMessages: current.length').replace('changed: learned.changed,','changed,').replace('itemsOf(learned.state)','itemsOf(state)').replace('return memoryContext(learned.state)','return memoryContext(state)')
request.write_text(text)

tests = root / "packages/core/src/jarvis/auto-memory.test.ts"
test_source = tests.read_text()
# The duplicate test represents reprocessing the same persisted message, so its timestamp stays the same.
tests.write_text(test_source.replace('serialized, ["I would like dark blue color and often red color too"], 200', 'serialized, ["I would like dark blue color and often red color too"], 100'))
with tests.open("a") as out:
    out.write("""
test("resuming an older session cannot roll back a more recent preference", () => {
  const corrected = learnFromUserTexts({}, ["My favorite color is green"], 200)
  const stale = learnFromUserTexts(corrected.state, ["I like dark blue color"], 100)
  expect(stale.changed).toBe(false)
  expect(memoryContext(stale.state)).toContain("green")
  expect(memoryContext(stale.state)).not.toContain("dark blue")
})
""")

with tests.open("a") as out:
    out.write("""
test("Android sessions share preferences while desktop projects remain isolated", () => {
  const first = learnFromUserTexts({}, ["My fav color is dark blue", "My name is Arjun", "My favorite food is dosa"], 200)
  const profile = new Map([[jarvisMemoryKey("first-project", true), first.state]])
  const recalled = memoryContext(profile.get(jarvisMemoryKey("another-project", true)))
  expect(recalled).toContain("dark blue")
  expect(recalled).toContain("Arjun")
  expect(recalled).toContain("dosa")
  expect(jarvisMemoryKey("first-project", false)).not.toBe(jarvisMemoryKey("another-project", false))
})
""")
tests.write_text('import { jarvisMemoryKey } from "./memory.js"\n' + tests.read_text())
