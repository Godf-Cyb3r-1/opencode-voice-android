import { useMemo, useRef } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { Layer } from "./Layer";
import { Particles } from "./Particles";
import { Wire, LatLong, ArcRing, Ticks, Circle, Telemetry, OrbitDot, Ripples } from "./Shapes";
import type { ArcRingProps } from "./Shapes";
import { glow, zoom, fx, fadeables, HOT } from "./materials";
import { jarvis, signal } from "@/jarvis/store";

const RINGS = [
  { r: 1.72, w: 0.035, kind: "long", seed: 11, spin: 0.32 },
  { r: 1.82, w: 0.1, kind: "dash", seed: 4, spin: -0.12, opacity: 0.3 },
  { r: 1.98, w: 0.018, kind: "long", seed: 21, spin: 0.55 },
  { r: 2.05, w: 0.07, kind: "random", seed: 9, spin: -0.24 },
  { r: 2.2, w: 0.008, kind: "full", seed: 1, spin: 0, opacity: 0.5 },
  { r: 2.26, w: 0.045, kind: "random", seed: 33, spin: 0.16 },
  { r: 2.36, w: 0.14, kind: "dash", seed: 17, spin: 0.05, opacity: 0.18 },
  { r: 2.52, w: 0.012, kind: "long", seed: 5, spin: -0.4 },
];

const Core = () => {
  const ref = useRef<THREE.Mesh>(null!);
  const mat = useMemo(() => glow(3.2, 0.9, HOT), []);
  const base = useMemo(() => HOT.clone().multiplyScalar(3.2), []);
  useFrame(({ clock, camera }) => {
    const t = clock.elapsedTime;
    const voice = fx.speak * fx.voice;
    const idle = Math.sin(t * 3.1) * 0.08 + Math.sin(t * 7.3) * 0.04;
    ref.current.scale.setScalar(1 + idle * (1 - fx.speak * 0.6) + voice * 0.55 + fx.listen * (0.08 + fx.input * 0.18) + fx.think * Math.sin(t * 13) * 0.05);
    mat.color.copy(base).multiplyScalar(1 + voice * 1.1 + fx.think * 0.25);
    mat.opacity = 0.9 * THREE.MathUtils.smoothstep(camera.position.length(), 0.25, 0.9) * (1 - fx.deep);
  });
  return (
    <mesh ref={ref} material={mat}>
      <icosahedronGeometry args={[0.14, 2]} />
    </mesh>
  );
};

const SpinRing = ({ spin, ...p }: ArcRingProps & { spin: number }) => {
  const ref = useRef<THREE.Group>(null!);
  useFrame((_, dt) => { ref.current.rotation.z += spin * dt * (1 + fx.think * 1.4 + fx.speak * fx.voice * 0.8); });
  return <group ref={ref}><ArcRing {...p} /></group>;
};

