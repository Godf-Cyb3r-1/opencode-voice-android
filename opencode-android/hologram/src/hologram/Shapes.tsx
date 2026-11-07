import { useMemo, useRef } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { glow, lineGlow, flowLine, withFlow, rng, fx, HOT } from "./materials";

export const Wire = ({ geometry, intensity = 1.3, opacity = 0.35, seed = 1 }: { geometry: THREE.BufferGeometry; intensity?: number; opacity?: number; seed?: number }) => {
  const geo = useMemo(() => withFlow(new THREE.EdgesGeometry(geometry, 1), seed), [geometry, seed]);
  const mat = useMemo(() => flowLine(intensity, opacity), [intensity, opacity]);
  return <lineSegments geometry={geo} material={mat} />;
};

export const LatLong = ({ r = 1.2, lat = 12, lon = 24, intensity = 1.2, opacity = 0.28 }) => {
  const geo = useMemo(() => {
    const pts: number[] = [];
    const seg = 96;
    for (let i = 1; i < lat; i++) {
      const phi = (i / lat) * Math.PI;
      const y = Math.cos(phi) * r, rr = Math.sin(phi) * r;
      for (let j = 0; j < seg; j++) {
        const a = (j / seg) * Math.PI * 2, b = ((j + 1) / seg) * Math.PI * 2;
        pts.push(Math.cos(a) * rr, y, Math.sin(a) * rr, Math.cos(b) * rr, y, Math.sin(b) * rr);
      }
    }
    for (let i = 0; i < lon; i++) {
      const th = (i / lon) * Math.PI * 2;
      for (let j = 0; j < seg / 2; j++) {
        const p1 = (j / (seg / 2)) * Math.PI, p2 = ((j + 1) / (seg / 2)) * Math.PI;
        pts.push(Math.sin(p1) * Math.cos(th) * r, Math.cos(p1) * r, Math.sin(p1) * Math.sin(th) * r);
        pts.push(Math.sin(p2) * Math.cos(th) * r, Math.cos(p2) * r, Math.sin(p2) * Math.sin(th) * r);
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(pts, 3));
    return g;
  }, [r, lat, lon]);
  const mat = useMemo(() => lineGlow(intensity, opacity), [intensity, opacity]);
  return <lineSegments geometry={geo} material={mat} />;
};

const arcPattern = (kind: string, seed: number) => {
  const r = rng(seed);
  const out: [number, number][] = [];
  if (kind === "dash") {
    const n = 36 + Math.floor(r() * 60);
    for (let i = 0; i < n; i++) out.push([i / n, (1 / n) * 0.55]);
  } else if (kind === "full") {
    out.push([0, 1]);
  } else {
    let a = r() * 0.1;
    while (a < 0.97) {
      const len = 0.03 + r() * (kind === "long" ? 0.35 : 0.14);
      if (a + len > 1) break;
      out.push([a, len]);
      a += len + 0.015 + r() * 0.08;
    }
  }
  return out;
};

export interface ArcRingProps { r: number; w: number; kind?: string; seed?: number; intensity?: number; opacity?: number }

export const ArcRing = ({ r, w, kind = "random", seed = 1, intensity = 1.8, opacity = 0.75 }: ArcRingProps) => {
  const arcs = useMemo(() => arcPattern(kind, seed), [kind, seed]);
  const mat = useMemo(() => glow(intensity, opacity), [intensity, opacity]);
  return (
    <group>
      {arcs.map(([s, l], i) => (
        <mesh key={i} material={mat}>
          <ringGeometry args={[r, r + w, Math.max(6, Math.ceil(l * 160)), 1, s * Math.PI * 2, l * Math.PI * 2]} />
        </mesh>
      ))}
    </group>
  );
};

export const Ticks = ({ r = 2.55, count = 180, len = 0.05, major = 10, intensity = 1.6, opacity = 0.6 }) => {
  const geo = useMemo(() => {
    const pts: number[] = [];
    for (let i = 0; i < count; i++) {
      const a = (i / count) * Math.PI * 2;
      const l = i % major === 0 ? len * 2.6 : len;
      pts.push(Math.cos(a) * r, Math.sin(a) * r, 0, Math.cos(a) * (r + l), Math.sin(a) * (r + l), 0);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(pts, 3));
    return g;
  }, [r, count, len, major]);
  const mat = useMemo(() => lineGlow(intensity, opacity), [intensity, opacity]);
  return <lineSegments geometry={geo} material={mat} />;
};

export const Circle = ({ r, intensity = 1.4, opacity = 0.45 }: { r: number; intensity?: number; opacity?: number }) => {
  const geo = useMemo(() => {
    const pts = new THREE.EllipseCurve(0, 0, r, r).getPoints(180).map((p) => new THREE.Vector3(p.x, p.y, 0));
    return new THREE.BufferGeometry().setFromPoints(pts);
  }, [r]);
  const mat = useMemo(() => lineGlow(intensity, opacity), [intensity, opacity]);
  return <lineLoop geometry={geo} material={mat} />;
};

/** radial telemetry bars — idle chatter, a sound receiver when listening, a voice meter when speaking */
export const Telemetry = ({ r = 2.72, count = 120, seed = 3 }) => {
  const ref = useRef<THREE.InstancedMesh>(null!);
  const dummy = useMemo(() => new THREE.Object3D(), []);
  const phases = useMemo(() => { const g = rng(seed); return Array.from({ length: count }, () => g() * 10); }, [count, seed]);
  const mat = useMemo(() => glow(2.0, 0.85), []);
  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    const gain = 1 + fx.listen * fx.input * 1.6 + fx.speak * fx.voice * 2.0;
    for (let i = 0; i < count; i++) {
      const a = (i / count) * Math.PI * 2;
      const n = Math.sin(t * 2.2 + phases[i]) * 0.5 + Math.sin(t * 5.1 + i * 0.35) * 0.3 + Math.sin(t * 0.7 + i * 0.08) * 0.4;
      const strobe = fx.think * Math.pow(Math.max(0, Math.sin(t * 9 - i * 0.42 + phases[i] * 0.3)), 10) * 0.22;
      const h = (0.02 + Math.pow(Math.max(0, n * 0.5 + 0.5), 2.2) * 0.32) * gain + strobe;
      dummy.position.set(Math.cos(a) * (r + h / 2), Math.sin(a) * (r + h / 2), 0);
      dummy.rotation.set(0, 0, a - Math.PI / 2);
      dummy.scale.set(1, h, 1);
      dummy.updateMatrix();
      ref.current.setMatrixAt(i, dummy.matrix);
    }
    ref.current.instanceMatrix.needsUpdate = true;
  });
  return (
    <instancedMesh ref={ref} args={[undefined, mat, count]} frustumCulled={false}>
      <planeGeometry args={[0.022, 1]} />
    </instancedMesh>
  );
};

