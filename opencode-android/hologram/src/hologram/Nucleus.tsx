import { useMemo, useRef } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { AMBER, HOT, fx, glow, flowLine, withFlow, rng, easeOutExpo } from "./materials";

/**
 * Deep-nucleus micro-architecture. Invisible in the default view; revealed only as
 * the camera breaches the core (fx.deep): a multi-shell neural mesh with travelling
 * light packets, rotating code matrices, and circuit traces feeding a singularity.
 */

const nodeVert = `
uniform float uTime; uniform float uSize; uniform float uDpr; uniform float uFire; uniform vec3 uDir; uniform vec3 uDir2;
attribute float aSeed;
varying float vA; varying float vFire;
void main(){
  vec3 p = position;
  float s = aSeed * 6.2831;
  p += vec3(sin(uTime*1.3+s*3.0), cos(uTime*1.1+s*5.0), sin(uTime*1.7+s*7.0)) * 0.0018;
  vec3 n = normalize(position + 0.00001);
  float r = length(position);
  float w1 = sin(dot(n, uDir) * 3.4 + r * 30.0 - uTime * 4.6 + aSeed * 2.2);
  float w2 = sin(dot(n, uDir2) * 2.6 - r * 22.0 - uTime * 3.2 + aSeed * 3.4);
  float fire = max(pow(max(w1, 0.0), 10.0), 0.75 * pow(max(w2, 0.0), 12.0)) * uFire;
  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_PointSize = min(uSize * uDpr * (1.0 + fire * 2.6) * (10.0 / max(-mv.z, 0.002)), 110.0);
  vA = (0.45 + 0.55 * fract(aSeed * 13.7)) * smoothstep(0.004, 0.035, -mv.z);
  vFire = fire;
  gl_Position = projectionMatrix * mv;
}`;
const nodeFrag = `
uniform vec3 uColor; uniform float uFade; varying float vA; varying float vFire;
void main(){
  float d = length(gl_PointCoord - 0.5);
  float a = smoothstep(0.5, 0.0, d); a *= a;
  float k = vA * uFade;
  gl_FragColor = vec4(uColor * a * k * (1.0 + vFire * 2.8), a * k);
}`;

const nodeMaterial = (size: number, intensity: number) =>
  new THREE.ShaderMaterial({
    vertexShader: nodeVert,
    fragmentShader: nodeFrag,
    uniforms: {
      uTime: { value: 0 },
      uSize: { value: size },
      uDpr: { value: Math.min(window.devicePixelRatio, 2) * 30 },
      uFire: { value: 0.3 },
      uFade: { value: 0 },
      uDir: { value: fx.dir },
      uDir2: { value: fx.dir2 },
      uColor: { value: AMBER.clone().lerp(HOT, 0.5).multiplyScalar(intensity) },
    },
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
    toneMapped: false,
  });

const pointsGeo = (pts: THREE.Vector3[], seed: number) => {
  const r = rng(seed);
  const g = new THREE.BufferGeometry().setFromPoints(pts);
  g.setAttribute("aSeed", new THREE.BufferAttribute(Float32Array.from(pts, () => r()), 1));
  return g;
};

const SHELLS: [number, number][] = [[0.05, 34], [0.09, 60], [0.135, 92], [0.185, 128]];

const buildNet = (seed: number) => {
  const r = rng(seed);
  const nodes: THREE.Vector3[] = [];
  SHELLS.forEach(([rad, n], si) => {
    for (let i = 0; i < n; i++) {
      const k = i + 0.5;
      const phi = Math.acos(1 - (2 * k) / n);
      const th = Math.PI * (1 + Math.sqrt(5)) * k + si * 0.9;
      const j = 1 + (r() - 0.5) * 0.22;
      nodes.push(new THREE.Vector3(Math.cos(th) * Math.sin(phi), Math.sin(th) * Math.sin(phi), Math.cos(phi)).multiplyScalar(rad * j));
    }
  });
  const pts: number[] = [];
  const seen = new Set<string>();
  nodes.forEach((a, i) => {
    nodes
      .map((b, j) => [j, a.distanceToSquared(b)] as const)
      .filter(([j]) => j !== i)
      .sort((x, y) => x[1] - y[1])
      .slice(0, 3)
      .forEach(([j]) => {
        const key = i < j ? `${i}-${j}` : `${j}-${i}`;
        if (seen.has(key)) return;
        seen.add(key);
        const b = nodes[j];
        pts.push(a.x, a.y, a.z, b.x, b.y, b.z);
      });
  });
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pts, 3));
  return { nodes, edges: withFlow(g, seed + 1) };
};

