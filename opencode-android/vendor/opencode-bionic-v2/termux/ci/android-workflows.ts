/** Declarative phone recipes. Persist selectors, never snapshot node references or executable code. */
export type Selector = { viewId?: string; text?: string; description?: string }
export type Node = Selector & { node: string; enabled?: boolean }
export type Screen = { package: string; nodes: Node[]; progressKey?: string }
export type Step = {
  action: string; packageName?: string; operation?: string; text?: string;
  contact?: string; contactId?: string; phoneNumber?: string; query?: string;
  selector?: Selector; description?: string;
}
export type Recipe = { name: string; description: string; steps: Step[]; learnedAt: number; runs: number }
export const screenOf = (value: any): Screen | undefined => {
  const screen = value?.screen ?? value
  return typeof screen?.package === "string" && Array.isArray(screen.nodes) ? screen : undefined
}
export function resolve(screen: Screen, selector: Selector): Node {
  const keys = (["viewId", "text", "description"] as const).filter(k => !!selector[k])
  if (!keys.length) throw new Error("A stable selector is required")
  const matches = screen.nodes.filter(n => n.enabled !== false && keys.every(k => n[k] === selector[k]))
  if (matches.length !== 1) throw new Error(`Selector matched ${matches.length} nodes; inspect the current screen and repair the workflow`)
  return matches[0]!
}
export function selectorFor(screen: Screen, ref: string): Selector {
  const node = screen.nodes.find(n => n.node === ref)
  if (!node) throw new Error("Node was not present in the observed screen")
  const candidates: Selector[] = [
    { viewId: node.viewId },
    { viewId: node.viewId, text: node.text, description: node.description },
    { text: node.text, description: node.description },
  ]
  for (const candidate of candidates) {
    const clean = Object.fromEntries(Object.entries(candidate).filter(([,v]) => !!v))
    try { if (resolve(screen, clean).node === ref) return clean } catch { }
  }
  throw new Error("This node has no unique stable selector; continue interactively")
}
export function parameterize<T>(value: T, parameters: Record<string,string>): T {
  return walk(value, text => {
    const match = Object.entries(parameters).find(([,v]) => v !== "" && v === text)
    return match ? "${" + match[0] + "}" : text
  })
}
export function expand<T>(value: T, parameters: Record<string,string>): T {
  return walk(value, text => {
    const match = /^\$\{([a-zA-Z][a-zA-Z0-9_]*)\}$/.exec(text)
    if (!match) return text
    if (!Object.hasOwn(parameters, match[1]!)) throw new Error(`Missing workflow parameter: ${match[1]}`)
    return parameters[match[1]!]!
  })
}
function walk<T>(value:T, transform:(text:string)=>string):T {
  if (typeof value === "string") return transform(value) as T
  if (Array.isArray(value)) return value.map(v=>walk(v,transform)) as T
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,walk(v,transform)])) as T
  return value
}
export function completed(value:any):boolean {
  return !value?.error && !value?.observationError && ![
    "failed", "action_rejected", "focus_restored_action_not_executed", "send_outcome_unknown", "send_attempt_recorded",
  ].includes(value?.status)
}
export function describe(step:Step):string {
  const app = step.packageName ? ` in ${step.packageName}` : ""
  if (step.action === "whatsapp_send") return `Send WhatsApp message to ${step.contact}: ${JSON.stringify(step.text)}`
  if (step.action === "call") return `Call ${step.contact}${step.phoneNumber ? ` (${step.phoneNumber})` : ""}`
  if (step.action === "notification_reply") return `Reply to notification: ${JSON.stringify(step.text)}`
  if (step.action === "ui") return `${step.operation}${app}: ${JSON.stringify(step.selector ?? {})}${step.text === undefined ? "" : ` → ${JSON.stringify(step.text)}`}`
  return `${step.action}${app}${step.query ? `: ${step.query}` : ""}`
}