export const Matrix = () => {
  const group = useRef<THREE.Group>(null!);
  const geos = useMemo(
    () => ({
      ico1: new THREE.IcosahedronGeometry(0.42, 1),
      ico2: new THREE.IcosahedronGeometry(0.78, 1),
      ico3: new THREE.IcosahedronGeometry(1.48, 2),
      octa: new THREE.OctahedronGeometry(1.05, 0),
      dodeca: new THREE.DodecahedronGeometry(3.55, 1),
      box: new THREE.BoxGeometry(1.25, 1.25, 1.25),
    }),
    []
  );

  useFrame(({ pointer, clock }, dt) => {
    const g = group.current;
    const t = clock.elapsedTime;
    const D = THREE.MathUtils.damp;
    // listening: a slow, human head-tilt and gaze drift toward the speaker
    const tiltZ = fx.listen * (0.07 + Math.sin(t * 0.43) * 0.05);
    const gazeY = fx.listen * (Math.sin(t * 0.29) * 0.16 + Math.sin(t * 0.71) * 0.04);
    const gazeX = fx.listen * (-0.06 + Math.sin(t * 0.37 + 1) * 0.04);
    g.rotation.x = D(g.rotation.x, -pointer.y * 0.35 + gazeX, 2.5, dt);
    g.rotation.y = D(g.rotation.y, pointer.x * 0.5 + gazeY, 2.5, dt);
    g.rotation.z = D(g.rotation.z, tiltZ, 1.6, dt);
    g.position.x = D(g.position.x, pointer.x * 0.15, 2, dt);
    g.position.y = D(g.position.y, pointer.y * 0.1, 2, dt);
  });

  return (
    <group ref={group}>
      <Layer index={0} spin={[0.4, 0.9, 0.2]} wobble={0.2} pulse={1.4}>
        <Core />
        <Wire geometry={geos.ico1} intensity={2.6} opacity={0.8} seed={2} />
      </Layer>
      <Layer index={1} spin={[0, 0.25, 0]} wobble={0.1} spread={0.25}>
        <Particles count={2600} radius={0.95} />
      </Layer>
      <Layer index={2} spin={[-0.3, -0.6, 0.1]} spread={0.4} pulse={0.8}>
        <Wire geometry={geos.ico2} intensity={1.8} opacity={0.55} seed={3} />
      </Layer>
      <Layer index={3} spin={[0.5, 0.1, -0.35]} spread={0.55} pulse={0.6}>
        <Wire geometry={geos.octa} intensity={1.5} opacity={0.4} seed={4} />
        <Wire geometry={geos.box} intensity={1.1} opacity={0.22} seed={5} />
      </Layer>
      <Layer index={4} spin={[0, 0.18, 0]} wobble={0.15} spread={0.6} base={[0.35, 0, 0.2]}>
        <LatLong r={1.25} />
      </Layer>
      <Layer index={5} spin={[0.08, -0.14, 0.05]} spread={0.75} pulse={0.4}>
        <Wire geometry={geos.ico3} intensity={1.2} opacity={0.22} seed={6} />
        <Particles count={240} radius={1.48} shell size={0.16} amp={0.025} intensity={3.4} seed={19} burst={false} />
      </Layer>
      <Layer index={6} spin={[0, 0, 0]} wobble={0.12} spread={0.9} tilt={[1.1, 0, 0]} pulse={1}>
        {RINGS.map((r, i) => <SpinRing key={i} {...r} />)}
        <Ripples />
      </Layer>
      <Layer index={7} spin={[0, 0, -0.08]} wobble={0.12} spread={1.0} tilt={[1.1, 0, 0]} pulse={0.7}>
        <Ticks />
        <Circle r={2.68} opacity={0.35} />
      </Layer>
      <Layer index={8} spin={[0, 0, 0.05]} wobble={0.12} spread={1.1} tilt={[1.1, 0, 0]} pulse={0.5}>
        <Telemetry />
      </Layer>
      <Layer index={9} spin={[0.15, 0.22, 0]} wobble={0.5} spread={1.3} base={[1.2, 0.3, 0]} tilt={[0.6, 0.4, 0]}>
        <Circle r={3.0} />
        <OrbitDot r={3.0} speed={0.8} />
        <ArcRing r={3.05} w={0.02} kind="long" seed={41} opacity={0.6} />
      </Layer>
      <Layer index={10} spin={[-0.2, 0.1, 0.12]} wobble={0.6} spread={1.45} base={[-0.9, 0.8, 0.4]} tilt={[-0.5, 0.7, 0]}>
        <Circle r={3.2} opacity={0.35} />
        <OrbitDot r={3.2} speed={-0.55} phase={2} />
        <ArcRing r={3.22} w={0.05} kind="dash" seed={8} opacity={0.25} />
      </Layer>
      <Layer index={11} spin={[0.1, -0.16, -0.2]} wobble={0.45} spread={1.6} base={[0.2, 1.4, -0.6]} tilt={[0.3, -0.6, 0.4]}>
        <Circle r={3.38} opacity={0.3} />
        <OrbitDot r={3.38} speed={0.4} phase={4} />
      </Layer>
      <Layer index={12} spin={[0.02, 0.04, 0.01]} wobble={0.05} spread={1.2}>
        <Wire geometry={geos.dodeca} intensity={1} opacity={0.1} seed={7} />
      </Layer>
    </group>
  );
};