/** manhattan traces from the outer shell inward to the singularity */
const buildCircuits = (seed: number, count = 18) => {
  const r = rng(seed);
  const pts: number[] = [];
  const along: number[] = [];
  const pads: THREE.Vector3[] = [];
  for (let c = 0; c < count; c++) {
    const p = new THREE.Vector3(r() - 0.5, r() - 0.5, r() - 0.5).normalize().multiplyScalar(0.2 + r() * 0.03);
    const path = [p.clone()];
    while (p.length() > 0.014) {
      const order = [0, 1, 2].sort((a, b) => Math.abs(p.getComponent(b)) - Math.abs(p.getComponent(a)));
      let ax = r() < 0.65 ? order[0] : order[1];
      if (Math.abs(p.getComponent(ax)) < 0.003) ax = order[0];
      const v = p.getComponent(ax);
      p.setComponent(ax, v - Math.sign(v) * Math.min(Math.abs(v), 0.012 + r() * 0.03));
      path.push(p.clone());
    }
    path.push(new THREE.Vector3());
    let total = 0;
    const cum = path.map((q, i) => (total += i ? q.distanceTo(path[i - 1]) : 0));
    for (let i = 1; i < path.length; i++) {
      const a = path[i - 1], b = path[i];
      pts.push(a.x, a.y, a.z, b.x, b.y, b.z);
      along.push(cum[i - 1] / total, cum[i] / total);
      if (i < path.length - 1) pads.push(b.clone());
    }
    pads.push(path[0].clone());
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pts, 3));
  return { traces: withFlow(g, seed + 2, along), pads };
};

const codeTexture = (seed: number) => {
  const r = rng(seed);
  const c = document.createElement("canvas");
  c.width = 1024;
  c.height = 96;
  const g = c.getContext("2d")!;
  g.font = '500 20px "Geist Mono Variable", ui-monospace, monospace';
  g.textBaseline = "top";
  const glyphs = "0123456789ABCDEF";
  for (let row = 0; row < 4; row++) {
    let x = r() * 20;
    while (x < 1000) {
      const len = 2 + Math.floor(r() * 4);
      const tok = r() < 0.12 ? "::" : Array.from({ length: len }, () => glyphs[Math.floor(r() * 16)]).join("");
      g.fillStyle = `rgba(255,255,255,${0.2 + r() * 0.8})`;
      g.fillText(tok, x, row * 24 + 2);
      x += g.measureText(tok).width + 12 + r() * 18;
    }
  }
  const tex = new THREE.CanvasTexture(c);
  tex.wrapS = THREE.RepeatWrapping;
  tex.repeat.set(3, 1);
  return tex;
};

const CodeRing = ({ r, h, tilt, speed, seed }: { r: number; h: number; tilt: [number, number, number]; speed: number; seed: number }) => {
  const ref = useRef<THREE.Group>(null!);
  const mat = useMemo(() => {
    const m = glow(1.7, 0.6, HOT, "in");
    m.map = codeTexture(seed);
    return m;
  }, [seed]);
  useFrame((_, dt) => {
    mat.map!.offset.x += dt * speed * (1 + fx.think * 2.2);
    ref.current.rotation.y += dt * speed * 0.6;
  });
  return (
    <group rotation={tilt}>
      <group ref={ref}>
        <mesh material={mat}>
          <cylinderGeometry args={[r, r, h, 96, 1, true]} />
        </mesh>
      </group>
    </group>
  );
};

