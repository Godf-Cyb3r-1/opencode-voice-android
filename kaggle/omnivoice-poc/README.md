# Jarvis OmniVoice Kaggle POC

This is the first implementation milestone for the Jarvis voice engine. It compares three private reference clips: one Tamil-English mixed clip, one Tamil-only clip, and one English-only clip.

## Prepare the private Kaggle dataset

Create a **private** Kaggle dataset named `jarvis-reference-audio` with this layout:

```
mixed.wav
tamil.wav
english.wav
```

Use recordings you own or have permission to clone. Each clip must be clean, mono/stereo, and 3–10 seconds. Prepare exact transcripts for all three clips.

## Run in Kaggle

1. Create a private Kaggle Notebook and enable a GPU accelerator.
2. Import `jarvis_omnivoice_poc.ipynb` from this folder.
3. Add your private `jarvis-reference-audio` dataset as an input. The mounted path must be `/kaggle/input/jarvis-reference-audio/`.
4. Set the three exact transcripts in the reference configuration cell.
5. Run all cells. Listen to each sample and inspect the timing table.
6. Save & Run All to create a reproducible Kaggle version.
7. Download `jarvis_omnivoice_metrics.json` from notebook output. It contains timings and hardware metadata only; it excludes voice data and transcripts.

The notebook creates reusable voice clone prompts once per reference, then reuses each prompt for all test utterances. Prompt files are written only under `/kaggle/temp` and are not part of the saved output.

## GitHub Actions

- `OmniVoice POC validation` runs on changes and validates notebook structure, metadata and privacy invariants.
- `Run OmniVoice Kaggle POC` is a manual workflow. Add repository Actions secrets `KAGGLE_USERNAME` and `KAGGLE_KEY`, then dispatch it with your private Kaggle dataset slug (for example `username/jarvis-reference-audio`). It pushes the notebook as a private Kaggle kernel, waits for execution, downloads the metrics report, and stores that report as a workflow artifact.

No reference audio, transcript, voice prompt, or generated WAV is uploaded to GitHub. Kaggle GPU availability and quota are controlled by Kaggle; a GitHub Actions job cannot guarantee a GPU slot.

## What counts as real evidence

A successful notebook validation is **not** a model inference test. Real POC evidence requires a completed Kaggle kernel run, a metrics JSON from that run, and human listening review of the private notebook audio outputs. The report records model load time, prompt preparation time, synthesis latency, output duration, RTF, GPU and software versions.
