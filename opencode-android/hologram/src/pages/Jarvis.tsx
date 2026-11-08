import Hologram from "@/hologram/Hologram";
import Captions from "@/components/Captions";
import Composer from "@/components/Composer";
import { useJarvis } from "@/jarvis/store";
import { useJarvisSession } from "@/jarvis/useJarvisSession";

import { nativeVoice, useAndroidJarvis } from "@/jarvis/android";

export default function Jarvis() {
  return window.JarvisAndroid ? <AndroidJarvis /> : <BrowserJarvis />;
}

function BrowserJarvis() {
  const { mode } = useJarvis();
  const { busy, status, error, stt, mic, micError, send, stop, toggleMic } = useJarvisSession();
  return (
    <main className="jarvis-root" data-testid="jarvis-root" data-state={mode}>
      <Hologram />
      <div className="jarvis-brand" aria-hidden>
        <span className="jarvis-brand-mark" />
        <span className="jarvis-brand-name">JARVIS</span>
        <span className="jarvis-brand-mode">{mode}</span>
      </div>
      <Captions />
      <Composer
        busy={busy}
        status={status}
        error={error}
        stt={stt}
        mic={mic}
        micError={micError}
        onToggleMic={() => void toggleMic()}
        onSend={send}
        onStop={stop}
      />
    </main>
  );
}

function AndroidJarvis() {
  const vm = useAndroidJarvis();
  return (
    <main className="jarvis-root" data-testid="jarvis-root" data-state={vm.mode}>
      <Hologram />
      <div className="jarvis-brand" aria-hidden>
        <span className="jarvis-brand-mark" />
        <span className="jarvis-brand-name">JARVIS</span>
        <span className="jarvis-brand-mode">{vm.mode}</span>
      </div>
      <div className="jarvis-native-controls">
        <button className="cap-toggle" onClick={() => nativeVoice("settings")}>Voice settings</button>
        <button className="cap-toggle" onClick={vm.newSession} disabled={vm.busy}>New session</button>
      </div>
      <Captions />
      <Composer {...vm} onToggleMic={vm.toggleMic} onSend={vm.send} onStop={vm.stop} />
      {vm.permission && (
        <section className="jarvis-native-permission" role="dialog" aria-label="Action approval">
          <strong>{vm.permission.action}</strong>
          <p>{vm.permission.metadata?.description ?? vm.permission.resources.join(", ")}</p>
          {vm.permission.metadata?.saveDescription && <p>{vm.permission.metadata.saveDescription}</p>}
          <button className="cap-toggle" onClick={() => void vm.replyPermission("once")}>Allow once</button>
          <button className="cap-toggle" onClick={() => void vm.replyPermission("always")}>Always allow</button>
          <button className="cap-toggle" onClick={() => void vm.replyPermission("reject")}>Reject</button>
        </section>
      )}
    </main>
  );
}