const Singularity = () => {
  const core = useRef<THREE.Mesh>(null!);
  const halo = useRef<THREE.Mesh>(null!);
  const ringA = useRef<THREE.Group>(null!);
  const ringB = useRef<THREE.Group>(null!);
  const m = useMemo(() => ({ core: glow(7, 1, HOT, "in"), halo: glow(2.4, 0.22, AMBER, "in"), ring: glow(3, 0.75, HOT, "in") }), []);
  useFrame(({ clock }, dt) => {
    const t = clock.elapsedTime;
    const f = 21 + fx.think * 16;
    const beat = 0.5 + 0.5 * Math.sin(t * f);
    core.current.scale.setScalar(1 + beat * 0.3 + fx.speak * fx.voice * 0.8);
    halo.current.scale.setScalar(1 + Math.pow(Math.abs(Math.sin(t * f * 0.23)), 6) * 0.7 + fx.think * 0.35);
    ringA.current.rotation.z += dt * (2.4 + fx.think * 3);
    ringB.current.rotation.x -= dt * (1.7 + fx.think * 2);
  });
  return (
    <group>
      <mesh ref={core} material={m.core}><icosahedronGeometry args={[0.0085, 3]} /></mesh>
      <mesh ref={halo} material={m.halo}><icosahedronGeometry args={[0.019, 3]} /></mesh>
      <group ref={ringA}>
        <mesh material={m.ring}><ringGeometry args={[0.027, 0.0279, 64, 1, 0, Math.PI * 1.3]} /></mesh>
      </group>
      <group ref={ringB} rotation={[0, 1.2, 0]}>
        <mesh material={m.ring}><ringGeometry args={[0.034, 0.0346, 64, 1, 0.4, Math.PI * 0.9]} /></mesh>
        <mesh material={m.ring}><ringGeometry args={[0.034, 0.0346, 64, 1, Math.PI * 1.25, Math.PI * 0.5]} /></mesh>
      </group>
    </group>
  );
};

export const Nucleus = () => {
  const group = useRef<THREE.Group>(null!);
  const net = useRef<THREE.Group>(null!);
  const { nodes, edges } = useMemo(() => buildNet(61), []);
  const { traces, pads } = useMemo(() => buildCircuits(83), []);
  const geo = useMemo(() => ({ nodes: pointsGeo(nodes, 5), pads: pointsGeo(pads, 9) }), [nodes, pads]);
  const mats = useMemo(
    () => ({
      edges: flowLine(1.5, 0.26, AMBER, "in", 0.45),
      traces: flowLine(2.4, 0.5, HOT, "in", 1.1),
      nodes: nodeMaterial(0.0014, 2.6),
      pads: nodeMaterial(0.0008, 3.2),
    }),
    []
  );
  useFrame(({ clock }, dt) => {
    const t = clock.elapsedTime;
    group.current.visible = fx.deep > 0.002;
    group.current.scale.setScalar(0.55 + 0.45 * easeOutExpo(fx.deep));
    group.current.rotation.y += dt * 0.05;
    net.current.rotation.y -= dt * 0.04 * (1 + fx.think * 1.5);
    net.current.rotation.x += dt * 0.015;
    for (const m of [mats.nodes, mats.pads]) {
      m.uniforms.uTime.value = t;
      m.uniforms.uFade.value = fx.deep;
      m.uniforms.uFire.value = 0.35 + fx.think * 1.1 + fx.speak * fx.voice * 0.6 + fx.listen * fx.input * 0.4;
    }
  });
  return (
    <group ref={group} visible={false}>
      <group ref={net}>
        <lineSegments geometry={edges} material={mats.edges} />
        <points geometry={geo.nodes} material={mats.nodes} />
      </group>
      <lineSegments geometry={traces} material={mats.traces} />
      <points geometry={geo.pads} material={mats.pads} />
      <CodeRing r={0.072} h={0.016} tilt={[0.3, 0, 0.2]} speed={0.05} seed={3} />
      <CodeRing r={0.118} h={0.02} tilt={[-0.5, 0.4, 0]} speed={-0.035} seed={7} />
      <CodeRing r={0.165} h={0.024} tilt={[1.2, 0, -0.3]} speed={0.025} seed={13} />
      <Singularity />
    </group>
  );
};