const offset = new THREE.Vector3();
const ORIGIN = new THREE.Vector3();

/** synthetic speech envelopes so the simulation reads like a real voice */
const speechEnvelope = (t: number, rate: number, phrase: number) => {
  const syl = Math.max(0, Math.sin(t * rate + Math.sin(t * 1.3) * 1.6));
  const gate = THREE.MathUtils.smoothstep(Math.sin(t * phrase) + 0.55 * Math.sin(t * phrase * 0.37 + 2), -0.35, 0.3);
  return syl * syl * gate * (0.65 + 0.35 * Math.sin(t * 3.7) ** 2);
};

/** eases presence signals, drives camera (scroll zoom + organic listening lean) and shared materials */
export const Rig = () => {
  useFrame(({ camera, size, controls, clock }, rawDt) => {
    const dt = Math.min(rawDt, 1 / 30);
    const t = clock.elapsedTime;
    const mode = jarvis.get().mode;
    const D = THREE.MathUtils.damp;

    fx.listen = D(fx.listen, mode === "listening" ? 1 : 0, 1.7, dt);
    fx.think = D(fx.think, mode === "thinking" ? 1 : 0, 2.0, dt);
    fx.speak = D(fx.speak, mode === "speaking" ? 1 : 0, 2.4, dt);
    const input = signal.input ?? (mode === "listening" ? speechEnvelope(t, 8.3, 1.9) : 0);
    const voice = signal.voice ?? (mode === "speaking" ? speechEnvelope(t + 3, 10.5, 1.4) : 0);
    fx.input = D(fx.input, input, 12, dt);
    fx.voice = D(fx.voice, voice, 16, dt);
    fx.deep = THREE.MathUtils.smoothstep(zoom.scroll, 0.48, 0.8);
    fx.dir.set(Math.cos(t * 0.37), Math.sin(t * 0.23) * 0.7, Math.sin(t * 0.37)).normalize();
    fx.dir2.set(Math.sin(t * 0.19 + 1), Math.cos(t * 0.31), Math.cos(t * 0.19 + 1) * 0.6).normalize();

    // organic lean: an under-damped spring toward an attentive, gently breathing distance
    const lean =
      fx.listen * (0.15 + Math.sin(t * 0.55) * 0.035 + Math.sin(t * 1.31 + 1) * 0.018 + fx.input * 0.045) -
      fx.speak * (0.035 + fx.voice * 0.02) +
      fx.think * (0.05 + Math.sin(t * 0.8) * 0.015);
    const k = 7, c = 2 * Math.sqrt(k) * 0.78;
    fx.leanV += (k * (lean - fx.lean) - c * fx.leanV) * dt;
    fx.lean += fx.leanV * dt;

    for (const m of fadeables) {
      const out = m.userData.fade === "out";
      const f = out ? 1 - fx.deep : fx.deep;
      if (m instanceof THREE.ShaderMaterial) {
        m.uniforms.uFade.value = f;
        m.uniforms.uTime.value = t;
        m.uniforms.uFlow.value = (m.userData.flow ?? 0) + fx.think * (out ? 1 : 0.9) + fx.speak * fx.voice * 0.35;
      } else {
        m.opacity = m.userData.base * f;
      }
    }

    const ctl = controls as unknown as { target: THREE.Vector3; rotateSpeed: number } | null;
    zoom.scroll = D(zoom.scroll, zoom.target, 2.2, dt);
    const fit = Math.max(1, 0.95 / (size.width / size.height));
    const d = 14 * fit * Math.pow(0.006 / fit, zoom.scroll) * (1 - fx.lean * (1 - fx.deep));
    const target = ctl?.target ?? ORIGIN;
    offset.copy(camera.position).sub(target);
    offset.setLength(Math.exp(D(Math.log(offset.length()), Math.log(d), 3, dt)));
    camera.position.copy(target).add(offset);
    if (ctl) ctl.rotateSpeed = 0.15 + 0.45 * Math.min(1, offset.length() / 6);
  });
  return null;
};
