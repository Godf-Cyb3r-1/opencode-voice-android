import { useMemo } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { AMBER, fx, rng } from "./materials";

const vertexShader = `
uniform float uTime; uniform float uSize; uniform float uAmp; uniform float uDpr;
uniform float uThink; uniform float uSwell; uniform vec3 uDir; uniform vec3 uDir2;
attribute float aSeed;
varying float vAlpha; varying float vFire;
void main(){
  vec3 p = position * (1.0 + uSwell);
  float s = aSeed * 6.2831;
  float pulse = sin(uTime * (1.2 + aSeed * 3.5) + s);
  p += normalize(position + 0.0001) * pulse * uAmp;
  p += vec3(sin(uTime*2.1+s*3.0), cos(uTime*1.7+s*5.0), sin(uTime*2.7+s*7.0)) * uAmp * 0.35;
  // neural firing: two non-synchronised waves sweep the lattice, a sparse subset of nodes ignites
  vec3 n = normalize(position + 0.0001);
  float w1 = sin(dot(n, uDir) * 3.2 + length(position) * 5.0 - uTime * 4.4 + aSeed * 2.4);
  float w2 = sin(dot(n, uDir2) * 2.4 - length(position) * 4.0 - uTime * 3.1 + aSeed * 3.1);
  float fire = max(pow(max(w1, 0.0), 12.0), 0.75 * pow(max(w2, 0.0), 14.0)) * step(0.55, fract(aSeed * 7.13)) * uThink;
  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_PointSize = min(uSize * uDpr * (0.55 + 0.7 * max(pulse, 0.0) + fire * 2.6) * (10.0 / max(-mv.z, 0.02)), 90.0);
  vAlpha = (0.25 + 0.75 * smoothstep(-0.6, 1.0, pulse)) * smoothstep(0.03, 0.3, -mv.z);
  vFire = fire;
  gl_Position = projectionMatrix * mv;
}`;

const fragmentShader = `
uniform vec3 uColor; uniform float uFade; varying float vAlpha; varying float vFire;
void main(){
  float d = length(gl_PointCoord - 0.5);
  float a = smoothstep(0.5, 0.0, d);
  a *= a;
  float k = vAlpha * uFade;
  gl_FragColor = vec4(uColor * a * k * (1.0 + vFire * 2.2), a * k);
}`;

const ballPositions = (count: number, radius: number, seed: number, shell: boolean) => {
  const r = rng(seed);
  const pos = new Float32Array(count * 3);
  const seeds = new Float32Array(count);
  for (let i = 0; i < count; i++) {
    let x: number, y: number, z: number, l: number;
    if (shell) {
      const k = i + 0.5;
      const phi = Math.acos(1 - (2 * k) / count);
      const th = Math.PI * (1 + Math.sqrt(5)) * k;
      x = Math.cos(th) * Math.sin(phi); y = Math.sin(th) * Math.sin(phi); z = Math.cos(phi); l = radius;
    } else {
      do { x = r() * 2 - 1; y = r() * 2 - 1; z = r() * 2 - 1; } while (x * x + y * y + z * z > 1);
      l = (radius * Math.pow(r(), 0.6)) / (Math.hypot(x, y, z) || 1);
    }
    pos.set([x * l, y * l, z * l], i * 3);
    seeds[i] = r();
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  g.setAttribute("aSeed", new THREE.BufferAttribute(seeds, 1));
  return g;
};

interface ParticlesProps {
  count?: number; radius?: number; size?: number; amp?: number; intensity?: number;
  seed?: number; shell?: boolean; burst?: boolean;
}

export const Particles = ({ count = 2400, radius = 0.95, size = 0.09, amp = 0.06, intensity = 2.4, seed = 7, shell = false, burst = true }: ParticlesProps) => {
  const geometry = useMemo(() => ballPositions(count, radius, seed, shell), [count, radius, seed, shell]);
  const material = useMemo(
    () =>
      new THREE.ShaderMaterial({
        vertexShader,
        fragmentShader,
        uniforms: {
          uTime: { value: 0 },
          uSize: { value: size },
          uAmp: { value: amp },
          uDpr: { value: Math.min(window.devicePixelRatio, 2) * 30 },
          uColor: { value: AMBER.clone().lerp(new THREE.Color("#ffd08a"), 0.35).multiplyScalar(intensity) },
          uThink: { value: 0 },
          uSwell: { value: 0 },
          uFade: { value: 1 },
          uDir: { value: fx.dir },
          uDir2: { value: fx.dir2 },
        },
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
        toneMapped: false,
      }),
    [size, amp, intensity]
  );
  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    const u = material.uniforms;
    u.uTime.value = t;
    const spike = burst ? Math.pow(Math.max(0, Math.sin(t * 0.9) * Math.sin(t * 2.3)), 3) : 0;
    // listening: the core leans open and ripples with incoming sound; speaking: it swells with the voice
    u.uAmp.value = amp * (1 + spike * 2.5) * (1 + fx.listen * fx.input * 1.8 + fx.speak * fx.voice * 1.2);
    u.uSwell.value = fx.listen * (0.06 + fx.input * 0.1) + fx.speak * fx.voice * 0.12;
    u.uThink.value = fx.think;
    u.uFade.value = 1 - fx.deep;
  });
  return <points geometry={geometry} material={material} />;
};
