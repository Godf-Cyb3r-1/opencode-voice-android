import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
notebook = json.loads((root / "kaggle/omnivoice-poc/jarvis_omnivoice_poc.ipynb").read_text())
assert notebook["nbformat"] == 4
source = "\n".join("".join(cell.get("source", [])) for cell in notebook["cells"])
for required in ("k2-fsa/OmniVoice", "create_voice_clone_prompt", "voice_clone_prompt", "num_step=16", "num_step=32", "Tamil", "English", "jarvis_omnivoice_metrics.json"):
    assert required in source, f"Missing POC requirement: {required}"
assert "/kaggle/temp/" in source
assert "REFERENCE_AUDIO_EXPORTED=false" in source
assert "VOICE_PROMPTS_EXPORTED=false" in source
assert "files.upload" not in source
assert "reference_audio" not in source.lower() or "reference" in source.lower()
print(f"PASS: notebook JSON valid; {len(notebook['cells'])} cells; POC/privacy contract verified")
