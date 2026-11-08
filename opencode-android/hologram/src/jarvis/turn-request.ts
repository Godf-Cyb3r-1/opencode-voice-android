import type { TurnSnapshot } from "./turn-controller";

function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(signal.reason ?? new Error("Cancelled")); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, ms);
    if (signal.aborted) abort();
    else signal.addEventListener("abort", abort, { once: true });
  });
}

/** Retry only transient failures; a changed speech revision cancels both attempts. */
export async function requestTurn(snapshot: TurnSnapshot, signal: AbortSignal, token?: string | null): Promise<string> {
  for (let attempt = 0; attempt < 2; attempt++) {
    signal.throwIfAborted();
    const timeout = new AbortController();
    const cancel = () => timeout.abort();
    signal.addEventListener("abort", cancel, { once: true });
    const timer = setTimeout(() => timeout.abort(), 22000);
    let retry = false;
    try {
      const response = await fetch("/api/experimental/generate/text", {
        method: "POST", signal: timeout.signal,
        headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Basic ${token}` } : {}) },
        body: JSON.stringify({ purpose: "jarvis-turn", model: { providerID: "opencode", id: "flint-40b-free" }, prompt: JSON.stringify(snapshot) }),
      });
      const result = await response.json().catch(() => null);
      signal.throwIfAborted();
      if (response.ok) {
        if (typeof result?.data?.text !== "string" || !result.data.text.trim())
          throw new Error("Flint returned no decision. Your speech remains pending.");
        return result.data.text;
      }
      const detail = result?.error ?? result;
      const reason = typeof detail?.reason === "string" ? detail.reason : "";
      const transient = [408, 500, 502, 503, 504].includes(response.status) &&
        (!reason || ["overloaded", "provider"].includes(reason));
      if (attempt === 0 && transient) retry = true;
      else {
        const message = typeof detail?.message === "string" ? detail.message.replace(/\s+/g, " ").slice(0, 240) : "Provider unavailable";
        throw new Error(`Flint: ${message} (${reason || response.status}). Your speech remains pending.`);
      }
    } catch (error) {
      signal.throwIfAborted();
      if (attempt === 0 && (timeout.signal.aborted || error instanceof TypeError)) retry = true;
      else if (timeout.signal.aborted) throw new Error("Flint timed out after retrying. Your speech remains pending.");
      else throw error;
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", cancel);
    }
    if (retry) await pause(500, signal);
  }
  throw new Error("Flint unavailable. Your speech remains pending.");
}
