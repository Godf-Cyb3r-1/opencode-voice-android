import * as THREE from "three";

export const AMBER = new THREE.Color("#ff8a1a");
export const HOT = new THREE.Color("#ffc46b");

/** camera zoom (0 = default view, 1 = deep nucleus) */
export const zoom = { scroll: 0, target: 0 };

/** per-frame presence signals, eased by Rig and read by every layer */
export const fx = {
  listen: 0,
  think: 0,
  speak: 0,
  input: 0,
  voice: 0,
  lean: 0,
  leanV: 0,
  deep: 0,
  dir: new THREE.Vector3(1, 0, 0),
  dir2: new THREE.Vector3(0, 1, 0),
};

/** "out" = outer hologram (fades as we breach the nucleus), "in" = nucleus only */
export type Fade = "out" | "in";
type Fadeable = THREE.Material & { userData: { fade: Fade; base: number; flow?: number } };
export const fadeables = new Set<Fadeable>();
const register = <M extends THREE.Material>(m: M, fade: Fade, base: number, flow?: number): M => {
  Object.assign(m.userData, { fade, base, flow });
  fadeables.add(m as unknown as Fadeable);
  return m;
};

export const glow = (intensity = 1.6, opacity = 0.8, color = AMBER, fade: Fade = "out") =>
  register(
    new THREE.MeshBasicMaterial({
      color: color.clone().multiplyScalar(intensity),
      transparent: true,
      opacity,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      side: THREE.DoubleSide,
      toneMapped: false,
    }),
    fade,
    opacity
  );

export const lineGlow = (intensity = 1.4, opacity = 0.5, color = AMBER, fade: Fade = "out") =>
  register(
    new THREE.LineBasicMaterial({
      color: color.clone().multiplyScalar(intensity),
      transparent: true,
      opacity,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      toneMapped: false,
    }),
    fade,
    opacity
  );

/**
 * Circuit line: identical to lineGlow at rest (uFlow = 0); when computing, light
 * packets race along each segment (aT 0→1) modulated by a travelling neural wave.
 */
const flowVert = `
uniform float uTime; uniform vec3 uDir;
attribute float aT; attribute float aSeed;
varying float vT; varying float vSeed; varying float vWave;
void main(){
  vT = aT; vSeed = aSeed;
  vec3 n = normalize(position + 0.0001);
  vWave = pow(0.5 + 0.5 * sin(dot(n, uDir) * 3.0 - uTime * 3.1 + aSeed * 2.2), 3.0);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}`;
const flowFrag = `
uniform vec3 uColor; uniform float uOpacity; uniform float uFade; uniform float uTime; uniform float uFlow;
varying float vT; varying float vSeed; varying float vWave;
void main(){
  float head = fract(uTime * (0.35 + vSeed * 0.9) + vSeed * 9.0);
  float d1 = vT - head;
  float d2 = vT - fract(head + 0.47);
  float pk = exp(-d1 * d1 * 220.0) + 0.55 * exp(-d2 * d2 * 320.0);
  float g = uFlow * (pk * (0.35 + vWave) * 4.5 + vWave * 0.4);
  gl_FragColor = vec4(uColor * (1.0 + g), uOpacity * uFade * (1.0 + g * 0.9));
}`;

export const flowLine = (intensity = 1.4, opacity = 0.5, color = AMBER, fade: Fade = "out", flow = 0) =>
  register(
    new THREE.ShaderMaterial({
      vertexShader: flowVert,
      fragmentShader: flowFrag,
      uniforms: {
        uColor: { value: color.clone().multiplyScalar(intensity) },
        uOpacity: { value: opacity },
        uFade: { value: 1 },
        uTime: { value: 0 },
        uFlow: { value: flow },
        uDir: { value: fx.dir },
      },
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      toneMapped: false,
    }),
    fade,
    opacity,
    flow
  );

/** add aT (0→1 along each segment) + aSeed to a LineSegments geometry */
export const withFlow = (geo: THREE.BufferGeometry, seed = 1, along?: number[]) => {
  const n = geo.getAttribute("position").count;
  const r = rng(seed);
  const t = new Float32Array(n);
  const s = new Float32Array(n);
  for (let i = 0; i < n; i += 2) {
    const v = r();
    t[i] = along ? along[i] : 0;
    t[i + 1] = along ? along[i + 1] : 1;
    s[i] = v;
    s[i + 1] = v;
  }
  geo.setAttribute("aT", new THREE.BufferAttribute(t, 1));
  geo.setAttribute("aSeed", new THREE.BufferAttribute(s, 1));
  return geo;
};

export const rng = (seed: number) => () => {
  seed = (seed * 16807) % 2147483647;
  return (seed - 1) / 2147483646;
};

export const easeOutExpo = (x: number) => (x >= 1 ? 1 : 1 - Math.pow(2, -10 * x));
export const clamp01 = (x: number) => Math.min(1, Math.max(0, x));
