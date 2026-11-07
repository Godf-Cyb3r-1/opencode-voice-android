import { useRef } from "react";
import type { ReactNode } from "react";
import { useFrame } from "@react-three/fiber";
import type { Group } from "three";
import { zoom, fx, easeOutExpo, clamp01 } from "./materials";

type Vec3 = [number, number, number];
interface LayerProps {
  index?: number;
  /** rad/s per axis */
  spin?: Vec3;
  /** axis-shift amplitude */
  wobble?: number;
  /** scroll expansion */
  spread?: number;
  /** scroll tilt */
  tilt?: Vec3;
  base?: Vec3;
  /** how strongly this layer breathes with voice / listening */
  pulse?: number;
  children?: ReactNode;
}

export const Layer = ({ index = 0, spin = [0, 0.2, 0], wobble = 0.3, spread = 0, tilt = [0, 0, 0], base = [0, 0, 0], pulse = 0, children }: LayerProps) => {
  const outer = useRef<Group>(null!);
  const inner = useRef<Group>(null!);
  useFrame(({ clock }, dt) => {
    const t = clock.elapsedTime;
    const intro = easeOutExpo(clamp01((t - 0.2 - index * 0.11) / 1.8));
    const s = Math.max(0, Math.min(1, (zoom.scroll - 0.1) / 0.35)) * 0.6;
    // breaching the nucleus: outer shells scale past the camera, outermost fastest
    const out = 1 + fx.deep * fx.deep * (4 + index * 0.9);
    const breathe = 1 + pulse * (fx.speak * fx.voice * 0.05 + fx.listen * (fx.input * 0.025 - 0.02));
    const spinBoost = 1 + fx.think * 0.6 + fx.listen * 0.25;
    outer.current.visible = fx.deep < 0.995;
    outer.current.scale.setScalar(Math.max(0.0001, intro * (1 + s * spread) * out * breathe));
    outer.current.rotation.set(
      base[0] + tilt[0] * s + Math.sin(t * 0.21 + index) * wobble * (1 - fx.listen * 0.5),
      base[1] + tilt[1] * s + Math.cos(t * 0.17 + index * 1.7) * wobble * 0.6,
      base[2] + tilt[2] * s + (1 - intro) * 1.2
    );
    inner.current.rotation.x += spin[0] * dt * spinBoost;
    inner.current.rotation.y += spin[1] * dt * spinBoost;
    inner.current.rotation.z += spin[2] * dt * spinBoost;
  });
  return (
    <group ref={outer} scale={0.0001}>
      <group ref={inner}>{children}</group>
    </group>
  );
};