export const OrbitDot = ({ r, speed = 0.6, phase = 0 }: { r: number; speed?: number; phase?: number }) => {
  const ref = useRef<THREE.Mesh>(null!);
  const mat = useMemo(() => glow(4, 1), []);
  useFrame(({ clock }) => {
    const a = clock.elapsedTime * speed + phase;
    ref.current.position.set(Math.cos(a) * r, Math.sin(a) * r, 0);
  });
  return (
    <mesh ref={ref} material={mat}>
      <sphereGeometry args={[0.035, 12, 12]} />
    </mesh>
  );
};

/**
 * Acoustic ripples in the ring plane: while listening they converge inward
 * (sound being taken in); while speaking they radiate outward on syllable peaks.
 */
const RIPPLES = 5;
export const Ripples = ({ inner = 1.6, outer = 3.5 }) => {
  const refs = useRef<(THREE.Mesh | null)[]>([]);
  const mats = useMemo(() => Array.from({ length: RIPPLES }, () => new THREE.MeshBasicMaterial({
    color: HOT.clone().multiplyScalar(2.2), transparent: true, opacity: 0, blending: THREE.AdditiveBlending,
    depthWrite: false, side: THREE.DoubleSide, toneMapped: false,
  })), []);
  const st = useMemo(() => ({ life: Array<number>(RIPPLES).fill(1), dirn: Array<number>(RIPPLES).fill(1), next: 0, prev: 0, cool: 0 }), []);
  useFrame((_, dt) => {
    const listening = fx.listen > 0.5, speaking = fx.speak > 0.5;
    const level = speaking ? fx.voice : listening ? fx.input : 0;
    st.cool -= dt;
    if (level > 0.42 && st.prev <= 0.42 && st.cool <= 0) {
      const i = st.next++ % RIPPLES;
      st.life[i] = 0;
      st.dirn[i] = speaking ? 1 : -1;
      st.cool = speaking ? 0.28 : 0.45;
    }
    st.prev = level;
    for (let i = 0; i < RIPPLES; i++) {
      const m = refs.current[i];
      if (!m) continue;
      st.life[i] = Math.min(1, st.life[i] + dt / (st.dirn[i] > 0 ? 1.5 : 1.9));
      const k = st.life[i];
      const e = 1 - Math.pow(1 - k, 2.4);
      const r = st.dirn[i] > 0 ? inner + (outer - inner) * e : outer - (outer - inner) * e;
      m.scale.setScalar(r);
      mats[i].opacity = Math.sin(Math.PI * k) * (st.dirn[i] > 0 ? 0.55 : 0.4) * (1 - fx.deep) * (k < 1 ? 1 : 0);
    }
  });
  return (
    <group>
      {mats.map((mat, i) => (
        <mesh key={i} ref={(m) => { refs.current[i] = m; }} material={mat} scale={inner}>
          <ringGeometry args={[1, 1.006, 160]} />
        </mesh>
      ))}
    </group>
  );
};
