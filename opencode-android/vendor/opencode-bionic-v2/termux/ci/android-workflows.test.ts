import { expect, test } from "bun:test"
import { resolve, selectorFor, parameterize, expand, completed, describe, type Screen } from "./android-workflows"
const screen:Screen={package:"app",nodes:[{node:"1:0",viewId:"app:id/next",text:"Next",enabled:true},{node:"1:1",viewId:"app:id/row",text:"Alice"},{node:"1:2",viewId:"app:id/row",text:"Bob"}]}
test("learned selectors survive regenerated snapshot refs",()=>{
  const selector=selectorFor(screen,"1:2")
  const fresh={...screen,nodes:screen.nodes.map(n=>({...n,node:n.node.replace("1:","9:")}))}
  expect(resolve(fresh,selector).node).toBe("9:2")
  expect(JSON.stringify(selector)).not.toContain("1:2")
})
test("ambiguous, absent and disabled selectors cannot choose a random target",()=>{
  expect(()=>resolve(screen,{viewId:"app:id/row"})).toThrow("2 nodes")
  expect(()=>resolve(screen,{text:"Missing"})).toThrow("0 nodes")
  expect(()=>resolve({...screen,nodes:[{node:"1",text:"Next",enabled:false}]},{text:"Next"})).toThrow()
  expect(()=>resolve(screen,{})).toThrow()
})
test("parameters replace complete values without evaluating code or rewriting labels",()=>{
  const steps=[{action:"whatsapp_send",contact:"Alice",text:"Hi",selector:{text:"Alice settings"}}]
  const saved=parameterize(steps,{recipient:"Alice",message:"Hi"})
  expect(saved[0]!.contact).toBe("${recipient}")
  expect(saved[0]!.selector.text).toBe("Alice settings")
  expect(expand(saved,{recipient:"Bob",message:"$(whoami)"})[0]!.text).toBe("$(whoami)")
  expect(()=>expand(saved,{recipient:"Bob"})).toThrow("message")
})
test("unobserved actions and uncertain sends never become successful learning examples",()=>{
  expect(completed({status:"submitted_to_whatsapp"})).toBe(true)
  for(const result of [{status:"failed"},{status:"send_outcome_unknown"},{status:"focus_restored_action_not_executed"},{status:"action_accepted",observationError:"lost focus"}])expect(completed(result)).toBe(false)
})
test("debug description contains the exact recipient and message",()=>{
  expect(describe({action:"whatsapp_send",contact:"Bob",text:"Hi"})).toBe('Send WhatsApp message to Bob: "Hi"')
})
