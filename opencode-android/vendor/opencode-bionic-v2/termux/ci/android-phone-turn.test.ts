import { expect, test } from "bun:test"
import { PhoneTurn } from "./android-phone-turn"
test("a progressing app flow can take more than six actions", () => {
  const turn = new PhoneTurn()
  for (let i = 0; i < 40; i++) {
    turn.observe(`screen-${i}`)
    expect(turn.admit("ui")).toBe(true)
  }
})
test("unchanged screens stop repeat mutation but keep inspection and recovery available", () => {
  const turn = new PhoneTurn()
  turn.observe("unchanged")
  expect(turn.admit("ui", "click:button-1")).toBe(true)
  for (let i = 0; i < 4; i++) turn.observe("unchanged")
  expect(turn.admit("ui", "click:button-1")).toBe(false)
  expect(turn.admit("ui", "click:retry-button")).toBe(true)
  expect(turn.admit("ui", "click:retry-button")).toBe(false)
  for (const action of ["screen", "open", "back", "home", "status"]) expect(turn.admit(action)).toBe(true)
  turn.observe("recovered-screen")
  expect(turn.admit("ui")).toBe(true)
})
test("one user turn cannot automatically retry a dedicated send", () => {
  const turn = new PhoneTurn()
  expect(turn.admit("whatsapp_send")).toBe(true)
  expect(turn.admit("whatsapp_send")).toBe(false)
  turn.settleSend("send_outcome_unknown")
  expect(turn.admit("ui")).toBe(false)
  expect(turn.admit("screen")).toBe(true)
  expect(new PhoneTurn().admit("whatsapp_send")).toBe(true)
})
