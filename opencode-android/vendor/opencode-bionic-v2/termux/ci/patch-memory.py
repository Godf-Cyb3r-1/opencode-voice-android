"""Patch the pinned M9 core without changing its persona or project scope."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
memory = root / "packages/core/src/jarvis/auto-memory.ts"
text = memory.read_text()
anchor = 'if (previous?.text === fact.text) continue'
assert text.count(anchor) == 1
memory.write_text(text.replace(anchor, 'if (previous && (previous.at > at || (previous.at === at && previous.text === fact.text))) continue'))

request = root / "packages/core/src/session/model-request.ts"
text = request.read_text()
assert 'const oldTexts = alreadyBackfilled' in text
text = 'import { DateTime } from "effect"\n' + text
start = text.index('            const marker = "jarvis:memory:backfill:v1:')
end = text.index('            if (process.env.JARVIS_PROMPT_DIAGNOSTICS', start)
text = text[:start] + '''            const marker = "jarvis:memory:backfill:v2:" + session.projectID
            const alreadyBackfilled = (yield* kv.get(marker)) === true
            const oldMessages = alreadyBackfilled || !historyStore
              ? []
              : yield* Effect.gen(function* () {
                  const recent = yield* historyStore.list({
                    project: session.projectID, limit: 40, order: "desc",
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
            if (changed) yield* kv.set(key, state as KV.Value)
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
