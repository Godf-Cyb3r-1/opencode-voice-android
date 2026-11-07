import { useEffect } from "react";
import { Canvas } from "@react-three/fiber";
import { OrbitControls } from "@react-three/drei";
import { EffectComposer, Bloom, Vignette, ChromaticAberration } from "@react-three/postprocessing";
import * as THREE from "three";
import { Matrix, Rig } from "./Matrix";
import { Nucleus } from "./Nucleus";
import { zoom } from "./materials";

const useScrollInput = () => {
  useEffect(() => {
    const nudge = (v: number) => { zoom.target = Math.min(1, Math.max(0, zoom.target + v)); };
    const onWheel = (e: WheelEvent) => { e.preventDefault(); nudge(Math.sign(e.deltaY) * Math.min(Math.abs(e.deltaY), 120) * 0.0007); };
    let pinch: number | null = null;
    const dist = (t: TouchList) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
    const onTouchMove = (e: TouchEvent) => {
      if (e.touches.length !== 2) { pinch = null; return; }
      const d = dist(e.touches);
      if (pinch !== null) nudge((d - pinch) * 0.003);
      pinch = d;
    };
    const onTouchEnd = () => { pinch = null; };
    const onKey = (e: KeyboardEvent) => { if (e.key === "r" || e.key === "R") zoom.target = 0; };
    window.addEventListener("keydown", onKey);
    window.addEventListener("wheel", onWheel, { passive: false });
    window.addEventListener("touchmove", onTouchMove, { passive: true });
    window.addEventListener("touchend", onTouchEnd);
    return () => {
      window.removeEventListener("wheel", onWheel);
      window.removeEventListener("touchmove", onTouchMove);
      window.removeEventListener("touchend", onTouchEnd);
      window.removeEventListener("keydown", onKey);
    };
  }, []);
};

const caOffset = new THREE.Vector2(0.0009, 0.0006);

export default function Hologram() {
  useScrollInput();
  return (
    <div className="holo-stage" data-testid="hologram-stage">
      <Canvas
        data-testid="hologram-canvas"
        dpr={[1, 2]}
        camera={{ position: [0, 0.6, 14], fov: 45, near: 0.001, far: 200 }}
        gl={{ antialias: true, alpha: false, powerPreference: "high-performance" }}
        onCreated={({ gl }) => gl.setClearColor("#000000", 1)}
      >
        <Rig />
        <Matrix />
        <Nucleus />
        <OrbitControls makeDefault enableDamping dampingFactor={0.05} enableZoom={false} enablePan screenSpacePanning panSpeed={0.5} rotateSpeed={0.6} />
        <EffectComposer multisampling={0}>
          <Bloom mipmapBlur intensity={1.5} luminanceThreshold={0.22} luminanceSmoothing={0.3} radius={0.72} />
          <ChromaticAberration offset={caOffset} radialModulation={false} modulationOffset={0} />
          <Vignette offset={0.25} darkness={0.85} />
        </EffectComposer>
      </Canvas>
    </div>
  );
}
