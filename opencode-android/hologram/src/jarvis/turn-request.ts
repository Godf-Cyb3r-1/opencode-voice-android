import type { TurnSnapshot } from "./turn-controller";

type Model = { id: string; providerID: string; enabled: boolean; status: string; type?: string; cost: { input: number; output: number; cache: { read: number; write: number } }[]; capabilities: { input: string[]; output: string[] } };
type Health = { until: number; latency?: number };
const KEY = "jarvis-turn-model-health-v1";
const PRIORITY = ["glm-5.3-flash-free", "mimo-v2.6-flash-free", "nemotron-3.5-lightning-free", "big-pickle"];
const ACTIONS = new Set(["LISTEN", "RESPOND", "ACKNOWLEDGE", "YIELD"]);
export function createTurnRequester() {
  let health: Record<string, Health> = {};
  let preferred = "";
  let catalog: Model[] = [];
  let catalogUntil = 0;
  try {
    const saved = JSON.parse(globalThis.localStorage?.getItem(KEY) || "null");
    if (saved && typeof saved.preferred === "string" && saved.health && typeof saved.health === "object") {
      preferred = saved.preferred;
      for (const [id, item] of Object.entries(saved.health)) {
        const h = item as Health;
        if (Number.isFinite(h?.until)) health[id] = { until: Math.min(h.until, Date.now() + 3600000), latency: Number.isFinite(h.latency) ? h.latency : undefined };
      }
    }
  } catch { /* Storage is optional. */ }
  function save() { try { globalThis.localStorage?.setItem(KEY, JSON.stringify({ health, preferred })); } catch {} }
  return async function requestTurn(snapshot: TurnSnapshot, signal: AbortSignal, token?: string | null, status?: (message: string) => void): Promise<string> {
    const headers = { "Content-Type": "application/json", ...(token ? { Authorization: `Basic ${token}` } : {}) };
    async function call(url: string, body?: unknown, ms = 12000) {
      signal.throwIfAborted();
      const timeout = new AbortController();
      const cancel = () => timeout.abort();
      signal.addEventListener("abort", cancel, { once: true });
      const timer = setTimeout(cancel, ms);
      try {
        const response = await fetch(url, { method: body ? "POST" : "GET", headers, signal: timeout.signal, ...(body ? { body: JSON.stringify(body) } : {}) });
        const data = await response.json().catch(() => null);
        signal.throwIfAborted();
        return { response, data };
      } finally { clearTimeout(timer); signal.removeEventListener("abort", cancel); }
    }
    if (Date.now() >= catalogUntil) {
      try {
        const { response, data } = await call("/api/model", undefined, 5000);
        if (!response.ok || !Array.isArray(data?.data)) throw new Error("Model discovery failed");
        catalog = data.data.filter((m: Model) => m.providerID === "opencode" && m.enabled && m.status !== "deprecated" && (!m.type || m.type === "chat") &&
          m.capabilities?.input?.includes("text") && m.capabilities?.output?.includes("text") && m.cost?.length > 0 &&
          m.cost.every(c => c.input === 0 && c.output === 0 && c.cache?.read === 0 && c.cache?.write === 0));
        catalogUntil = Date.now() + 300000;
      } catch (error) {
        signal.throwIfAborted();
        if (!catalog.length) throw new Error("Cannot discover available free controller models. Your speech remains pending.");
      }
    }
    const rank = (id: string) => id === preferred ? -100 : PRIORITY.includes(id) ? PRIORITY.indexOf(id) : /flash|lightning/i.test(id) ? 10 : id === "flint-40b-free" ? 100 : 20;
    const candidates = catalog.filter(m => !(health[m.id]?.until > Date.now())).sort((a, b) => rank(a.id) - rank(b.id) || (health[a.id]?.latency ?? 99999) - (health[b.id]?.latency ?? 99999)).slice(0, 3);
    if (!candidates.length) throw new Error(catalog.length ? "Controller models are cooling down after provider failures. Your speech remains pending; try again later or send typed text." : "No enabled free controller models are available. Your speech remains pending.");
    let lastError = "No valid decision";
    for (const model of candidates) {
      signal.throwIfAborted();
      status?.(`Checking conversation with ${model.id}…`);
      const start = Date.now();
      let cooldown = 60000;
      try {
        const { response, data } = await call("/api/experimental/generate/text", { purpose: "jarvis-turn", model: { providerID: model.providerID, id: model.id }, prompt: JSON.stringify(snapshot) });
        if (response.ok) {
          const action = typeof data?.data?.text === "string" ? data.data.text.trim().toUpperCase().replace(/[.!]$/, "") : "";
          if (!ACTIONS.has(action)) throw new Error("Invalid turn decision");
          health[model.id] = { until: 0, latency: Date.now() - start };
          preferred = model.id; save();
          return action;
        }
        const detail = data?.error ?? data;
        const reason = detail?.reason;
        lastError = `${model.id}: ${typeof detail?.message === "string" ? detail.message.replace(/\s+/g, " ").slice(0, 180) : response.status}`;
        // A rejected prompt or local authentication error is not fixed by cycling models.
        if (reason === "content_filter" || reason === "invalid_request" || response.status === 401 || response.status === 403 || response.status === 400) throw Object.assign(new Error(lastError), { terminal: true });
        cooldown = reason === "rate_limit" || response.status === 429 ? 600000 : reason === "authentication" ? 300000 : 60000;
        if (typeof detail?.retryAfterMs === "number" && Number.isFinite(detail.retryAfterMs)) cooldown = Math.max(cooldown, Math.min(detail.retryAfterMs, 3600000));
        if (response.status === 404) catalogUntil = 0;
      } catch (error) {
        signal.throwIfAborted();
        if ((error as { terminal?: boolean })?.terminal) throw error;
        lastError = `${model.id}: ${error instanceof Error ? error.message : "Request failed"}`;
      }
      health[model.id] = { ...health[model.id], until: Date.now() + cooldown }; save();
    }
    throw new Error(`No controller succeeded. ${lastError}. Your speech remains pending.`);
  };
}
export const requestTurn = createTurnRequester();
