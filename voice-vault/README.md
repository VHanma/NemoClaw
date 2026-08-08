# Voice Vault Android

Voice Vault is an Android-first offline voice-cloning reader built for arm64 phones.

## Goals

- Store many named voice profiles locally.
- Create a profile from a short microphone recording or imported PCM WAV reference.
- Generate speech with the selected cloned voice.
- Paste/type text or import TXT, MD, CSV, JSON, HTML, EPUB, DOCX, and PDF documents.
- Long-text chunking with stop control.
- No account, subscription, or cloud inference.
- Models download once to app-private storage on first setup.

## Engine

The first mobile engine is PocketTTS through `k2-fsa/sherpa-onnx`, because PocketTTS supports zero-shot cloning from reference audio without requiring a matching transcript. A ZipVoice high-quality mode can be added as the second engine.

Model used by v0.1:

`sherpa-onnx-pocket-tts-int8-2026-01-26`

The model is downloaded from the official sherpa-onnx release when the user taps **Install Voice Engine**.

## Voice profiles

A voice profile contains a user-chosen name and a local reference WAV. Voice data remains on the device. Voice Vault is intended for voices the user owns or has permission to synthesize.

## Building

`.github/workflows/build-voice-vault.yml` clones the pinned sherpa-onnx source, builds the arm64 JNI runtime, overlays the Voice Vault Android UI and engine files, then builds and uploads `VoiceVault-arm64-debug.apk` as a workflow artifact.

The APK intentionally does not bundle the neural model, which keeps the install package much smaller and permits model upgrades without rebuilding the APK.
