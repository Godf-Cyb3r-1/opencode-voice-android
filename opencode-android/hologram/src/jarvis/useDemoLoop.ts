import { useEffect } from "react";
import { jarvis } from "./store";

const HEARD = "Jarvis, run a diagnostic on the core and tell me how we're holding up.";
const REPLY =
  "All systems nominal. Core output is holding at ninety-eight percent and the neural lattice is stable. Ready when you are.";

/**
 * Simulated presence loop (25s):
 * 0–5 idle · 5–10 listening · 10–15 speaking · 15–20 idle · 20–25 thinking → repeat
 */
export function useDemoLoop(enabled = true) {
  useEffect(() => {
    if (!enabled) return;
    const timers: number[] = [];
    const at = (s: number, fn: () => void) => timers.push(window.setTimeout(fn, s * 1000));
    const words = HEARD.split(" ");
    const run = () => {
      timers.length = 0;
      jarvis.idle();
      at(5, () => jarvis.listen(""));
      words.forEach((_, i) => at(5.7 + i * 0.3, () => jarvis.hear(words.slice(0, i + 1).join(" "))));
      at(10, () => jarvis.speak(REPLY));
      at(15, () => jarvis.idle());
      at(20, () => jarvis.think());
      at(25, run);
    };
    run();
    return () => {
      timers.forEach(clearTimeout);
      jarvis.idle();
    };
  }, [enabled]);
}
