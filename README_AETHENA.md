# Aethena Android Agent

Aethena is an Android AI companion with chat, voice, philosophy modes, phone automation, encrypted model settings, a floating orb, Termux commands, and a code-to-ZIP workspace.

## First launch

1. Open **Settings** inside Aethena.
2. Choose **Local** for a llama.cpp server at `127.0.0.1:8080`, or paste a hosted OpenAI-compatible endpoint, model name, and token.
3. Press **Save encrypted settings**.
4. Open **Operate** and enable screen control, notification access, and overlay permission.
5. Press **Start orb**.

## Brain options

- Local llama.cpp server: `http://127.0.0.1:8080/v1`
- Hugging Face or another hosted provider: use its OpenAI-compatible base URL and model ID
- OpenAI backup: press the preset and paste your key

The API token is stored with Android encrypted preferences. It is not included in the source code or APK.

## Termux bridge

Termux must enable external commands in `~/.termux/termux.properties`:

```text
allow-external-apps=true
```

Restart Termux after changing it.

## Current MVP abilities

- Freeform, Deep Thought, Council, and Architect chat modes
- Speech recognition and text-to-speech
- Open apps and URLs
- Read visible screen text
- Tap controls by visible text
- Type into the focused field
- Scroll, Back, Home, and Recents
- Read recent notifications
- Floating Aethena orb
- Run explicit Termux commands
- Generate complete projects and export a ZIP

This branch is isolated from the repository's main branch.
