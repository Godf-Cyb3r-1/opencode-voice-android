import { expect, test } from "bun:test"
import { PhoneTurn } from "./android-phone-turn"
test("repeated UI inspection is bounded while the native sender remains available", () => {
  const turn = new PhoneTurn()
  for (let i = 0; i < 6; i++) expect(turn.admit(i % 2 ? "ui" : "screen")).toBe(true)
  expect(turn.admit("screen")).toBe(false)
  expect(turn.uiStopped).toBe(true)
  expect(turn.admit("whatsapp_send")).toBe(true)
})
test("one user turn cannot automatically retry a dedicated send", () => {
  const turn = new PhoneTurn()
  expect(turn.admit("whatsapp_send")).toBe(true)
  expect(turn.admit("whatsapp_send")).toBe(false)
  turn.settleSend("send_outcome_unknown")
  expect(turn.admit("ui")).toBe(false)
  expect(new PhoneTurn().admit("whatsapp_send")).toBe(true)
})
